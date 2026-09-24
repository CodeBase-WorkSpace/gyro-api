package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.UserSubscription
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class SubscriptionSummaryService(private val subscriptions: UserSubscriptionRepository, private val plans: SubscriptionPlanRepository) {
    fun current(userId: UUID): SubscriptionSummary = subscriptions.findByUserId(userId).orElse(null)?.let(::summary)
        ?: SubscriptionSummary(status = "NONE", nextAction = "RENEW")
    fun summary(subscription: UserSubscription): SubscriptionSummary = SubscriptionSummary(
        status = subscription.status.name,
        planCode = plans.findById(subscription.planId).orElse(null)?.code,
        periodStart = subscription.periodStart,
        periodEnd = subscription.periodEnd,
        cancelAtPeriodEnd = subscription.cancelAtPeriodEnd,
        gracePeriodEnd = subscription.gracePeriodEnd,
        graceReason = subscription.graceReason?.takeIf { it == "RENEWAL_OVERDUE" },
        nextAction = when {
            subscription.status.name == "GRACE_PERIOD" || subscription.status.name == "EXPIRED" -> "RENEW"
            subscription.cancelAtPeriodEnd -> "RESTORE"
            else -> "NONE"
        },
    )
}

data class SubscriptionSummary(
    val status: String,
    val planCode: String? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val gracePeriodEnd: Instant? = null,
    val graceReason: String? = null,
    val nextAction: String,
)
