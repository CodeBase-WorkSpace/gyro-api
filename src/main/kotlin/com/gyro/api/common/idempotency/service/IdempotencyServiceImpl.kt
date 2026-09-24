package com.gyro.api.common.idempotency.service

import com.gyro.api.common.error.IdempotencyKeyConflictException
import com.gyro.api.common.error.InvalidIdempotencyKeyException
import com.gyro.api.common.idempotency.domain.IdempotencyKey
import com.gyro.api.common.idempotency.repository.IdempotencyKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class IdempotencyServiceImpl(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.idempotency.ttl:24h}")
    private val ttl: Duration,
) : IdempotencyService {
    private val actionLocks = Array(ACTION_LOCK_STRIPES) { ReentrantLock() }

    @Transactional
    override fun <T : Any> execute(
        scope: String,
        ownerUserId: UUID?,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        responseStatus: Int,
        action: () -> T,
    ): IdempotencyResult<T> {
        return executeResult(
            scope = scope,
            ownerUserId = ownerUserId,
            idempotencyKey = idempotencyKey,
            request = request,
            responseType = responseType,
        ) {
            IdempotencyResult(
                responseStatus = responseStatus,
                body = action(),
            )
        }
    }

    @Transactional
    override fun <T : Any> executeResult(
        scope: String,
        ownerUserId: UUID?,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        action: () -> IdempotencyResult<T>,
    ): IdempotencyResult<T> = executeResultInternal(
        scope = scope,
        ownerUserId = ownerUserId,
        idempotencyKey = idempotencyKey,
        request = request,
        responseType = responseType,
        action = action,
    )

    override fun <T : Any> executeWithoutActionTransaction(
        scope: String,
        ownerUserId: UUID?,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        responseStatus: Int,
        action: () -> T,
    ): IdempotencyResult<T> {
        val normalizedKey = idempotencyKey?.trim()
        if (normalizedKey.isNullOrBlank()) {
            return executeResultInternal(
                scope = scope,
                ownerUserId = ownerUserId,
                idempotencyKey = normalizedKey,
                request = request,
                responseType = responseType,
            ) {
                IdempotencyResult(responseStatus = responseStatus, body = action())
            }
        }

        // The current deployment intentionally runs one API instance while lifecycle jobs are
        // enabled. Serialize first use locally so the provider call stays outside a database
        // transaction without allowing two same-key requests to execute it concurrently.
        // A horizontally scaled deployment requires a durable IN_PROGRESS lease in the schema.
        return actionLock(scope, normalizedKey).withLock {
            executeResultInternal(
                scope = scope,
                ownerUserId = ownerUserId,
                idempotencyKey = normalizedKey,
                request = request,
                responseType = responseType,
            ) {
                IdempotencyResult(responseStatus = responseStatus, body = action())
            }
        }
    }

    private fun actionLock(scope: String, normalizedKey: String): ReentrantLock {
        val stripe = Math.floorMod(31 * scope.hashCode() + normalizedKey.hashCode(), actionLocks.size)
        return actionLocks[stripe]
    }

    private fun <T : Any> executeResultInternal(
        scope: String,
        ownerUserId: UUID?,
        idempotencyKey: String?,
        request: Any,
        responseType: Class<T>,
        action: () -> IdempotencyResult<T>,
    ): IdempotencyResult<T> {
        val normalizedKey = idempotencyKey?.trim()

        if (normalizedKey.isNullOrBlank()) {
            logger.atInfo()
                .addKeyValue("event", "idempotency")
                .addKeyValue("stage", "bypassed")
                .addKeyValue("scope", scope)
                .log("Idempotency bypassed because no key was provided.")
            return action()
        }

        validateIdempotencyKey(normalizedKey)
        logger.atInfo()
            .addKeyValue("event", "idempotency")
            .addKeyValue("stage", "lookup_started")
            .addKeyValue("scope", scope)
            .log("Looking up idempotency key.")

        val requestHash = hash(objectMapper.writeValueAsString(request))
        val existing = idempotencyKeyRepository.findByScopeAndIdempotencyKey(scope, normalizedKey)

        if (existing != null) {
            if (existing.requestHash != requestHash) {
                logger.atWarn()
                    .addKeyValue("event", "idempotency")
                    .addKeyValue("stage", "conflict")
                    .addKeyValue("scope", scope)
                    .log("Idempotency key conflict.")
                throw IdempotencyKeyConflictException()
            }

            logger.atInfo()
                .addKeyValue("event", "idempotency")
                .addKeyValue("stage", "replay")
                .addKeyValue("scope", scope)
                .addKeyValue("responseStatus", existing.responseStatus)
                .log("Replaying stored idempotent response.")
            return IdempotencyResult(
                responseStatus = existing.responseStatus,
                body = objectMapper.readValue(existing.responseBody, responseType),
            )
        }

        logger.atInfo()
            .addKeyValue("event", "idempotency")
            .addKeyValue("stage", "action_started")
            .addKeyValue("scope", scope)
            .log("Executing idempotent action.")
        val response = action()
        val now = Instant.now()

        logger.atInfo()
            .addKeyValue("event", "idempotency")
            .addKeyValue("stage", "save_started")
            .addKeyValue("scope", scope)
            .addKeyValue("responseStatus", response.responseStatus)
            .log("Saving idempotent response.")
        idempotencyKeyRepository.save(
            IdempotencyKey(
                scope = scope,
                userId = ownerUserId,
                idempotencyKey = normalizedKey,
                requestHash = requestHash,
                responseStatus = response.responseStatus,
                responseBody = objectMapper.writeValueAsString(response.body),
                createdAt = now,
                expiresAt = now.plus(ttl),
            )
        )
        logger.atInfo()
            .addKeyValue("event", "idempotency")
            .addKeyValue("stage", "save_completed")
            .addKeyValue("scope", scope)
            .addKeyValue("responseStatus", response.responseStatus)
            .log("Saved idempotent response.")

        return response
    }

    private fun validateIdempotencyKey(idempotencyKey: String) {
        if (idempotencyKey.length !in 8..255 || !IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            throw InvalidIdempotencyKeyException()
        }
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        private const val ACTION_LOCK_STRIPES = 256
        private val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]+$")
        private val logger = LoggerFactory.getLogger(IdempotencyServiceImpl::class.java)
    }
}
