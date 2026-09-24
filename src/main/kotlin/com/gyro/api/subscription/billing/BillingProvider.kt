package com.gyro.api.subscription.billing

import com.gyro.api.subscription.domain.Money
import java.time.Instant
import java.util.UUID

interface BillingProvider {
    /** Start a checkout. Returns a gateway URL for user redirect. */
    fun createCheckout(request: CheckoutRequest): CheckoutResult

    /** Server-to-server verification. Returns a typed outcome so uncertain provider states can be retried. */
    fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult

    /** Query unverified payments for reconciliation. */
    fun listUnverifiedPayments(): List<UnverifiedPayment>
}

data class CheckoutRequest(
    val paymentAttemptId: UUID,
    val clientRefId: String,
    val amount: Money,
    val returnUrl: String,
    val description: String?,
)

sealed class CheckoutResult {
    data class Success(
        val gatewayUrl: String,
        val providerCode: String,
        val providerRequestId: String? = null,
    ) : CheckoutResult()

    data class Failure(val providerErrorCode: String, val message: String?) : CheckoutResult()
}

data class VerifyPaymentRequest(
    val paymentAttemptId: UUID,
    val providerCode: String?,
    val providerRefId: String,
    val expectedAmount: Money,
    /** Local client reference; provider confirmation must echo it when this value is present. */
    val clientRefId: String? = null,
)

data class PaymentConfirmation(
    val providerRefId: String,
    val amount: Money,
    val verifiedAt: Instant,
    val cardLast4: String?,
    val providerRequestId: String? = null,
)

sealed class PaymentVerificationResult {
    data class Confirmed(val confirmation: PaymentConfirmation) : PaymentVerificationResult()
    data class Pending(
        val providerErrorCode: String,
        val message: String?,
        val providerHttpStatus: Int? = null,
        val providerRequestId: String? = null,
    ) : PaymentVerificationResult()
    data class Failed(
        val providerErrorCode: String,
        val message: String?,
        val providerHttpStatus: Int? = null,
        val providerRequestId: String? = null,
    ) : PaymentVerificationResult()
}

data class UnverifiedPayment(
    val providerRefId: String,
    val clientRefId: String,
    val amount: Money,
    val payDate: Instant?,
)
