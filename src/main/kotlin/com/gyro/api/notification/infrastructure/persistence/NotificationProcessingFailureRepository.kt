package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_ATTEMPTS
import com.gyro.api.jooq.Tables.NOTIFICATION_DELIVERIES
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationDeliveryClaim
import com.gyro.api.notification.domain.NotificationDeliveryStatus
import com.gyro.api.notification.domain.NotificationReason
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class NotificationProcessingFailureResult(
    val intentId: UUID,
    val providerRequestId: String,
    val adapterKey: String,
    val channel: NotificationChannel,
    val attemptNumber: Int,
    val status: NotificationDeliveryStatus,
)

@Repository
class NotificationProcessingFailureRepository(
    private val dsl: DSLContext,
) {
    fun record(
        claim: NotificationDeliveryClaim,
        now: Instant,
        transient: Boolean,
        backoff: Duration,
        maxAttempts: Int,
        contentRetention: Duration,
        preserveIncompleteAttempt: Boolean,
    ): NotificationProcessingFailureResult? {
        val timestamp = now.atOffset(ZoneOffset.UTC)
        val delivery = dsl.select(
            NOTIFICATION_DELIVERIES.INTENT_ID,
            NOTIFICATION_DELIVERIES.PROVIDER_REQUEST_ID,
            NOTIFICATION_DELIVERIES.ADAPTER_KEY,
            NOTIFICATION_DELIVERIES.CHANNEL,
            NOTIFICATION_DELIVERIES.ATTEMPT_COUNT,
            NOTIFICATION_DELIVERIES.EXPIRES_AT,
        )
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.ID.eq(claim.deliveryId)
                    .and(NOTIFICATION_DELIVERIES.STATUS.eq(NotificationDeliveryStatus.CLAIMED.name))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_OWNER.eq(claim.owner))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_TOKEN.eq(claim.token)),
            )
            .forUpdate()
            .fetchOne()
            ?: return null

        val incompleteAttempt = dsl.select(
            NOTIFICATION_ATTEMPTS.ID,
            NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER,
            NOTIFICATION_ATTEMPTS.STARTED_AT,
        )
            .from(NOTIFICATION_ATTEMPTS)
            .where(
                NOTIFICATION_ATTEMPTS.DELIVERY_ID.eq(claim.deliveryId)
                    .and(NOTIFICATION_ATTEMPTS.COMPLETED_AT.isNull),
            )
            .orderBy(NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER.desc())
            .limit(1)
            .fetchOne()
        val attemptNumber = incompleteAttempt?.get(NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER)
            ?: requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ATTEMPT_COUNT)) + 1
        val retryAt = now.plus(backoff)
        val expiresAt = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.EXPIRES_AT)).toInstant()
        val canRetry = transient &&
            (preserveIncompleteAttempt || attemptNumber < maxAttempts) &&
            retryAt.isBefore(expiresAt)
        val status = if (canRetry) NotificationDeliveryStatus.RETRY_SCHEDULED else NotificationDeliveryStatus.DEAD_LETTER
        val preserveAttempt = preserveIncompleteAttempt && canRetry && incompleteAttempt != null

        // A transient post-send failure keeps the attempt incomplete so the retry reconciles instead of resending.
        if (!preserveAttempt) {
            if (incompleteAttempt == null) {
                dsl.insertInto(NOTIFICATION_ATTEMPTS)
                    .set(NOTIFICATION_ATTEMPTS.ID, UUID.randomUUID())
                    .set(NOTIFICATION_ATTEMPTS.DELIVERY_ID, claim.deliveryId)
                    .set(NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER, attemptNumber)
                    .set(NOTIFICATION_ATTEMPTS.STARTED_AT, timestamp)
                    .set(NOTIFICATION_ATTEMPTS.COMPLETED_AT, timestamp)
                    .set(NOTIFICATION_ATTEMPTS.DURATION_MS, 0L)
                    .set(NOTIFICATION_ATTEMPTS.OUTCOME, AdapterOutcome.UNKNOWN_FAILURE.name)
                    .set(NOTIFICATION_ATTEMPTS.CLASSIFICATION, AdapterClassification.INTERNAL_PROCESSING_FAILURE.name)
                    .set(NOTIFICATION_ATTEMPTS.CREATED_AT, timestamp)
                    .execute()
            } else {
                val startedAt = requireNotNull(incompleteAttempt.get(NOTIFICATION_ATTEMPTS.STARTED_AT)).toInstant()
                dsl.update(NOTIFICATION_ATTEMPTS)
                    .set(NOTIFICATION_ATTEMPTS.COMPLETED_AT, timestamp)
                    .set(NOTIFICATION_ATTEMPTS.DURATION_MS, Duration.between(startedAt, now).toMillis().coerceAtLeast(0))
                    .set(NOTIFICATION_ATTEMPTS.OUTCOME, AdapterOutcome.UNKNOWN_FAILURE.name)
                    .set(NOTIFICATION_ATTEMPTS.CLASSIFICATION, AdapterClassification.INTERNAL_PROCESSING_FAILURE.name)
                    .where(NOTIFICATION_ATTEMPTS.ID.eq(incompleteAttempt.get(NOTIFICATION_ATTEMPTS.ID)))
                    .execute()
            }
        }

        val updated = dsl.update(NOTIFICATION_DELIVERIES)
            .set(NOTIFICATION_DELIVERIES.STATUS, status.name)
            .set(NOTIFICATION_DELIVERIES.REASON, NotificationReason.PROCESSING_FAILURE.name)
            .set(
                NOTIFICATION_DELIVERIES.ATTEMPT_COUNT,
                if (preserveAttempt) requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ATTEMPT_COUNT)) else attemptNumber,
            )
            .set(NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT, if (canRetry) retryAt.atOffset(ZoneOffset.UTC) else null)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_OWNER)
            .setNull(NOTIFICATION_DELIVERIES.CLAIMED_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_TOKEN)
            .set(
                NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT,
                if (canRetry) null else now.plus(contentRetention).atOffset(ZoneOffset.UTC),
            )
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, timestamp)
            .where(
                NOTIFICATION_DELIVERIES.ID.eq(claim.deliveryId)
                    .and(NOTIFICATION_DELIVERIES.STATUS.eq(NotificationDeliveryStatus.CLAIMED.name))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_OWNER.eq(claim.owner))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_TOKEN.eq(claim.token)),
            )
            .execute()
        check(updated == 1) { "Notification claim ${claim.deliveryId} became stale while recording a processing failure" }

        return NotificationProcessingFailureResult(
            intentId = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.INTENT_ID)),
            providerRequestId = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.PROVIDER_REQUEST_ID)),
            adapterKey = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ADAPTER_KEY)),
            channel = NotificationChannel.valueOf(requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.CHANNEL))),
            attemptNumber = attemptNumber,
            status = status,
        )
    }
}
