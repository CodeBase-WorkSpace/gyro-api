package com.gyro.api.common.request

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpRequestLoggingFilterTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val filter = HttpRequestLoggingFilter(
        meterRegistry,
        TrustedClientIpResolver(ClientIpProperties(listOf("172.16.0.0/12"))),
    )
    private val logger = LoggerFactory.getLogger(HttpRequestLoggingFilter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        RequestIds.clear()
    }

    @Test
    fun `filter logs one safe structured line after authenticated completed request`() {
        val request = MockHttpServletRequest("POST", "/api/v1/foods/search").apply {
            remoteAddr = "203.0.113.10"
            queryString = "q=banana"
            setContent("""{"query":"banana"}""".toByteArray())
            addHeader("User-Agent", "GyroWeb/1.0")
            addHeader("Authorization", "Bearer secret-access-token")
            addHeader("Cookie", "refreshToken=secret-refresh-token")
            setAttribute(RequestIds.ATTRIBUTE_NAME, "request-123")
            setAttribute(USER_ID_ATTRIBUTE, "user-123")
            setAttribute(USER_ROLE_ATTRIBUTE, "USER")
            setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern", "/api/v1/foods/search")
        }
        val response = MockHttpServletResponse()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        filter.doFilter(
            request,
            response,
            CapturingFilterChain {
                response.status = 200
            },
        )

        assertEquals(1, appender.list.size)
        val event = appender.list.single()
        assertEquals(Level.INFO, event.level)
        assertEquals("HTTP request completed", event.formattedMessage)
        val fields = event.keyValuePairs.associate { it.key to it.value }
        assertEquals("http_request", fields["event"])
        assertFalse(fields.containsKey("requestId"))
        assertEquals("request-123", event.mdcPropertyMap["requestId"])
        assertEquals("POST", fields["method"])
        assertEquals("/api/v1/foods/search", fields["path"])
        assertEquals(200, fields["status"])
        assertEquals("user-123", fields["userId"])
        assertEquals("USER", fields["role"])
        assertEquals("203.0.113.10", fields["clientIp"])
        assertEquals("GyroWeb/1.0", fields["userAgent"])
        assertEquals(request.contentLengthLong, fields["contentLength"])
        assertEquals(true, fields["queryPresent"])
        assertNull(fields["errorCode"])
        assertEquals(
            1.0,
            meterRegistry.find("gyro.api.requests")
                .tags(
                    "method", "POST",
                    "route", "/api/v1/foods/search",
                    "status", "200",
                    "outcome", "SUCCESS",
                ).timer()!!.count().toDouble(),
        )
        assertFalse(event.formattedMessage.contains("Authorization"))
        assertFalse(event.formattedMessage.contains("Cookie"))
        assertFalse(event.formattedMessage.contains("password"))
        assertFalse(event.formattedMessage.contains("refreshToken"))
    }

    @Test
    fun `filter generates request id when none exists`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me")
        val response = MockHttpServletResponse()
        logger.addAppender(appender)

        filter.doFilter(request, response, CapturingFilterChain())

        val requestId = response.getHeader(RequestIds.HEADER_NAME)
        assertNotNull(requestId)
        assertEquals(requestId, request.getAttribute(RequestIds.ATTRIBUTE_NAME))
        assertEquals(requestId, appender.list.single().mdcPropertyMap["requestId"])
        assertNull(RequestIds.current())
    }

    @Test
    fun `filter logs anonymous request metadata`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/login").apply {
            setAttribute(RequestIds.ATTRIBUTE_NAME, "request-123")
        }
        val response = MockHttpServletResponse()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        filter.doFilter(request, response, CapturingFilterChain())

        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("anonymous", fields["userId"])
        assertEquals("anonymous", fields["role"])
        assertEquals(false, fields["queryPresent"])
    }

    @Test
    fun `filter logs the peer address when an untrusted caller spoofs forwarding headers`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me").apply {
            remoteAddr = "198.51.100.20"
            addHeader("X-Forwarded-For", "203.0.113.99")
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.98")
        }
        logger.addAppender(appender)

        filter.doFilter(request, MockHttpServletResponse(), CapturingFilterChain())

        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("198.51.100.20", fields["clientIp"])
    }

    @Test
    fun `filter logs the overwritten client address from a trusted proxy`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me").apply {
            remoteAddr = "172.20.0.4"
            addHeader("X-Forwarded-For", "203.0.113.99")
            addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.12")
        }
        logger.addAppender(appender)

        filter.doFilter(request, MockHttpServletResponse(), CapturingFilterChain())

        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("203.0.113.12", fields["clientIp"])
    }

    @Test
    fun `filter does not log authorization or cookie header values to structured output`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me").apply {
            addHeader("Authorization", "Bearer secret-access-token")
            addHeader("Cookie", "refreshToken=secret-refresh-token")
            setAttribute(RequestIds.ATTRIBUTE_NAME, "request-123")
        }
        val response = MockHttpServletResponse()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        filter.doFilter(request, response, CapturingFilterChain())

        val json = structuredJson(appender.list.single())
        assertFalse(json.contains("secret-access-token"))
        assertFalse(json.contains("secret-refresh-token"))
        assertFalse(json.contains("Authorization"))
        assertFalse(json.contains("Cookie"))
    }

    @Test
    fun `filter skips health endpoint`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        logger.addAppender(appender)

        filter.doFilter(request, response, CapturingFilterChain())

        assertTrue(appender.list.isEmpty())
        assertNull(meterRegistry.find("gyro.api.requests").timer())
    }

    @Test
    fun `filter logs one completed request event when handler throws`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me").apply {
            setAttribute(RequestIds.ATTRIBUTE_NAME, "request-123")
        }
        val response = MockHttpServletResponse()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        assertFailsWith<IllegalStateException> {
            filter.doFilter(
                request,
                response,
                CapturingFilterChain {
                    throw IllegalStateException("boom")
                },
            )
        }

        assertEquals(1, appender.list.size)
        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("http_request", fields["event"])
        assertEquals(500, fields["status"])
    }

    @Test
    fun `production structured encoder writes request id once`() {
        val request = MockHttpServletRequest("GET", "/api/v1/users/me").apply {
            setAttribute(RequestIds.ATTRIBUTE_NAME, "request-123")
        }
        val response = MockHttpServletResponse()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        filter.doFilter(request, response, CapturingFilterChain())

        val json = structuredJson(appender.list.single())

        assertEquals(1, "\"requestId\"".toRegex().findAll(json).count())
        assertTrue(json.contains("\"requestId\":\"request-123\""))
    }

    private fun structuredJson(event: ILoggingEvent): String {
        val loggerContext = logger.loggerContext.apply {
            putObject(Environment::class.java.name, StandardEnvironment())
        }
        val encoder = StructuredLogEncoder().apply {
            context = loggerContext
            setFormat("logstash")
            start()
        }

        val json = encoder.encode(event).decodeToString()
        encoder.stop()
        return json
    }

    private class CapturingFilterChain(
        private val onFilter: () -> Unit = {},
    ) : FilterChain {
        override fun doFilter(
            request: ServletRequest,
            response: ServletResponse,
        ) {
            onFilter()
        }
    }
}
