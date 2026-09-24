package com.gyro.api.notification.web

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.common.request.ClientIpProperties
import com.gyro.api.common.request.TrustedClientIpResolver
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.TelegramAccountLinkingService
import com.gyro.api.notification.application.TelegramLinkConsumption
import com.gyro.api.notification.application.TelegramLinkRateLimitService
import com.gyro.api.notification.application.TelegramLinkResult
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class TelegramLinkingControllerTest {
    @Test
    fun `confirm endpoint applies distributed rate limits before code consumption`() {
        val linking = Mockito.mock(TelegramAccountLinkingService::class.java)
        val rateLimits = Mockito.mock(TelegramLinkRateLimitService::class.java)
        val notifications = Mockito.mock(NotificationService::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val metrics = Mockito.mock(NotificationMetrics::class.java)
        val controller = TelegramLinkingController(
            linking,
            rateLimits,
            notifications,
            time,
            metrics,
            TrustedClientIpResolver(ClientIpProperties(listOf("10.0.0.0/8"))),
        )
        val userId = UUID.randomUUID()
        val servletRequest = MockHttpServletRequest().apply {
            remoteAddr = "10.0.0.2"
            addHeader("X-Forwarded-For", "198.51.100.99")
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.12")
        }
        Mockito.`when`(linking.consume(userId, "ABCD-EFGH")).thenReturn(
            TelegramLinkResult(TelegramLinkConsumption.INVALID),
        )

        assertThrows<ResponseStatusException> {
            controller.confirmLink(userId.toString(), TelegramLinkConfirmRequest("ABCD-EFGH"), servletRequest)
        }

        Mockito.verify(rateLimits).checkConfirm(userId, "203.0.113.12")
        Mockito.verify(linking).consume(userId, "ABCD-EFGH")
        Mockito.verify(metrics).telegramLinkConfirmation("INVALID")
    }
}
