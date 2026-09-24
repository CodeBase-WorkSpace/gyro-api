package com.gyro.api.notification.application

import com.gyro.api.common.ratelimit.RateLimitService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramLinkRateLimitServiceTest {
    @Test
    fun `confirmation attempts consume both per-user and per-ip limits`() {
        val rateLimits = Mockito.mock(RateLimitService::class.java)
        val service = TelegramLinkRateLimitService(
            rateLimits,
            linkLimit = 5,
            linkWindow = Duration.ofHours(1),
            confirmPerUserLimit = 5,
            confirmPerIpLimit = 20,
            confirmWindow = Duration.ofMinutes(15),
            testLimit = 3,
            testWindow = Duration.ofMinutes(10),
        )
        val userId = UUID.randomUUID()

        service.checkConfirm(userId, "203.0.113.12")

        val invocations = Mockito.mockingDetails(rateLimits).invocations.toList()
        assertEquals(listOf(5L, 20L), invocations.map { it.arguments[1] })
        assertEquals(listOf(Duration.ofMinutes(15), Duration.ofMinutes(15)), invocations.map { it.arguments[2] })
        assertTrue(invocations[0].arguments[0].toString().startsWith("rate:telegram:confirm:user:"))
        assertTrue(invocations[1].arguments[0].toString().startsWith("rate:telegram:confirm:ip:"))
        assertFalse(invocations.any { it.arguments[0].toString().contains(userId.toString()) })
        assertFalse(invocations.any { it.arguments[0].toString().contains("203.0.113.12") })
    }
}
