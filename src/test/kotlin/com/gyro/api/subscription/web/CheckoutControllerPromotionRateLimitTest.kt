package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.CheckoutService
import com.gyro.api.subscription.application.PromotionMetrics
import com.gyro.api.subscription.application.PromotionRateLimitService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

class CheckoutControllerPromotionRateLimitTest {
    @Test
    fun `checkout does not consume the promotion probing limit`() {
        val checkoutService = Mockito.mock(CheckoutService::class.java)
        val rateLimitService = Mockito.mock(PromotionRateLimitService::class.java)
        val metrics = Mockito.mock(PromotionMetrics::class.java)
        val controller = CheckoutController(checkoutService, rateLimitService, metrics, false)
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.7" }

        assertThrows(RuntimeException::class.java) {
            controller.createCheckout(
                CheckoutRequestDto(1L, "SAVE20"),
                "checkout-idempotency-key",
                UUID.randomUUID().toString(),
                request,
            )
        }

        Mockito.verifyNoInteractions(rateLimitService)
    }

    @Test
    fun `validation endpoint checks promotion rate limit before validation`() {
        val checkoutService = Mockito.mock(CheckoutService::class.java)
        val rateLimitService = Mockito.mock(PromotionRateLimitService::class.java)
        val metrics = Mockito.mock(PromotionMetrics::class.java)
        val controller = CheckoutController(checkoutService, rateLimitService, metrics, true)
        val userId = UUID.randomUUID()
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.7" }

        Mockito.doThrow(IllegalStateException("limited"))
            .`when`(rateLimitService)
            .checkValidate(userId, "203.0.113.7")

        assertThrows(IllegalStateException::class.java) {
            controller.validatePromotion(PromotionValidationRequestDto(1L, "SAVE20"), userId.toString(), request)
        }

        Mockito.verify(metrics).rateLimited("validate")
        Mockito.verifyNoInteractions(checkoutService)
    }
}
