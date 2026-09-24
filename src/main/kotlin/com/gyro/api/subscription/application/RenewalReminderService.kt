package com.gyro.api.subscription.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.outbox.OutboxEventWriter
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import com.gyro.api.subscription.domain.UserSubscription
import com.gyro.api.subscription.infrastructure.SubscriptionRenewalReminderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RenewalReminderService(
    private val reminders: SubscriptionRenewalReminderRepository,
    private val outboxWriter: OutboxEventWriter,
    private val timeProvider: TimeProvider,
) {
    /** Records one reminder per subscription period; delivery is intentionally deferred to an outbox consumer. */
    @Transactional
    fun record(subscription: UserSubscription): RenewalReminderRecordResult {
        val periodEnd = requireNotNull(subscription.periodEnd)
        val now = timeProvider.now()
        val inserted = reminders.insertIfAbsent(
            subscriptionId = requireNotNull(subscription.id),
            periodEnd = periodEnd,
            remindedAt = now,
        )
        if (inserted == 0) return RenewalReminderRecordResult.DUPLICATE

        outboxWriter.writeSubscriptionEvent(
            aggregateId = subscription.id.toString(),
            payload = SubscriptionEventPayload(
                subscription.userId,
                "RENEWAL_REMINDER",
                subscription.planId,
                subscription.periodStart,
                periodEnd,
                now
            ),
        )
        return RenewalReminderRecordResult.CREATED
    }
}

enum class RenewalReminderRecordResult {
    CREATED,
    DUPLICATE,
}
