package com.gyro.api.common.error

import com.gyro.api.common.request.RequestIds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @AfterEach
    fun clearRequestId() {
        RequestIds.clear()
    }

    @Test
    fun `domain error response includes current request ID`() {
        RequestIds.put("test-domain-123")

        val response = handler.handleDomainException(ResourceNotFoundException("Food"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("test-domain-123", response.body?.requestId)
        assertEquals(ApiErrorCode.RESOURCE_NOT_FOUND, response.body?.code)
    }

    @Test
    fun `promotion error response includes stable reason code`() {
        RequestIds.put("test-promo-123")

        val response = handler.handleDomainException(
            PromotionException("promotion_expired", "Promotion code has expired."),
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("test-promo-123", response.body?.requestId)
        assertEquals(ApiErrorCode.PROMOTION_INVALID, response.body?.code)
        assertEquals("promotion_expired", response.body?.reasonCode)
    }

    @Test
    fun `unexpected error response includes current request ID`() {
        RequestIds.put("test-unexpected-123")

        val response = handler.handleUnexpectedException(IllegalStateException("boom"))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("test-unexpected-123", response.body?.requestId)
        assertEquals(ApiErrorCode.INTERNAL_ERROR, response.body?.code)
    }
}
