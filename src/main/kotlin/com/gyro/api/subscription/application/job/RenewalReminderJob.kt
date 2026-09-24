package com.gyro.api.subscription.application.job

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.subscription.application.RenewalReminderRecordResult
import com.gyro.api.subscription.application.RenewalReminderService
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RenewalReminderJob(private val subscriptions: UserSubscriptionRepository, private val reminders: RenewalReminderService, private val time: TimeProvider, private val metrics: LifecycleObservability, @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean, @Value("\${app.billing.lifecycle.batch-size:100}") private val batchSize: Int, @Value("\${app.billing.lifecycle.renewal-reminder-days:3}") private val days: Long) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(cron = "\${app.billing.lifecycle.renewal-reminder-cron:0 30 6 * * *}")
    fun run() {
        if (!enabled) return; try {
            val batch = subscriptions.findRenewalReminderCandidates(
                time.now(),
                time.now().plus(Duration.ofDays(days)),
                PageRequest.of(0, batchSize)
            );
            var failures = 0; batch.forEach { subscription ->
                try {
                    val result = reminders.record(subscription); metrics.jobItem(
                        "renewal_reminder",
                        if (result == RenewalReminderRecordResult.CREATED) "success" else "duplicate"
                    )
                } catch (e: RuntimeException) {
                    failures += 1; metrics.jobItem(
                        "renewal_reminder",
                        "failure"
                    ); log.warn(
                        "event=renewal_reminder outcome=failure subscriptionId={} reason={}",
                        subscription.id,
                        e::class.simpleName
                    )
                }
            }; metrics.batchSize("renewal_reminder", batch.size); metrics.jobRun(
                "renewal_reminder",
                if (failures == 0) "success" else "partial_failure"
            )
        } catch (e: RuntimeException) {
            metrics.jobRun("renewal_reminder", "failure"); log.warn(
                "event=renewal_reminder outcome=failure reason={}",
                e::class.simpleName
            )
        }
    }
}
