package com.gyro.api.subscription.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class AdminLifecycleReadService(private val jdbc: JdbcTemplate) {
    fun snapshot(): AdminLifecycleSnapshot = AdminLifecycleSnapshot(
        subscriptionsByStatus = rows("select status, count(*) from user_subscriptions group by status"),
        outboxByStatus = rows("select status, count(*) from outbox_events group by status"),
        overdueExpiry = count("select count(*) from user_subscriptions where status in ('ACTIVE', 'CANCELED') and period_end < now()"),
        overdueGraceExit = count("select count(*) from user_subscriptions where status = 'GRACE_PERIOD' and grace_period_end < now()"),
        oldestPendingOutboxAgeSeconds = count("select coalesce(extract(epoch from now() - min(created_at)), 0)::bigint from outbox_events where status = 'PENDING'"),
        staleAttemptsLast24h = count("select count(*) from payment_attempts where status = 'STALE' and updated_at >= now() - interval '24 hours'"),
        remindersLast24h = count("select count(*) from subscription_renewal_reminders where reminded_at >= now() - interval '24 hours'"),
        failedEvents = jdbc.query("select id, event_type, retry_count, left(coalesce(last_error, ''), 200), created_at from outbox_events where status = 'FAILED' order by created_at asc limit 50") { rs, _ ->
            FailedOutboxEvent(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant())
        },
    )
    private fun count(sql: String) = jdbc.queryForObject(sql, Long::class.java) ?: 0L
    private fun rows(sql: String) = jdbc.query(sql) { rs, _ -> rs.getString(1) to rs.getLong(2) }.toMap()
}
data class AdminLifecycleSnapshot(val subscriptionsByStatus: Map<String, Long>, val outboxByStatus: Map<String, Long>, val overdueExpiry: Long, val overdueGraceExit: Long, val oldestPendingOutboxAgeSeconds: Long, val staleAttemptsLast24h: Long, val remindersLast24h: Long, val failedEvents: List<FailedOutboxEvent>)
data class FailedOutboxEvent(val id: Long, val eventType: String, val retryCount: Int, val lastError: String, val createdAt: java.time.Instant)
