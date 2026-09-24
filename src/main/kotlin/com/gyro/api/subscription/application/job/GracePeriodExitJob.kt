package com.gyro.api.subscription.application.job

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class GracePeriodExitJob(private val subscriptions: UserSubscriptionRepository, private val lifecycle: SubscriptionLifecycleService, private val time: TimeProvider, private val metrics: LifecycleObservability, @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean, @Value("\${app.billing.lifecycle.batch-size:100}") private val batchSize: Int) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${app.billing.lifecycle.grace-exit-delay:300000}")
    fun run() {
        if (!enabled) return; try {
            val ids = subscriptions.findGraceExitCandidates(time.now(), PageRequest.of(0, batchSize)).map { it.id!! };
            var failures = 0; ids.forEach { id ->
                runCatching { lifecycle.processGraceExit(id) }.onSuccess {
                    metrics.jobItem(
                        "grace_exit",
                        "success"
                    )
                }.onFailure {
                    failures += 1; metrics.jobItem(
                    "grace_exit",
                    "failure"
                ); log.warn("event=grace_exit outcome=failure subscriptionId={} reason={}", id, it::class.simpleName)
                }
            }; metrics.batchSize("grace_exit", ids.size); metrics.jobRun(
                "grace_exit",
                if (failures == 0) "success" else "partial_failure"
            )
        } catch (e: RuntimeException) {
            metrics.jobRun("grace_exit", "failure"); log.warn(
                "event=grace_exit outcome=failure reason={}",
                e::class.simpleName
            )
        }
    }
}
