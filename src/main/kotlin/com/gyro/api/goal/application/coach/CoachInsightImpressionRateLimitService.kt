package com.gyro.api.goal.application.coach

import com.gyro.api.common.ratelimit.RateLimitService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Service
class CoachInsightImpressionRateLimitService(
    private val rateLimits: RateLimitService,
    @Value("\${app.rate-limit.nutrition-coach.impressions-per-user-limit:60}")
    private val perUserLimit: Long,
    @Value("\${app.rate-limit.nutrition-coach.window:15m}")
    private val window: Duration,
) {
    fun check(userId: UUID) {
        val userKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(userId.toString().toByteArray()),
        )
        rateLimits.check(
            "rate:nutrition-coach:impression:user:$userKey",
            perUserLimit,
            window,
        )
    }
}
