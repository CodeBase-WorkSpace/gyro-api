package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.policy.NotificationPolicyRegistry
import com.gyro.api.notification.application.template.NotificationTemplateService
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class NotificationCreateOutcome(
    val intentId: UUID,
    val created: Boolean,
    val status: NotificationIntentStatus = NotificationIntentStatus.ROUTED,
    val reason: NotificationReason? = null,
)

@Service
class NotificationService(
    private val policies: NotificationPolicyRegistry,
    private val templateService: NotificationTemplateService,
    private val routing: NotificationRoutingService,
    private val cadence: NotificationCadenceService,
    private val intentCreation: NotificationIntentCreationRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val objectMapper: ObjectMapper,
    private val metrics: NotificationMetrics,
    private val time: TimeProvider,
) {
    @Transactional
    fun create(request: NotificationRequest): UUID = createWithOutcome(request).intentId

    @Transactional
    fun createWithOutcome(request: NotificationRequest): NotificationCreateOutcome {
        validateRequest(request)
        val policy = policies.policyFor(request.type)
        val persistedVariables = templateService.validate(policy, request.templateData)
        val budget = cadence.announcementBudget(
            userId = request.recipientUserId,
            type = request.type,
            category = policy.category,
            scheduledAt = request.scheduledAt,
        )
        val routingResult = if (budget.allowed) {
            routing.resolve(request.recipientUserId, policy)
        } else {
            NotificationRoutingResult(emptyList(), NotificationReason.BUDGET_LIMIT)
        }
        val terminalReason = routingResult.unavailableReason
        val now = time.now()
        val intent = NotificationIntentEntity(
                userId = request.recipientUserId,
                type = request.type,
                category = policy.category,
                risk = policy.risk,
                routeStrategy = policy.routeStrategy,
                occurredAt = request.occurredAt,
                scheduledAt = request.scheduledAt,
                expiresAt = request.expiresAt,
                idempotencyKey = request.idempotencyKey,
                sourceType = request.sourceType,
                sourceReference = request.sourceReference,
                requestId = request.requestId,
                templateData = objectMapper.writeValueAsString(persistedVariables),
                status = when {
                    !budget.allowed -> NotificationIntentStatus.SUPPRESSED
                    routingResult.routes.isEmpty() -> NotificationIntentStatus.UNDELIVERABLE
                    else -> NotificationIntentStatus.ROUTED
                },
                reason = terminalReason,
                terminalAt = if (routingResult.routes.isEmpty()) now else null,
        )
        val insertedId = intentCreation.insertIfAbsent(intent)
            ?: return intentCreation.findOutcome(request.sourceType, request.idempotencyKey).let { existing ->
                NotificationCreateOutcome(
                    intentId = existing.intentId,
                    created = false,
                    status = existing.status,
                    reason = existing.reason,
                )
            }

        routingResult.routes.forEach { route ->
            val rendered = templateService.render(policy, request.templateData, route.channel)
            val deliveryId = UUID.randomUUID()
            deliveries.save(
                NotificationDeliveryEntity(
                    id = deliveryId,
                    intentId = insertedId,
                    channel = route.channel,
                    endpointReference = route.endpointReference,
                    providerRequestId = providerRequestId(deliveryId),
                    adapterKey = route.adapterKey,
                    templateKey = rendered.key,
                    templateVersion = rendered.version,
                    templateLocale = rendered.locale,
                    renderedSubject = rendered.subject,
                    renderedPlainBody = rendered.plainBody,
                    renderedHtmlBody = rendered.htmlBody,
                    dueAt = request.scheduledAt,
                    expiresAt = request.expiresAt,
                ),
            )
            metrics.deliveryCreated(request.type, route.channel, route.adapterKey)
        }
        metrics.intentCreated(request.type, policy.category)
        if (routingResult.routes.isEmpty()) {
            metrics.intentOutcome(request.type, intent.status, terminalReason)
        }
        return NotificationCreateOutcome(
            intentId = insertedId,
            created = true,
            status = intent.status,
            reason = terminalReason,
        )
    }

    private fun validateRequest(request: NotificationRequest) {
        val policy = policies.policyFor(request.type)
        require(!request.occurredAt.isAfter(request.scheduledAt)) { "Notification cannot be scheduled before occurrence" }
        require(request.scheduledAt.isBefore(request.expiresAt)) { "Notification expiry must be after its schedule" }
        require(time.now().isBefore(request.expiresAt)) { "Notification is already expired" }
        require(!request.expiresAt.isAfter(request.occurredAt.plus(policy.defaultExpiry))) {
            "Notification expiry exceeds the registered policy"
        }
        require(request.idempotencyKey.isNotBlank() && request.idempotencyKey.length <= 192) {
            "Notification idempotency key is invalid"
        }
        require(request.requestId.isNotBlank() && request.requestId.length <= 128) { "Notification request ID is invalid" }
        require(request.sourceType.isNotBlank() && request.sourceType.length <= 64) { "Notification source type is invalid" }
        require(request.sourceReference.isNotBlank() && request.sourceReference.length <= 192) {
            "Notification source reference is invalid"
        }
    }

    companion object {
        private fun providerRequestId(deliveryId: UUID) = "gyro-notification-$deliveryId"
    }
}
