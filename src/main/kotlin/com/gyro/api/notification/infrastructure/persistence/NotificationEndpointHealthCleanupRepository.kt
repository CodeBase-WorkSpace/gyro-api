package com.gyro.api.notification.infrastructure.persistence

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class NotificationEndpointHealthCleanupRepository(
    private val dsl: DSLContext,
) {
    fun clearExpiredDiagnostics(now: Instant, batchSize: Int): Int {
        val timestamp = now.atOffset(ZoneOffset.UTC)
        val endpoints = DSL.table(DSL.name("notification_endpoint_health"))
        val id = DSL.field(DSL.name("notification_endpoint_health", "id"), UUID::class.java)
        val diagnosticDeleteAt = DSL.field(DSL.name("notification_endpoint_health", "diagnostic_delete_at"), java.time.OffsetDateTime::class.java)
        val invalidReason = DSL.field(DSL.name("notification_endpoint_health", "invalid_reason"), String::class.java)
        val lastFailureAt = DSL.field(DSL.name("notification_endpoint_health", "last_failure_at"), java.time.OffsetDateTime::class.java)
        val updatedAt = DSL.field(DSL.name("notification_endpoint_health", "updated_at"), java.time.OffsetDateTime::class.java)
        val candidates = DSL.name("notification_endpoint_health_cleanup_candidates")
        val candidateTable = DSL.table(candidates)
        val candidateId = DSL.field(DSL.name("notification_endpoint_health_cleanup_candidates", "id"), UUID::class.java)
        val candidateIds = dsl.select(id)
            .from(endpoints)
            .where(diagnosticDeleteAt.le(timestamp))
            .orderBy(diagnosticDeleteAt, id)
            .limit(batchSize)
            .forUpdate()
            .skipLocked()

        return dsl.with(candidates)
            .asMaterialized(candidateIds)
            .update(endpoints)
            .setNull(invalidReason)
            .setNull(lastFailureAt)
            .setNull(diagnosticDeleteAt)
            .set(updatedAt, timestamp)
            .from(candidateTable)
            .where(id.eq(candidateId))
            .execute()
    }
}
