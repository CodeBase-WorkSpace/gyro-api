package com.gyro.api.common.request

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestIdFilterTest {
    private val filter = RequestIdFilter()

    @Test
    fun `filter echoes valid incoming request ID`() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIds.HEADER_NAME, "test-request-123")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, CapturingFilterChain())

        assertEquals("test-request-123", response.getHeader(RequestIds.HEADER_NAME))
        assertEquals("test-request-123", request.getAttribute(RequestIds.ATTRIBUTE_NAME))
        assertNull(RequestIds.current())
    }

    @Test
    fun `filter generates UUID request ID when incoming request ID is missing`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, CapturingFilterChain())

        val requestId = response.getHeader(RequestIds.HEADER_NAME)
        assertNotNull(requestId)
        assertTrue(UUID_PATTERN.matches(requestId))
        assertEquals(requestId, request.getAttribute(RequestIds.ATTRIBUTE_NAME))
        assertNull(RequestIds.current())
    }

    @Test
    fun `filter stores request ID in MDC while request is active`() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIds.HEADER_NAME, "active-request-123")
        }
        val response = MockHttpServletResponse()
        var activeRequestId: String? = null

        filter.doFilter(
            request,
            response,
            CapturingFilterChain {
                activeRequestId = RequestIds.current()
            },
        )

        assertEquals("active-request-123", activeRequestId)
        assertNull(RequestIds.current())
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

    private companion object {
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
