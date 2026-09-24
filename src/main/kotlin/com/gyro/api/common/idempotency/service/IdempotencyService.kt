package com.gyro.api.common.idempotency.service

import java.util.*

interface IdempotencyService {
    fun <T : Any> execute(
        scope: String,
        ownerUserId: UUID? = null,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        responseStatus: Int,
        action: () -> T,
    ): IdempotencyResult<T>

    fun <T : Any> executeResult(
        scope: String,
        ownerUserId: UUID? = null,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        action: () -> IdempotencyResult<T>,
    ): IdempotencyResult<T>

    /**
     * Execute a workflow that owns its transaction boundaries without wrapping the action in
     * the idempotency transaction. Lookup, replay, and response caching semantics stay the same.
     */
    fun <T : Any> executeWithoutActionTransaction(
        scope: String,
        ownerUserId: UUID? = null,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        responseStatus: Int,
        action: () -> T,
    ): IdempotencyResult<T>
}
