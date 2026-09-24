package com.gyro.api.subscription.application.job

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.subscription.application.OutboxCleanupService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class OutboxCleanupJob(private val cleanupService: OutboxCleanupService, private val time: TimeProvider, private val metrics: LifecycleObservability, @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean, @Value("\${app.billing.lifecycle.outbox-retention:7d}") private val retention: Duration) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(cron = "\${app.billing.lifecycle.outbox-cleanup-cron:0 45 4 * * *}") fun run() { if (!enabled) return; try { val deleted = cleanupService.cleanup(time.now().minus(retention)); metrics.jobRun("outbox_cleanup", "success"); log.info("event=outbox_cleanup outcome=success count={}", deleted) } catch (e: RuntimeException) { metrics.jobRun("outbox_cleanup", "failure"); log.warn("event=outbox_cleanup outcome=failure reason={}", e::class.simpleName) } }
}
