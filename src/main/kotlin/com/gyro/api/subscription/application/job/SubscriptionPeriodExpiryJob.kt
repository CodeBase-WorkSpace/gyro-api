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
class SubscriptionPeriodExpiryJob(private val subscriptions: UserSubscriptionRepository, private val lifecycle: SubscriptionLifecycleService, private val time: TimeProvider, private val metrics: LifecycleObservability,
    @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean, @Value("\${app.billing.lifecycle.batch-size:100}") private val batchSize: Int) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${app.billing.lifecycle.period-expiry-delay:300000}")
    fun run() = process(
        name = "period_expiry",
        batch = { subscriptions.findPeriodExpiryCandidates(time.now(), PageRequest.of(0, batchSize)).map { it.id!! } },
        item = { lifecycle.processPeriodExpiry(it) },
    )
    private fun process(name: String, batch: () -> List<Long>, item: (Long) -> Unit) {
        if (!enabled) return; try {
            val ids = batch();
            var failures = 0; ids.forEach { id ->
                runCatching { item(id) }.onSuccess {
                    metrics.jobItem(
                        name,
                        "success"
                    )
                }.onFailure {
                    failures += 1; metrics.jobItem(
                    name,
                    "failure"
                ); log.warn("event={} outcome=failure subscriptionId={} reason={}", name, id, it::class.simpleName)
                }
            }; metrics.batchSize(name, ids.size); metrics.jobRun(
                name,
                if (failures == 0) "success" else "partial_failure"
            )
        } catch (e: RuntimeException) {
            metrics.jobRun(name, "failure"); log.warn("event={} outcome=failure reason={}", name, e::class.simpleName)
        }
    }
}
