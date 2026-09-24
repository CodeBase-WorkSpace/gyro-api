package com.gyro.api.subscription.billing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Local browser-checkout simulator. Never register this provider outside the dev profile. */
class LocalDemoBillingProvider(
    private val serverPort: Int,
    private val apiBasePath: String,
) : BillingProvider {
    data class DemoCheckout(
        val request: CheckoutRequest,
        val providerCode: String,
        val providerRefId: String,
        val token: String,
    )

    enum class Outcome { SUCCESS, FAILED, PENDING }

    private val checkouts = ConcurrentHashMap<UUID, DemoCheckout>()
    private val outcomes = ConcurrentHashMap<String, Outcome>()

    override fun createCheckout(request: CheckoutRequest): CheckoutResult {
        val checkout = checkouts.computeIfAbsent(request.paymentAttemptId) { attemptId ->
            DemoCheckout(
                request = request,
                providerCode = "DEMO_$attemptId",
                providerRefId = "demo-$attemptId",
                token = UUID.randomUUID().toString() + UUID.randomUUID().toString(),
            )
        }
        val basePath = apiBasePath.trimEnd('/')
        return CheckoutResult.Success(
            gatewayUrl = "http://localhost:$serverPort$basePath/billing/demo/checkout/" +
                "${request.paymentAttemptId}?token=${checkout.token}",
            providerCode = checkout.providerCode,
            providerRequestId = "demo-${request.paymentAttemptId}",
        )
    }

    fun findCheckout(attemptId: UUID, token: String): DemoCheckout? {
        val checkout = checkouts[attemptId] ?: return null
        if (token.length != checkout.token.length) return null
        val actual = token.toByteArray(StandardCharsets.UTF_8)
        val expected = checkout.token.toByteArray(StandardCharsets.UTF_8)
        return checkout.takeIf { MessageDigest.isEqual(actual, expected) }
    }

    fun chooseOutcome(attemptId: UUID, token: String, outcome: Outcome): DemoCheckout? {
        val checkout = findCheckout(attemptId, token) ?: return null
        outcomes[checkout.providerRefId] = outcome
        return checkout
    }

    override fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult {
        val checkout = checkouts[request.paymentAttemptId]
            ?: return PaymentVerificationResult.Failed("DEMO_UNKNOWN_ATTEMPT", "Unknown local checkout.")
        if (request.providerCode != checkout.providerCode ||
            request.providerRefId != checkout.providerRefId ||
            request.clientRefId != checkout.request.clientRefId
        ) {
            return PaymentVerificationResult.Failed("DEMO_REFERENCE_MISMATCH", "Local checkout references do not match.")
        }
        if (request.expectedAmount.currency != checkout.request.amount.currency ||
            request.expectedAmount.amount.compareTo(checkout.request.amount.amount) != 0
        ) {
            return PaymentVerificationResult.Failed("DEMO_AMOUNT_MISMATCH", "Local checkout amount does not match.")
        }
        return when (outcomes[request.providerRefId]) {
            Outcome.SUCCESS -> PaymentVerificationResult.Confirmed(
                PaymentConfirmation(
                    providerRefId = request.providerRefId,
                    amount = request.expectedAmount,
                    verifiedAt = Instant.now(),
                    cardLast4 = null,
                    providerRequestId = "demo-verify-${request.paymentAttemptId}",
                ),
            )
            Outcome.FAILED -> PaymentVerificationResult.Failed("DEMO_DECLINED", "Synthetic payment declined.")
            Outcome.PENDING, null -> PaymentVerificationResult.Pending("DEMO_PENDING", "Synthetic payment is pending.")
        }
    }

    override fun listUnverifiedPayments(): List<UnverifiedPayment> = emptyList()
}
