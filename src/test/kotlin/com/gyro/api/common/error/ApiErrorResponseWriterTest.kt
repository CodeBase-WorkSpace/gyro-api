package com.gyro.api.common.error

import com.gyro.api.common.request.RequestIds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class ApiErrorResponseWriterTest {
    private val objectMapper = JsonMapper.builder().build()
    private val writer = ApiErrorResponseWriter(objectMapper)

    @AfterEach
    fun clearRequestId() {
        RequestIds.clear()
    }

    @Test
    fun `writer includes request ID in auth style error response`() {
        RequestIds.put("test-auth-123")
        val response = MockHttpServletResponse()

        writer.write(
            response = response,
            status = 401,
            code = ApiErrorCode.INVALID_CREDENTIALS,
            message = "Authentication is required.",
        )

        val json = objectMapper.readTree(response.contentAsString)
        assertEquals(401, response.status)
        assertTrue(MediaType.parseMediaType(response.contentType ?: "").isCompatibleWith(MediaType.APPLICATION_JSON))
        assertEquals(401, json["status"].asInt())
        assertEquals("INVALID_CREDENTIALS", json["code"].asText())
        assertEquals("test-auth-123", json["requestId"].asText())
    }
}
