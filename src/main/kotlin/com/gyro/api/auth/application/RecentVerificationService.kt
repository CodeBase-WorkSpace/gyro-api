package com.gyro.api.auth.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.*

/**
 * Server-side "recently verified" marker for sensitive account actions.
 *
 * A successful OTP step-up sets a short-lived marker keyed by user id. Sensitive endpoints
 * (change/remove password) check this marker instead of trusting any client-supplied
 * timestamp, so a stolen access token alone cannot mutate credentials without a fresh code.
 */
@Service
class RecentVerificationService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${app.auth.step-up-ttl:10m}")
    private val ttl: Duration,
) {
    fun markVerified(userId: UUID) {
        redisTemplate.opsForValue().set(key(userId), "1", ttl)
    }

    /**
     * Atomically consumes the marker so a single step-up authorises exactly one sensitive action.
     * Uses Redis `GETDEL` so concurrent sensitive requests cannot both observe the same marker;
     * only the caller that removes it is authorised. Returns true when a marker was present.
     */
    fun consumeIfPresent(userId: UUID): Boolean {
        return redisTemplate.opsForValue().getAndDelete(key(userId)) != null
    }

    private fun key(userId: UUID): String = "recent-verify:$userId"
}
