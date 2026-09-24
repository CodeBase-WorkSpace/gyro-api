package com.gyro.api.subscription.domain

enum class SubscriptionStatus {
    ACTIVE,
    GRACE_PERIOD,
    CANCELED,
    EXPIRED,
    BILLED_BLOCKED,
}
