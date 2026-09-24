package com.gyro.api.notification.application.delivery

import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class NotificationDeliveryWorker(
    private val claims: NotificationDeliveryClaimService,
    private val attempts: NotificationAttemptService,
    private val outcomes: NotificationOutcomeService,
    private val eligibility: NotificationEligibilityPreflightService,
    private val failures: NotificationProcessingFailureHandler,
    private val properties: NotificationProperties,
) {
    fun processBatch(): Int {
        claims.recoverAndExpireDue().forEach(outcomes::recompute)
        var processed = 0
        repeat(properties.batchSize) {
            val claim = claims.claimNext() ?: return processed
            try {
                val preflight = eligibility.evaluate(claim)
                if (preflight.outcome == NotificationPreflightOutcome.PROCEED) attempts.attempt(claim)
                else outcomes.recompute(preflight.intentId)
            } catch (_: StaleNotificationClaimException) {
                // A newer lease owns the row; its worker is responsible for the outcome.
            } catch (exception: Exception) {
                failures.handle(claim, exception)
            }
            processed++
        }
        return processed
    }
}

@Component
class NotificationDeliveryScheduler(
    private val worker: NotificationDeliveryWorker,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
) {
    @Scheduled(fixedDelayString = "\${app.notification.poll-delay:5s}")
    fun run() {
        if (!properties.jobsEnabled) return
        val startedAt = System.nanoTime()
        var processed = 0
        runCatching { worker.processBatch().also { processed = it } }
            .onFailure { exception ->
                log.warn(
                    "event=notification_worker outcome=failure reason={} exception={}",
                    exception::class.simpleName,
                    exception.sanitizedForLogging(),
                )
            }
        metrics.deliveryBatchCompleted(processed, Duration.ofNanos(System.nanoTime() - startedAt))
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationDeliveryScheduler::class.java)
    }
}
