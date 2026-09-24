package com.gyro.api.subscription.web

import com.gyro.api.common.error.CheckoutDisabledException
import com.gyro.api.subscription.application.CheckoutResponse
import com.gyro.api.subscription.application.CheckoutService
import com.gyro.api.subscription.application.PromotionMetrics
import com.gyro.api.subscription.application.PromotionRateLimitService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/billing")
class CheckoutController(
    private val checkoutService: CheckoutService,
    private val promotionRateLimitService: PromotionRateLimitService,
    private val promotionMetrics: PromotionMetrics,
    @Value("\${app.billing.checkout-enabled:true}")
    private val checkoutEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/checkout")
    fun createCheckout(
        @RequestBody body: CheckoutRequestDto,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal userId: String,
        request: HttpServletRequest,
    ): ResponseEntity<CheckoutResponseDto> {
        val requestId = request.getHeader("X-Request-Id")
        val authenticatedUserId = UUID.fromString(userId)
        if (!checkoutEnabled) {
            log.warn("event=checkout_rejection reason=checkout_disabled user_id={}", authenticatedUserId)
            throw CheckoutDisabledException()
        }

        log.info(
            "event=checkout_start user_id={} price_id={} has_promotion={} has_idempotency_key={} request_id={}",
            authenticatedUserId,
            body.priceId,
            body.promotionCode != null,
            idempotencyKey != null,
            requestId,
        )

        val result = checkoutService.checkout(
            userId = authenticatedUserId,
            priceId = body.priceId,
            idempotencyKey = idempotencyKey,
            promotionCode = body.promotionCode,
        )

        return when (result) {
            is CheckoutResponse.Success -> {
                log.info(
                    "event=checkout_success user_id={} invoice_id={} payment_attempt_id={} request_id={}",
                    authenticatedUserId,
                    result.invoiceId,
                    result.paymentAttemptId,
                    requestId,
                )
                ResponseEntity.ok(
                    CheckoutResponseDto(
                        status = "SUCCESS",
                        invoiceId = result.invoiceId.toString(),
                        paymentAttemptId = result.paymentAttemptId.toString(),
                        gatewayUrl = result.gatewayUrl,
                        requestId = requestId,
                    ),
                )
            }
            is CheckoutResponse.Failed -> {
                log.warn(
                    "event=checkout_failure user_id={} invoice_id={} reason={} request_id={}",
                    authenticatedUserId,
                    result.invoiceId,
                    result.reason,
                    requestId,
                )
                ResponseEntity.ok(
                    CheckoutResponseDto(
                        status = "FAILED",
                        invoiceId = result.invoiceId.toString(),
                        paymentAttemptId = result.paymentAttemptId.toString(),
                        failureReason = result.reason,
                        requestId = requestId,
                    ),
                )
            }
        }
    }

    @PostMapping("/promotions/validate")
    fun validatePromotion(
        @RequestBody body: PromotionValidationRequestDto,
        @AuthenticationPrincipal userId: String,
        request: HttpServletRequest,
    ): ResponseEntity<PromotionValidationResponseDto> {
        val requestId = request.getHeader("X-Request-Id")
        val authenticatedUserId = UUID.fromString(userId)
        try {
            promotionRateLimitService.checkValidate(authenticatedUserId, request.remoteAddr)
        } catch (exception: RuntimeException) {
            promotionMetrics.rateLimited("validate")
            throw exception
        }

        log.info(
            "event=promotion_validate_start user_id={} price_id={} has_promotion={} request_id={}",
            authenticatedUserId,
            body.priceId,
            body.promotionCode.isNotBlank(),
            requestId,
        )

        val result = checkoutService.validatePromotion(
            userId = authenticatedUserId,
            priceId = body.priceId,
            promotionCode = body.promotionCode,
        )

        log.info(
            "event=promotion_validate_success user_id={} price_id={} request_id={}",
            authenticatedUserId,
            body.priceId,
            requestId,
        )

        return ResponseEntity.ok(PromotionValidationResponseDto.from(result))
    }
}
