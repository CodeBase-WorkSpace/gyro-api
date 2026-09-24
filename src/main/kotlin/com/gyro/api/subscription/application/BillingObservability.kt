package com.gyro.api.subscription.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
class BillingObservability(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pendingAttempts = AtomicLong()
    private val createFailures = AtomicLong()
    private val verificationFailures = AtomicLong()
    private val oldestPendingAgeSeconds = AtomicLong()

    @PostConstruct
    fun registerMetrics() {
        meterRegistryProvider.ifAvailable { registry ->
            registerAttemptGauge(registry, "pending", pendingAttempts)
            registerAttemptGauge(registry, "create_failed", createFailures)
            registerAttemptGauge(registry, "verification_failed", verificationFailures)
            Gauge.builder("gyro.billing.payping.verify_pending.oldest_age_seconds", oldestPendingAgeSeconds) { it.get().toDouble() }
                .description("Age in seconds of the oldest PayPing attempt awaiting verification")
                .register(registry)
        }
        refresh()
    }

    @Scheduled(fixedDelayString = "\${app.billing.observability-refresh-delay:60000}")
    fun refresh() {
        try {
            pendingAttempts.set(count("PENDING", "VERIFY_PENDING"))
            createFailures.set(count("CREATE_FAILED"))
            verificationFailures.set(count("FAILED"))
            oldestPendingAgeSeconds.set(oldestPendingAge())
        } catch (exception: RuntimeException) {
            meterRegistryProvider.ifAvailable { registry ->
                Counter.builder("gyro.billing.metrics.refresh.failures")
                    .description("Number of failed database refreshes for billing operational gauges")
                    .register(registry)
                    .increment()
            }
            log.warn("event=billing_metrics_refresh outcome=failure reason={}", exception::class.simpleName)
        }
    }

    private fun registerAttemptGauge(registry: MeterRegistry, statusGroup: String, value: AtomicLong) {
        Gauge.builder("gyro.billing.payment.attempts", value) { it.get().toDouble() }
            .tag("status_group", statusGroup)
            .description("Current number of billing payment attempts by operational status group")
            .register(registry)
    }

    private fun count(vararg statuses: String): Long {
        val placeholders = statuses.joinToString(",") { "?" }
        return jdbcTemplate.queryForObject(
            "select count(*) from payment_attempts where status in ($placeholders)",
            Long::class.java,
            *statuses,
        ) ?: 0L
    }

    private fun oldestPendingAge(): Long {
        val oldest = jdbcTemplate.queryForObject(
            "select min(updated_at) from payment_attempts where status = 'VERIFY_PENDING'",
            Timestamp::class.java,
        )?.toInstant() ?: return 0L
        return Duration.between(oldest, clock.instant()).seconds.coerceAtLeast(0)
    }
}
