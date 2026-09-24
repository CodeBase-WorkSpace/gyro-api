package com.gyro.api.notification.application

import com.gyro.api.common.outbox.InvalidOutboxPayloadException
import com.gyro.api.common.outbox.OutboxConsumer
import com.gyro.api.common.outbox.OutboxEvent
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationOutboxWriter
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class CoreProbeOutboxPayload(
    val recipientUserId: UUID,
    val idempotencyKey: String,
    val occurredAt: Instant,
    val scheduledAt: Instant,
    val expiresAt: Instant,
    val requestId: String,
)

@Component
class NotificationOutboxConsumer(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
) : OutboxConsumer {
    override val consumerName = "notification-core"

    override fun supports(eventType: String) = eventType in setOf(CORE_PROBE_EVENT, PaymentVerifiedNotificationOutboxWriter.PAYMENT_VERIFIED_EVENT)

    override fun consume(event: OutboxEvent) {
        when (event.eventType) {
            CORE_PROBE_EVENT -> consumeCoreProbe(event)
            PaymentVerifiedNotificationOutboxWriter.PAYMENT_VERIFIED_EVENT -> consumeVerifiedPayment(event)
        }
    }

    private fun consumeCoreProbe(event: OutboxEvent) {
        val payload = try {
            objectMapper.readValue(event.payload, CoreProbeOutboxPayload::class.java)
        } catch (exception: Exception) {
            throw InvalidOutboxPayloadException("Invalid notification core payload for event ${event.id}", exception)
        }
        val eventId = requireNotNull(event.id)
        val intentId = notificationService.create(
            NotificationRequest(
                recipientUserId = payload.recipientUserId,
                type = NotificationType.CORE_PROBE,
                templateData = emptyMap(),
                occurredAt = payload.occurredAt,
                scheduledAt = payload.scheduledAt,
                expiresAt = payload.expiresAt,
                idempotencyKey = payload.idempotencyKey,
                requestId = payload.requestId,
                sourceType = "OUTBOX_EVENT",
                sourceReference = eventId.toString(),
            ),
        )
        log.info("event=notification_intent_created eventId={} intentId={} type={}", eventId, intentId, NotificationType.CORE_PROBE)
    }

    private fun consumeVerifiedPayment(event: OutboxEvent) {
        val payload = try {
            objectMapper.readValue(event.payload, PaymentVerifiedNotificationPayload::class.java)
        } catch (exception: Exception) {
            throw InvalidOutboxPayloadException("Invalid payment notification payload for event ${event.id}", exception)
        }
        val eventId = requireNotNull(event.id)
        val intentId = notificationService.create(
            NotificationRequest(
                recipientUserId = payload.userId,
                type = NotificationType.PAYMENT_VERIFIED,
                templateData = mapOf(
                    "amount" to TemplateVariableValue.Number(payload.amount.amount),
                    "currency" to TemplateVariableValue.Text(payload.amount.currency),
                ),
                occurredAt = payload.occurredAt,
                scheduledAt = payload.occurredAt,
                expiresAt = payload.occurredAt.plus(java.time.Duration.ofHours(24)),
                idempotencyKey = "payment-verified:${payload.paymentAttemptId}",
                requestId = payload.requestId,
                sourceType = "OUTBOX_EVENT",
                sourceReference = eventId.toString(),
            ),
        )
        log.info("event=notification_intent_created eventId={} intentId={} type={}", eventId, intentId, NotificationType.PAYMENT_VERIFIED)
    }

    companion object {
        const val CORE_PROBE_EVENT = "notification.core.probe-requested"
        private val log = LoggerFactory.getLogger(NotificationOutboxConsumer::class.java)
    }
}
