package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.notification.domain.NotificationDeliveryClaim
import com.gyro.api.jooq.Tables.NOTIFICATION_DELIVERIES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

data class NotificationQueueSnapshot(
    val dueCount: Long,
    val oldestDueAt: Instant?,
    val expiredActiveCount: Long,
)

@Repository
class NotificationDeliveryQueueRepository(
    private val dsl: DSLContext,
) {
    private val effectiveDueAt = DSL.coalesce(
        NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT,
        NOTIFICATION_DELIVERIES.DUE_AT,
    )

    fun recoverExpiredClaims(now: Instant): Int {
        val timestamp = now.toOffsetDateTime()
        return dsl.update(NOTIFICATION_DELIVERIES)
            .set(NOTIFICATION_DELIVERIES.STATUS, "PENDING")
            .setNull(NOTIFICATION_DELIVERIES.REASON)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_OWNER)
            .setNull(NOTIFICATION_DELIVERIES.CLAIMED_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_TOKEN)
            .set(NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT, effectiveDueAt)
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, timestamp)
            .where(
                NOTIFICATION_DELIVERIES.STATUS.eq("CLAIMED")
                    .and(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT.le(timestamp))
                    .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.gt(timestamp)),
            )
            .execute()
    }

    fun findExpiredIntentIds(now: Instant): Set<UUID> {
        val timestamp = now.toOffsetDateTime()
        return dsl.selectDistinct(NOTIFICATION_DELIVERIES.INTENT_ID)
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.STATUS.`in`(ACTIVE_STATUSES)
                    .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.le(timestamp)),
            )
            .fetchSet(NOTIFICATION_DELIVERIES.INTENT_ID)
    }

    fun expireDueDeliveries(now: Instant, contentPurgeAt: Instant): Int {
        val timestamp = now.toOffsetDateTime()
        return dsl.update(NOTIFICATION_DELIVERIES)
            .set(NOTIFICATION_DELIVERIES.STATUS, "EXPIRED")
            .set(NOTIFICATION_DELIVERIES.REASON, "MISSED_ALLOWED_WINDOW")
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_OWNER)
            .setNull(NOTIFICATION_DELIVERIES.CLAIMED_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT)
            .setNull(NOTIFICATION_DELIVERIES.CLAIM_TOKEN)
            .setNull(NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT)
            .set(NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT, contentPurgeAt.toOffsetDateTime())
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, timestamp)
            .where(
                NOTIFICATION_DELIVERIES.STATUS.`in`(ACTIVE_STATUSES)
                    .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.le(timestamp)),
            )
            .execute()
    }

    fun claimDueDeliveries(
        now: Instant,
        batchSize: Int,
        owner: String,
        claimExpiresAt: Instant,
        channels: Set<String>,
    ): List<NotificationDeliveryClaim> {
        val timestamp = now.toOffsetDateTime()
        val claimToken = UUID.randomUUID()
        val candidates = DSL.name("notification_claim_candidates")
        val candidateTable = DSL.table(candidates)
        val candidateId = DSL.field(DSL.name("notification_claim_candidates", "id"), UUID::class.java)
        val candidateIds = dsl.select(NOTIFICATION_DELIVERIES.ID)
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.STATUS.`in`(CLAIMABLE_STATUSES)
                    .and(NOTIFICATION_DELIVERIES.CHANNEL.`in`(channels))
                    .and(effectiveDueAt.le(timestamp))
                    .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.gt(timestamp)),
            )
            .orderBy(effectiveDueAt, NOTIFICATION_DELIVERIES.CREATED_AT, NOTIFICATION_DELIVERIES.ID)
            .limit(batchSize)
            .forUpdate()
            .skipLocked()

        // Returning rows are sorted in memory because PostgreSQL does not guarantee UPDATE RETURNING order.
        // Selection and claiming still happen atomically in this single statement.
        return dsl.with(candidates)
            .asMaterialized(candidateIds)
            .update(NOTIFICATION_DELIVERIES)
            .set(NOTIFICATION_DELIVERIES.STATUS, "CLAIMED")
            .set(NOTIFICATION_DELIVERIES.CLAIM_OWNER, owner)
            .set(NOTIFICATION_DELIVERIES.CLAIMED_AT, timestamp)
            .set(NOTIFICATION_DELIVERIES.CLAIM_EXPIRES_AT, claimExpiresAt.toOffsetDateTime())
            .set(NOTIFICATION_DELIVERIES.CLAIM_TOKEN, claimToken)
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, timestamp)
            .from(candidateTable)
            .where(
                NOTIFICATION_DELIVERIES.ID.eq(candidateId)
                    .and(NOTIFICATION_DELIVERIES.STATUS.`in`(CLAIMABLE_STATUSES)),
            )
            .returning(
                NOTIFICATION_DELIVERIES.ID,
                NOTIFICATION_DELIVERIES.CLAIM_TOKEN,
                NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT,
                NOTIFICATION_DELIVERIES.DUE_AT,
                NOTIFICATION_DELIVERIES.CREATED_AT,
            )
            .fetch()
            .sortedWith(
                compareBy(
                    { record ->
                        record.get(NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT)
                            ?: record.get(NOTIFICATION_DELIVERIES.DUE_AT)
                    },
                    { record -> record.get(NOTIFICATION_DELIVERIES.CREATED_AT) },
                    { record -> record.get(NOTIFICATION_DELIVERIES.ID) },
                ),
            )
            .map { record ->
                NotificationDeliveryClaim(
                    deliveryId = requireNotNull(record.get(NOTIFICATION_DELIVERIES.ID)),
                    token = requireNotNull(record.get(NOTIFICATION_DELIVERIES.CLAIM_TOKEN)),
                    owner = owner,
                    expiresAt = claimExpiresAt,
                )
            }
    }

    fun snapshot(now: Instant): NotificationQueueSnapshot {
        val timestamp = now.toOffsetDateTime()
        val dueCondition = NOTIFICATION_DELIVERIES.STATUS.`in`(CLAIMABLE_STATUSES)
            .and(effectiveDueAt.le(timestamp))
            .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.gt(timestamp))
        val record = dsl.select(DSL.count(), DSL.min(effectiveDueAt))
            .from(NOTIFICATION_DELIVERIES)
            .where(dueCondition)
            .fetchOne()

        val expiredActiveCount = dsl.selectCount()
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.STATUS.`in`(ACTIVE_STATUSES)
                    .and(NOTIFICATION_DELIVERIES.EXPIRES_AT.le(timestamp)),
            )
            .fetchOne(0, Long::class.java) ?: 0

        return NotificationQueueSnapshot(
            dueCount = record?.get(DSL.count())?.toLong() ?: 0,
            oldestDueAt = record?.get(DSL.min(effectiveDueAt))?.toInstant(),
            expiredActiveCount = expiredActiveCount,
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        val ACTIVE_STATUSES = listOf("PENDING", "RETRY_SCHEDULED", "CLAIMED")
        val CLAIMABLE_STATUSES = listOf("PENDING", "RETRY_SCHEDULED")
    }
}
