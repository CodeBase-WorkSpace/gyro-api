package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.SubscriptionStatus
import com.gyro.api.subscription.domain.UserSubscription
import java.time.Instant

data class SubscriptionStateSnapshot(
    val status: SubscriptionStatus,
    val planId: Long,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val gracePeriodEnd: Instant?,
    val graceReason: String?,
)

fun UserSubscription.toStateSnapshot() = SubscriptionStateSnapshot(
    status = status,
    planId = planId,
    periodStart = periodStart,
    periodEnd = periodEnd,
    cancelAtPeriodEnd = cancelAtPeriodEnd,
    gracePeriodEnd = gracePeriodEnd,
    graceReason = graceReason,
)
