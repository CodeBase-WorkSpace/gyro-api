package com.gyro.api.notification.application

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.policy.NotificationPolicyRegistry
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Creates one operator-initiated recovery intent for a terminal failed payment receipt.
 *
 * Payment receipts do not automatically retry ambiguous SMTP outcomes. The recovery intent is
 * intentionally idempotent per failed delivery, so support can safely use this action once after
 * reconciling the provider state without overwriting the original delivery evidence.
 */
@Service
class MandatoryReceiptRecoveryService(
    private val intents: NotificationIntentRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val notifications: NotificationService,
    private val policies: NotificationPolicyRegistry,
    private val objectMapper: ObjectMapper,
    private val time: TimeProvider,
    private val audit: AccountAuditService,
) {
    @Transactional
    fun retryFailedPaymentReceipt(intentId: UUID, deliveryId: UUID, operatorId: UUID): UUID {
        val intent = intents.findById(intentId).orElseThrow { ResourceNotFoundException("Notification intent") }
        requirePaymentReceipt(intent)
        val delivery = deliveries.findById(deliveryId).orElseThrow { ResourceNotFoundException("Notification delivery") }
        require(delivery.intentId == intentId) { "Delivery does not belong to the notification intent" }
        require(delivery.status in RECOVERABLE_DELIVERY_STATUSES) {
            "Only terminal failed payment deliveries can be manually retried"
        }

        val now = time.now()
        val policy = policies.policyFor(NotificationType.PAYMENT_VERIFIED)
        val recoveryIntentId = notifications.create(
            NotificationRequest(
                recipientUserId = intent.userId,
                type = intent.type,
                templateData = restoreTemplateData(intent, policy),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(policy.defaultExpiry),
                idempotencyKey = "manual-payment-receipt-retry:$deliveryId",
                requestId = "admin-manual-retry:$deliveryId",
                sourceType = MANUAL_RETRY_SOURCE,
                sourceReference = "delivery:$deliveryId;operator:$operatorId",
            ),
        )
        audit.record(
            actorUserId = operatorId,
            targetUserId = intent.userId,
            eventType = AccountAuditEventType.ADMIN_PAYMENT_RECEIPT_RETRY_REQUESTED,
            reason = delivery.reason?.name,
            metadata = mapOf(
                "failedIntentId" to intentId.toString(),
                "failedDeliveryId" to deliveryId.toString(),
                "recoveryIntentId" to recoveryIntentId.toString(),
            ),
        )
        return recoveryIntentId
    }

    private fun requirePaymentReceipt(intent: NotificationIntentEntity) {
        require(intent.type == NotificationType.PAYMENT_VERIFIED) {
            "Only payment receipt notifications can be manually retried"
        }
    }

    private fun restoreTemplateData(
        intent: NotificationIntentEntity,
        policy: NotificationPolicy,
    ): Map<String, TemplateVariableValue> {
        val persisted = requireNotNull(intent.templateData) { "Payment receipt has no recoverable template data" }
        val values = objectMapper.readTree(persisted)
        return policy.requiredVariables.mapValues { (name, rule) ->
            val value = values.path(name).takeUnless { it.isMissingNode || it.isNull }
                ?: throw FieldValidationException(
                    message = "Payment receipt recovery data is incomplete.",
                    fieldErrors = emptyList(),
                )
            when (rule.type) {
                TemplateVariableType.TEXT -> TemplateVariableValue.Text(value.asString())
                TemplateVariableType.NUMBER -> TemplateVariableValue.Number(BigDecimal(value.asString()))
                TemplateVariableType.INSTANT -> TemplateVariableValue.Timestamp(Instant.parse(value.asString()))
                TemplateVariableType.BOOLEAN -> TemplateVariableValue.Flag(value.asString().toBooleanStrict())
            }
        }
    }

    private companion object {
        const val MANUAL_RETRY_SOURCE = "ADMIN_MANUAL_RECEIPT_RETRY"
        val RECOVERABLE_DELIVERY_STATUSES = setOf(
            NotificationDeliveryStatus.PERMANENT_FAILURE,
            NotificationDeliveryStatus.DEAD_LETTER,
        )
    }
}
