package com.gyro.api.subscription.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

@Component
class LifecycleObservability(private val registries: ObjectProvider<MeterRegistry>, private val jdbc: JdbcTemplate, private val clock: Clock) {
    private val pendingOutbox = AtomicLong()
    private val failedOutbox = AtomicLong()
    private val oldestPendingAge = AtomicLong()
    private val staleAttempts = AtomicLong()
    @PostConstruct fun registerGauges() {
        registries.ifAvailable { registry ->
            Gauge.builder("gyro.billing.outbox.backlog", pendingOutbox) { it.get().toDouble() }.tag("status", "PENDING").register(registry)
            Gauge.builder("gyro.billing.outbox.backlog", failedOutbox) { it.get().toDouble() }.tag("status", "FAILED").register(registry)
            Gauge.builder("gyro.billing.outbox.oldest_pending_age_seconds", oldestPendingAge) { it.get().toDouble() }.register(registry)
            Gauge.builder("gyro.billing.attempts.stale_total_24h", staleAttempts) { it.get().toDouble() }.register(registry)
        }
        refresh()
    }
    @Scheduled(fixedDelayString = "\${app.billing.observability-refresh-delay:60000}") fun refresh() = runCatching {
        pendingOutbox.set(count("select count(*) from outbox_events where status = 'PENDING'"))
        failedOutbox.set(count("select count(*) from outbox_events where status = 'FAILED'"))
        staleAttempts.set(count("select count(*) from payment_attempts where status = 'STALE' and updated_at >= now() - interval '24 hours'"))
        val oldest = jdbc.queryForObject("select extract(epoch from now() - min(created_at)) from outbox_events where status = 'PENDING'", java.lang.Double::class.java)
        oldestPendingAge.set((oldest ?: 0.0).toLong().coerceAtLeast(0))
    }
    private fun count(sql: String) = jdbc.queryForObject(sql, Long::class.java) ?: 0L
    fun jobRun(job: String, outcome: String) = registries.ifAvailable { registry ->
        Counter.builder("gyro.billing.jobs.runs").tag("job", job).tag("outcome", outcome).register(registry).increment()
    }
    fun batchSize(job: String, size: Int) = registries.ifAvailable { registry ->
        DistributionSummary.builder("gyro.billing.jobs.batch_size").tag("job", job).register(registry).record(size.toDouble())
    }
    fun jobItem(job: String, outcome: String) = registries.ifAvailable { registry ->
        Counter.builder("gyro.billing.jobs.items").tag("job", job).tag("outcome", outcome).register(registry)
            .increment()
    }
    fun transition(transition: String, outcome: String) = registries.ifAvailable { registry ->
        Counter.builder("gyro.billing.lifecycle.transitions").tag("transition", transition).tag("outcome", outcome).register(registry).increment()
    }
}
