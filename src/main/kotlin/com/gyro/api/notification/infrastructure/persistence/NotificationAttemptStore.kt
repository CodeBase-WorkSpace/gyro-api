package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_ATTEMPTS
import com.gyro.api.jooq.Tables.NOTIFICATION_DELIVERIES
import com.gyro.api.jooq.Tables.NOTIFICATION_INTENTS
import com.gyro.api.notification.domain.*
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class NotificationAttemptContext(
    val attemptId: UUID,
    val attemptNumber: Int,
    val startedAt: Instant,
    val resumed: Boolean,
    val deliveryId: UUID,
    val intentId: UUID,
    val type: NotificationType,
    val channel: NotificationChannel,
    val endpointReference: String,
    val providerRequestId: String,
    val adapterKey: String,
    val subject: String?,
    val plainBody: String,
    val htmlBody: String?,
    val expiresAt: Instant,
)

data class NotificationFinalization(
    val status: NotificationDeliveryStatus,
    val reason: NotificationReason?,
    val nextAttemptAt: Instant?,
)

@Repository
class NotificationAttemptStore(
    private val dsl: DSLContext,
) {
    fun begin(claim: NotificationDeliveryClaim, startedAt: Instant): NotificationAttemptContext? {
        val now = startedAt.atOffset(ZoneOffset.UTC)
        val delivery = dsl.select(
            NOTIFICATION_DELIVERIES.ID,
            NOTIFICATION_DELIVERIES.INTENT_ID,
            NOTIFICATION_DELIVERIES.CHANNEL,
            NOTIFICATION_DELIVERIES.ENDPOINT_REFERENCE,
            NOTIFICATION_DELIVERIES.PROVIDER_REQUEST_ID,
            NOTIFICATION_DELIVERIES.ADAPTER_KEY,
            NOTIFICATION_DELIVERIES.RENDERED_SUBJECT,
            NOTIFICATION_DELIVERIES.RENDERED_PLAIN_BODY,
            NOTIFICATION_DELIVERIES.RENDERED_HTML_BODY,
            NOTIFICATION_DELIVERIES.EXPIRES_AT,
            NOTIFICATION_DELIVERIES.ATTEMPT_COUNT,
        )
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.ID.eq(claim.deliveryId)
                    .and(NOTIFICATION_DELIVERIES.STATUS.eq(NotificationDeliveryStatus.CLAIMED.name))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_OWNER.eq(claim.owner))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_TOKEN.eq(claim.token))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT.gt(now)),
            )
            .forUpdate()
            .fetchOne()
            ?: return null

        val intentId = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.INTENT_ID))
        val type = dsl.select(NOTIFICATION_INTENTS.NOTIFICATION_TYPE)
            .from(NOTIFICATION_INTENTS)
            .where(NOTIFICATION_INTENTS.ID.eq(intentId))
            .fetchOne(NOTIFICATION_INTENTS.NOTIFICATION_TYPE)
            ?.let(NotificationType::valueOf)
            ?: error("Notification intent $intentId does not exist")

        val incomplete = dsl.select(
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

        val attemptId = incomplete?.get(NOTIFICATION_ATTEMPTS.ID) ?: UUID.randomUUID()
        val attemptNumber = incomplete?.get(NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER)
            ?: requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ATTEMPT_COUNT)) + 1
        val persistedStartedAt = incomplete?.get(NOTIFICATION_ATTEMPTS.STARTED_AT)?.toInstant() ?: startedAt
        if (incomplete == null) {
            dsl.insertInto(NOTIFICATION_ATTEMPTS)
                .set(NOTIFICATION_ATTEMPTS.ID, attemptId)
                .set(NOTIFICATION_ATTEMPTS.DELIVERY_ID, claim.deliveryId)
                .set(NOTIFICATION_ATTEMPTS.ATTEMPT_NUMBER, attemptNumber)
                .set(NOTIFICATION_ATTEMPTS.STARTED_AT, now)
                .set(NOTIFICATION_ATTEMPTS.CREATED_AT, now)
                .execute()
        }

        return NotificationAttemptContext(
            attemptId = attemptId,
            attemptNumber = attemptNumber,
            startedAt = persistedStartedAt,
            resumed = incomplete != null,
            deliveryId = claim.deliveryId,
            intentId = intentId,
            type = type,
            channel = NotificationChannel.valueOf(requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.CHANNEL))),
            endpointReference = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ENDPOINT_REFERENCE)),
            providerRequestId = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.PROVIDER_REQUEST_ID)),
            adapterKey = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.ADAPTER_KEY)),
            subject = delivery.get(NOTIFICATION_DELIVERIES.RENDERED_SUBJECT),
            plainBody = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.RENDERED_PLAIN_BODY)) {
                "Claimed delivery ${claim.deliveryId} has already had its content purged"
            },
            htmlBody = delivery.get(NOTIFICATION_DELIVERIES.RENDERED_HTML_BODY),
            expiresAt = requireNotNull(delivery.get(NOTIFICATION_DELIVERIES.EXPIRES_AT)).toInstant(),
        )
    }

    fun finalize(
        claim: NotificationDeliveryClaim,
        context: NotificationAttemptContext,
        result: AdapterResult,
        finalization: NotificationFinalization,
        completedAt: Instant,
        contentPurgeAt: Instant?,
    ): Boolean {
        val completed = completedAt.atOffset(ZoneOffset.UTC)
        val updated = dsl.update(NOTIFICATION_DELIVERIES)
            .set(NOTIFICATION_DELIVERIES.STATUS, finalization.status.name)
            .set(NOTIFICATION_DELIVERIES.REASON, finalization.reason?.name)
            .set(NOTIFICATION_DELIVERIES.ATTEMPT_COUNT, context.attemptNumber)
            .set(NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT, finalization.nextAttemptAt?.atOffset(ZoneOffset.UTC))
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_OWNER)
            .setNull(NOTIFICATION_DELIVERIES.CLAIMED_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_TOKEN)
            .set(NOTIFICATION_DELIVERIES.PROVIDER_REFERENCE_DIGEST, result.providerReferenceDigest)
            .set(NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT, contentPurgeAt?.atOffset(ZoneOffset.UTC))
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, completed)
            .where(
                NOTIFICATION_DELIVERIES.ID.eq(claim.deliveryId)
                    .and(NOTIFICATION_DELIVERIES.STATUS.eq(NotificationDeliveryStatus.CLAIMED.name))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_OWNER.eq(claim.owner))
                    .and(NOTIFICATION_DELIVERIES.CLAIM_TOKEN.eq(claim.token)),
            )
            .execute()
        if (updated == 0) return false

        val attemptUpdated = dsl.update(NOTIFICATION_ATTEMPTS)
            .set(NOTIFICATION_ATTEMPTS.COMPLETED_AT, completed)
            .set(
                NOTIFICATION_ATTEMPTS.DURATION_MS,
                Duration.between(context.startedAt, completedAt).toMillis().coerceAtLeast(0),
            )
            .set(NOTIFICATION_ATTEMPTS.OUTCOME, result.outcome.name)
            .set(NOTIFICATION_ATTEMPTS.CLASSIFICATION, result.classification.name)
            .set(NOTIFICATION_ATTEMPTS.PROVIDER_REFERENCE_DIGEST, result.providerReferenceDigest)
            .where(
                NOTIFICATION_ATTEMPTS.ID.eq(context.attemptId)
                    .and(NOTIFICATION_ATTEMPTS.COMPLETED_AT.isNull),
            )
            .execute()
        check(attemptUpdated == 1) { "Notification attempt ${context.attemptId} was already finalized" }
        return true
    }
}
