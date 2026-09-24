package com.gyro.api.notification.application

import com.gyro.api.common.outbox.InvalidOutboxPayloadException
import com.gyro.api.common.outbox.OutboxConsumer
import com.gyro.api.common.outbox.OutboxEvent
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.EntitlementService
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Sends one general notice when premium access lapses. The notification is not
 * tied to a specific feature because schedules, future planning, and higher
 * limits all share the same preserve-and-restore downgrade policy.
 */
@Component
class PremiumAccessDowngradeNoticeConsumer(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
    private val entitlementService: EntitlementService,
    private val entitlementGateService: EntitlementGateService,
) : OutboxConsumer {
    override val consumerName = "premium-access-downgrade-notice"

    override fun supports(eventType: String) = eventType in LAPSE_EVENT_TYPES

    override fun consume(event: OutboxEvent) {
        val payload = try {
            objectMapper.readValue(event.payload, SubscriptionEventPayload::class.java)
        } catch (exception: Exception) {
            throw InvalidOutboxPayloadException("Invalid subscription event payload for event ${event.id}", exception)
        }
        val eventId = requireNotNull(event.id)
        val currentEntitlement = entitlementService.compute(payload.userId)
        if (entitlementGateService.hasAnyFeatureAccess(currentEntitlement)) {
            log.info(
                "event=premium_access_downgrade_notice outcome=skipped reason=access_retained eventId={} userId={} status={} source={}",
                eventId,
                payload.userId,
                currentEntitlement.status,
                currentEntitlement.source,
            )
            return
        }
        val lapseAt = when (payload.transitionType) {
            "GRACE_EXIT_FAILURE" -> payload.occurredAt
            else -> payload.periodEnd ?: payload.occurredAt
        }

        val intentId = notificationService.create(
            NotificationRequest(
                recipientUserId = payload.userId,
                type = NotificationType.PREMIUM_ACCESS_DOWNGRADED,
                templateData = emptyMap(),
                occurredAt = payload.occurredAt,
                scheduledAt = payload.occurredAt,
                expiresAt = payload.occurredAt.plus(Duration.ofHours(48)),
                idempotencyKey = "premium-access-downgrade:${payload.userId}:${lapseAt.epochSecond}",
                requestId = "premium-access-downgrade-$eventId",
                sourceType = "OUTBOX_EVENT",
                sourceReference = eventId.toString(),
            ),
        )
        log.info("event=premium_access_downgrade_notice outcome=created eventId={} intentId={}", eventId, intentId)
    }

    companion object {
        val LAPSE_EVENT_TYPES = setOf(
            "subscription.PERIOD_EXPIRED",
            "subscription.GRACE_EXIT_FAILURE",
            "subscription.MANUAL_GRANT_EXPIRED",
        )
        private val log = LoggerFactory.getLogger(PremiumAccessDowngradeNoticeConsumer::class.java)
    }
}
