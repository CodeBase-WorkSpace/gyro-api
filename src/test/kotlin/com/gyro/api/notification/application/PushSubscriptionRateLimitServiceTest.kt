package com.gyro.api.notification.application

import com.gyro.api.common.ratelimit.RateLimitService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushSubscriptionRateLimitServiceTest {
    @Test
    fun `applies separate per-user limits to subscription and test actions`() {
        val rateLimits = Mockito.mock(RateLimitService::class.java)
        val service = PushSubscriptionRateLimitService(rateLimits, 20, 5, Duration.ofMinutes(15))
        val userId = UUID.randomUUID()

        service.checkSubscribe(userId)
        service.checkTest(userId)

        val invocations = Mockito.mockingDetails(rateLimits).invocations.toList()
        assertEquals(listOf(20L, 5L), invocations.map { it.arguments[1] })
        assertTrue(invocations[0].arguments[0].toString().startsWith("rate:web-push:subscribe:user:"))
        assertTrue(invocations[1].arguments[0].toString().startsWith("rate:web-push:test:user:"))
        assertEquals(listOf(Duration.ofMinutes(15), Duration.ofMinutes(15)), invocations.map { it.arguments[2] })
    }
}
