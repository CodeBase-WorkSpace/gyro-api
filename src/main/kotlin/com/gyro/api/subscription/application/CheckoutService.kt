package com.gyro.api.subscription.application

import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.subscription.domain.Money
import org.springframework.stereotype.Service
import java.util.*

@Service
class CheckoutService(
    private val idempotencyService: IdempotencyService,
    private val checkoutWorkflow: CheckoutWorkflow,
    private val checkoutTransactions: CheckoutTransactions,
) {
    /**
     * Create a checkout for a subscription price.
     * Handles idempotency, invoice creation, payment attempt creation, and provider checkout.
     */
    fun checkout(
        userId: UUID,
        priceId: Long,
        idempotencyKey: String? = null,
        promotionCode: String? = null,
    ): CheckoutResponse {
        val scope = "checkout:$userId"

        val result = idempotencyService.executeWithoutActionTransaction(
            scope = scope,
            ownerUserId = userId,
            idempotencyKey = idempotencyKey,
            request = CheckoutRequestData(userId, priceId, promotionCode),
            responseType = CachedCheckoutResponse::class.java,
            responseStatus = 200,
        ) {
            checkoutWorkflow.createCheckout(userId, priceId, promotionCode, idempotencyKey)
        }

        return result.body.toCheckoutResponse()
    }

    fun validatePromotion(
        userId: UUID,
        priceId: Long,
        promotionCode: String?,
    ): PromotionValidationResult = checkoutTransactions.validatePromotion(userId, priceId, promotionCode)
}

data class CheckoutRequestData(
    val userId: UUID,
    val priceId: Long,
    val promotionCode: String?,
)

data class PromotionValidationResult(
    val promotionCode: String,
    val amountBeforeDiscount: Money,
    val amountAfterDiscount: Money,
    val discountAmount: Money,
    val promotionType: String,
    val redemptionMode: String,
    val freeDays: Int?,
)

/**
 * Plain data class for idempotency cache serialization.
 * Maps to sealed [CheckoutResponse] at the API boundary.
 */
data class CachedCheckoutResponse(
    val invoiceId: UUID,
    val paymentAttemptId: UUID,
    val gatewayUrl: String,
    val providerCode: String,
    val amount: Money,
    val failed: Boolean,
    val failureReason: String?,
)

fun CachedCheckoutResponse.toCheckoutResponse(): CheckoutResponse = if (failed) {
    CheckoutResponse.Failed(invoiceId, paymentAttemptId, failureReason ?: "Unknown error")
} else {
    CheckoutResponse.Success(invoiceId, paymentAttemptId, gatewayUrl, providerCode, amount)
}

sealed interface CheckoutResponse {
    data class Success(
        val invoiceId: UUID,
        val paymentAttemptId: UUID,
        val gatewayUrl: String,
        val providerCode: String,
        val amount: Money,
    ) : CheckoutResponse

    data class Failed(
        val invoiceId: UUID,
        val paymentAttemptId: UUID,
        val reason: String,
    ) : CheckoutResponse
}
