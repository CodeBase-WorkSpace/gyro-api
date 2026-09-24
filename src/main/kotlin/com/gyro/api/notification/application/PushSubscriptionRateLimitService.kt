package com.gyro.api.notification.application

import com.gyro.api.common.ratelimit.RateLimitService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Service
class PushSubscriptionRateLimitService(
    private val rateLimits: RateLimitService,
    @Value("\${app.rate-limit.web-push.subscribe-per-user-limit}") private val subscribePerUserLimit: Long,
    @Value("\${app.rate-limit.web-push.test-per-user-limit}") private val testPerUserLimit: Long,
    @Value("\${app.rate-limit.web-push.window}") private val window: Duration,
) {
    fun checkSubscribe(userId: UUID) = check("subscribe", userId, subscribePerUserLimit)

    fun checkTest(userId: UUID) = check("test", userId, testPerUserLimit)

    private fun check(action: String, userId: UUID, limit: Long) {
        val userKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(userId.toString().toByteArray()),
        )
        rateLimits.check("rate:web-push:$action:user:$userKey", limit, window)
    }
}
