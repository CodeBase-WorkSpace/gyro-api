package com.gyro.api.notification.web

import com.gyro.api.common.error.ApiErrorResponseWriter
import com.gyro.api.notification.config.NotificationProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramWebhookAuthenticationFilterTest {
    private val mapper = JsonMapper.builder().build()
    private val properties = NotificationProperties(
        telegramEnabled = true,
        telegramLinkingEnabled = true,
        telegramWebhookSecret = "test-webhook-secret",
    )
    private val filter = TelegramWebhookAuthenticationFilter(
        "/api/v1",
        properties,
        ApiErrorResponseWriter(mapper),
    )

    @Test
    fun `rejects invalid secret before reading or forwarding webhook body`() {
        val request = request("not-json", "wrong-secret")
        val response = MockHttpServletResponse()
        var chainCalled = false

        filter.doFilter(request, response, CapturingFilterChain { chainCalled = true })

        assertEquals(401, response.status)
        assertFalse(chainCalled)
        assertEquals("INVALID_CREDENTIALS", mapper.readTree(response.contentAsString)["code"].asString())
    }

    @Test
    fun `forwards a bounded authenticated payload from the cached body`() {
        val payload = """{"update_id":1}"""
        val request = request(payload, properties.telegramWebhookSecret)
        val response = MockHttpServletResponse()
        var forwardedBody: String? = null

        filter.doFilter(request, response, CapturingFilterChain { forwarded ->
            forwardedBody = String(forwarded.inputStream.readAllBytes())
        })

        assertEquals(payload, forwardedBody)
        assertEquals(200, response.status)
    }

    @Test
    fun `rejects authenticated payload larger than endpoint limit`() {
        val request = request("x".repeat(TelegramWebhookAuthenticationFilter.MAX_PAYLOAD_BYTES + 1), properties.telegramWebhookSecret)
        val response = MockHttpServletResponse()
        var chainCalled = false

        filter.doFilter(request, response, CapturingFilterChain { chainCalled = true })

        assertEquals(413, response.status)
        assertFalse(chainCalled)
        assertEquals("VALIDATION_ERROR", mapper.readTree(response.contentAsString)["code"].asString())
    }

    @Test
    fun `does not filter other endpoints`() {
        val request = MockHttpServletRequest("POST", "/api/v1/other")
        val response = MockHttpServletResponse()
        var chainCalled = false

        filter.doFilter(request, response, CapturingFilterChain { chainCalled = true })

        assertTrue(chainCalled)
    }

    private fun request(body: String, secret: String) =
        MockHttpServletRequest("POST", "/api/v1/integrations/telegram/webhook").apply {
            setContent(body.toByteArray())
            addHeader(TelegramWebhookAuthenticationFilter.SECRET_HEADER, secret)
        }

    private class CapturingFilterChain(
        private val capture: (ServletRequest) -> Unit,
    ) : FilterChain {
        override fun doFilter(request: ServletRequest, response: ServletResponse) = capture(request)
    }
}
