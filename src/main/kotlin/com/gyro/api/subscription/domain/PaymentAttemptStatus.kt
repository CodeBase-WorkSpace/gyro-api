package com.gyro.api.subscription.domain

enum class PaymentAttemptStatus {
    PENDING,
    CREATE_FAILED,
    STALE,
    CANCELLED,
    VERIFY_PENDING,
    VERIFIED,
    FAILED,
}
