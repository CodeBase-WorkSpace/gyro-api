package com.gyro.api.common.idempotency.service

data class IdempotencyResult<T : Any>(
    val responseStatus: Int,
    val body: T,
)
