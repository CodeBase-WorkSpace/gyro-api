package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.SubscriptionRenewalReminder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface SubscriptionRenewalReminderRepository : JpaRepository<SubscriptionRenewalReminder, Long> {
    fun countByRemindedAtAfter(threshold: Instant): Long

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            insert into subscription_renewal_reminders (subscription_id, period_end, reminded_at)
            values (:subscriptionId, :periodEnd, :remindedAt)
            on conflict (subscription_id, period_end) do nothing
        """,
    )
    fun insertIfAbsent(subscriptionId: Long, periodEnd: Instant, remindedAt: Instant): Int
}
