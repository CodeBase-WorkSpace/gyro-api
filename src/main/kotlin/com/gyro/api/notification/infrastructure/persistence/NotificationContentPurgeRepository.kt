package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_DELIVERIES
import com.gyro.api.jooq.Tables.NOTIFICATION_INTENTS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class NotificationPurgeSnapshot(
    val overdueCount: Long,
    val oldestOverdueAt: Instant?,
)

@Repository
class NotificationContentPurgeRepository(
    private val dsl: DSLContext,
) {
    fun purgeDue(now: Instant, batchSize: Int): Int {
        val timestamp = now.atOffset(ZoneOffset.UTC)
        val candidates = DSL.name("notification_content_purge_candidates")
        val candidateTable = DSL.table(candidates)
        val candidateId = DSL.field(DSL.name("notification_content_purge_candidates", "id"), UUID::class.java)
        val candidateIds = dsl.select(NOTIFICATION_DELIVERIES.ID)
            .from(NOTIFICATION_DELIVERIES)
            .where(
                NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT.le(timestamp)
                    .and(NOTIFICATION_DELIVERIES.CONTENT_PURGED_AT.isNull),
            )
            .orderBy(NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT, NOTIFICATION_DELIVERIES.ID)
            .limit(batchSize)
            .forUpdate()
            .skipLocked()

        val purged = dsl.with(candidates)
            .asMaterialized(candidateIds)
            .update(NOTIFICATION_DELIVERIES)
            .setNull(NOTIFICATION_DELIVERIES.RENDERED_SUBJECT)
            .setNull(NOTIFICATION_DELIVERIES.RENDERED_PLAIN_BODY)
            .setNull(NOTIFICATION_DELIVERIES.RENDERED_HTML_BODY)
            .setNull(NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT)
            .set(NOTIFICATION_DELIVERIES.CONTENT_PURGED_AT, timestamp)
            .set(NOTIFICATION_DELIVERIES.UPDATED_AT, timestamp)
            .from(candidateTable)
            .where(NOTIFICATION_DELIVERIES.ID.eq(candidateId))
            .returning(NOTIFICATION_DELIVERIES.INTENT_ID)
            .fetch()

        val intentIds = purged.mapNotNull { it.get(NOTIFICATION_DELIVERIES.INTENT_ID) }.toSet()
        if (intentIds.isNotEmpty()) {
            val remainingContent = DSL.exists(
                DSL.selectOne()
                    .from(NOTIFICATION_DELIVERIES)
                    .where(
                        NOTIFICATION_DELIVERIES.INTENT_ID.eq(NOTIFICATION_INTENTS.ID)
                            .and(NOTIFICATION_DELIVERIES.CONTENT_PURGED_AT.isNull),
                    ),
            )
            dsl.update(NOTIFICATION_INTENTS)
                .setNull(NOTIFICATION_INTENTS.TEMPLATE_DATA)
                .set(NOTIFICATION_INTENTS.UPDATED_AT, timestamp)
                .where(NOTIFICATION_INTENTS.ID.`in`(intentIds).andNot(remainingContent))
                .execute()
        }
        return purged.size
    }

    fun snapshot(now: Instant): NotificationPurgeSnapshot {
        val overdue = NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT.le(now.atOffset(ZoneOffset.UTC))
            .and(NOTIFICATION_DELIVERIES.CONTENT_PURGED_AT.isNull)
        val count = DSL.count()
        val oldest = DSL.min(NOTIFICATION_DELIVERIES.CONTENT_PURGE_AT)
        val record = dsl.select(count, oldest)
            .from(NOTIFICATION_DELIVERIES)
            .where(overdue)
            .fetchOne()
        return NotificationPurgeSnapshot(
            overdueCount = record?.get(count)?.toLong() ?: 0,
            oldestOverdueAt = record?.get(oldest)?.toInstant(),
        )
    }
}
