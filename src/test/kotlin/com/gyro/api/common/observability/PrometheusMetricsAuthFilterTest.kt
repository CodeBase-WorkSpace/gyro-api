package com.gyro.api.common.observability

import com.gyro.api.common.error.ApiErrorResponseWriter
import com.gyro.api.common.request.RequestIds
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrometheusMetricsAuthFilterTest {
    private val objectMapper = JsonMapper.builder().build()
    private val filter = PrometheusMetricsAuthFilter(
        properties = ObservabilityProperties(metricsToken = "metrics-token"),
        apiErrorResponseWriter = ApiErrorResponseWriter(objectMapper),
    )

    @AfterEach
    fun tearDown() {
        RequestIds.clear()
    }

    @Test
    fun `rejects Prometheus scrape without operations token`() {
        RequestIds.put("request-123")
        val request = MockHttpServletRequest("GET", "/actuator/prometheus")
        val response = MockHttpServletResponse()
        var chainCalled = false

        filter.doFilter(request, response, CapturingFilterChain { chainCalled = true })

        val body = objectMapper.readTree(response.contentAsString)
        assertEquals(401, response.status)
        assertFalse(chainCalled)
        assertEquals("INVALID_CREDENTIALS", body["code"].asText())
        assertEquals("request-123", body["requestId"].asText())
    }

    @Test
    fun `allows Prometheus scrape with operations token`() {
        val request = MockHttpServletRequest("GET", "/actuator/prometheus").apply {
            addHeader(PrometheusMetricsAuthFilter.TOKEN_HEADER, "metrics-token")
        }
        val response = MockHttpServletResponse()
        var chainCalled = false

        filter.doFilter(request, response, CapturingFilterChain { chainCalled = true })

        assertTrue(chainCalled)
    }

    private class CapturingFilterChain(
        private val onFilter: () -> Unit,
    ) : FilterChain {
        override fun doFilter(
            request: ServletRequest,
            response: ServletResponse,
        ) = onFilter()
    }
}
