package com.gyro.api.common.outbox

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
