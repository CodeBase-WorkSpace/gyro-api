package com.gyro.api.auth.application

import com.gyro.api.common.error.TooManyVerificationAttemptsException
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Caps the number of failed verification-code attempts per stored code.
 *
 * A code is only bounded by its 6-digit space and TTL otherwise, so without a cap an
 * attacker could exhaust the space within the code's lifetime. Each stored code key gets a
 * companion counter; once failures reach [maxAttempts] the code is invalidated (locked out)
 * and [TooManyVerificationAttemptsException] is thrown. Counters are cleared whenever a fresh
 * code is issued or a code is successfully consumed so legitimate resends are never penalised.
 */
@Component
class VerificationAttemptGuard(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${app.auth.verification-max-attempts:5}")
    private val maxAttempts: Long,
    @Value("\${app.auth.verification-code-ttl:10m}")
    private val ttl: Duration,
) {
    /**
     * Records a failed attempt for [codeKey]. When the failure count reaches the cap this
     * deletes the stored code and throws [TooManyVerificationAttemptsException]; otherwise it
     * returns so the caller can surface the ordinary invalid-code error.
     */
    fun registerFailure(codeKey: String) {
        val attemptsKey = attemptsKey(codeKey)
        val attempts = redisTemplate.opsForValue().increment(attemptsKey) ?: 1L
        if (attempts == 1L) {
            redisTemplate.expire(attemptsKey, ttl)
        }
        if (attempts >= maxAttempts) {
            redisTemplate.delete(codeKey)
            redisTemplate.delete(attemptsKey)
            throw TooManyVerificationAttemptsException()
        }
    }

    /** Clears the attempt counter for [codeKey] (on successful confirm or when a new code is issued). */
    fun clear(codeKey: String) {
        redisTemplate.delete(attemptsKey(codeKey))
    }

    private fun attemptsKey(codeKey: String): String = "$codeKey:attempts"
}
