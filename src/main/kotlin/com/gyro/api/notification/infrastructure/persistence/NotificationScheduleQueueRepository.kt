package com.gyro.api.notification.infrastructure.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class NotificationScheduleClaim(val scheduleId: UUID, val owner: String, val token: UUID)

@Repository
class NotificationScheduleQueueRepository(private val dsl: DSLContext) {
    private val schedules = DSL.table(DSL.name("notification_schedules"))
    private val id = DSL.field(DSL.name("notification_schedules", "id"), UUID::class.java)
    private val enabled = DSL.field(DSL.name("notification_schedules", "enabled"), Boolean::class.java)
    private val nextEvaluationAt = DSL.field(DSL.name("notification_schedules", "next_evaluation_at"), OffsetDateTime::class.java)
    private val claimOwner = DSL.field(DSL.name("notification_schedules", "claim_owner"), String::class.java)
    private val claimedAt = DSL.field(DSL.name("notification_schedules", "claimed_at"), OffsetDateTime::class.java)
    private val claimExpiresAt = DSL.field(DSL.name("notification_schedules", "claim_expires_at"), OffsetDateTime::class.java)
    private val claimToken = DSL.field(DSL.name("notification_schedules", "claim_token"), UUID::class.java)
    private val updatedAt = DSL.field(DSL.name("notification_schedules", "updated_at"), OffsetDateTime::class.java)

    fun recoverExpiredClaims(now: Instant): Int = dsl.update(schedules)
        .setNull(claimOwner).setNull(claimedAt).setNull(claimExpiresAt).setNull(claimToken).set(updatedAt, now.utc())
        .where(claimExpiresAt.le(now.utc())).execute()

    fun claimDue(now: Instant, owner: String, batchSize: Int, leaseUntil: Instant): List<NotificationScheduleClaim> {
        val candidates = DSL.name("notification_schedule_candidates")
        val candidateTable = DSL.table(candidates)
        val candidateId = DSL.field(DSL.name("notification_schedule_candidates", "id"), UUID::class.java)
        val candidatesQuery = dsl.select(id).from(schedules)
            .where(enabled.eq(true).and(nextEvaluationAt.le(now.utc())).and(claimToken.isNull))
            .orderBy(nextEvaluationAt, id).limit(batchSize).forUpdate().skipLocked()
        return dsl.with(candidates).asMaterialized(candidatesQuery).update(schedules)
            .set(claimOwner, owner).set(claimedAt, now.utc()).set(claimExpiresAt, leaseUntil.utc()).set(claimToken, UUID.randomUUID()).set(updatedAt, now.utc())
            .from(candidateTable).where(id.eq(candidateId).and(claimToken.isNull)).returning(id, claimToken).fetch()
            .map { NotificationScheduleClaim(requireNotNull(it.get(id)), owner, requireNotNull(it.get(claimToken))) }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
