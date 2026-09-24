package com.gyro.api.goal.application.coach

import com.gyro.api.common.ratelimit.RateLimitService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoachInsightImpressionRateLimitServiceTest {
    @Test
    fun `applies the configured per-user impression limit without exposing the user id`() {
        val rateLimits = Mockito.mock(RateLimitService::class.java)
        val service = CoachInsightImpressionRateLimitService(
            rateLimits,
            60,
            Duration.ofMinutes(15),
        )
        val userId = UUID.randomUUID()

        service.check(userId)

        val invocation = Mockito.mockingDetails(rateLimits).invocations.single()
        val key = invocation.arguments[0].toString()
        assertTrue(key.startsWith("rate:nutrition-coach:impression:user:"))
        assertTrue(!key.contains(userId.toString()))
        assertEquals(60L, invocation.arguments[1])
        assertEquals(Duration.ofMinutes(15), invocation.arguments[2])
    }
}
