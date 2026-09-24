package com.gyro.api.subscription.application.job

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.subscription.infrastructure.StalePaymentAttemptRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class StalePaymentAttemptCleanupJob(
    private val attempts: StalePaymentAttemptRepository,
    private val time: TimeProvider,
    private val metrics: LifecycleObservability,
    @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean,
    @Value("\${app.billing.lifecycle.batch-size:100}") private val batchSize: Int,
    @Value("\${app.billing.lifecycle.stale-attempt-age:60m}") private val staleAge: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.billing.lifecycle.stale-attempt-delay:900000}")
    fun run() {
        if (!enabled) return
        try {
            val now = time.now()
            val staleAttemptIds = attempts.markStaleBefore(now.minus(staleAge), now, batchSize)
            metrics.batchSize("stale_attempt_cleanup", staleAttemptIds.size)
            metrics.jobRun("stale_attempt_cleanup", "success")
            log.info("event=stale_payment_attempt_cleanup outcome=success count={}", staleAttemptIds.size)
        } catch (exception: RuntimeException) {
            metrics.jobRun("stale_attempt_cleanup", "failure")
            log.warn(
                "event=stale_payment_attempt_cleanup outcome=failure reason={}",
                exception::class.simpleName,
            )
        }
    }
}
