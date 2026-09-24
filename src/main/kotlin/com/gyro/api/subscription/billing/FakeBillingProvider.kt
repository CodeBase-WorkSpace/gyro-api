package com.gyro.api.subscription.billing

import com.gyro.api.subscription.domain.Money
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for BillingProvider. Tracks all invocations for assertion.
 * Each createCheckout call returns a unique gateway URL and provider code.
 * Thread-safe for parallel test execution.
 */
class FakeBillingProvider : BillingProvider {

    private val checkoutCount = AtomicInteger(0)
    private val verifyCount = AtomicInteger(0)
    private val _checkouts = CopyOnWriteArrayList<CheckoutRequest>()
    private val _verifications = CopyOnWriteArrayList<VerifyPaymentRequest>()
    private val verificationResults = ConcurrentLinkedQueue<PaymentVerificationResult>()

    val checkouts: List<CheckoutRequest> get() = _checkouts.toList()
    val verifications: List<VerifyPaymentRequest> get() = _verifications.toList()

    override fun createCheckout(request: CheckoutRequest): CheckoutResult {
        val seq = checkoutCount.incrementAndGet()
        _checkouts.add(request)
        return CheckoutResult.Success(
            gatewayUrl = "https://pay.example.com/checkout/$seq-${request.paymentAttemptId}",
            providerCode = "FAKE_CODE_$seq",
            providerRequestId = "fake-request-$seq",
        )
    }

    override fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult {
        verifyCount.incrementAndGet()
        _verifications.add(request)
        verificationResults.poll()?.let { return it }
        return PaymentVerificationResult.Confirmed(
            PaymentConfirmation(
                providerRefId = request.providerRefId,
                amount = request.expectedAmount,
                verifiedAt = Instant.now(),
                cardLast4 = "1234",
                providerRequestId = "fake-verify-request",
            ),
        )
    }

    override fun listUnverifiedPayments(): List<UnverifiedPayment> = emptyList()

    fun checkoutInvocationCount(): Int = checkoutCount.get()
    fun verifyInvocationCount(): Int = verifyCount.get()
    fun enqueueVerificationResult(result: PaymentVerificationResult) {
        verificationResults.add(result)
    }

    fun reset() {
        checkoutCount.set(0)
        verifyCount.set(0)
        _checkouts.clear()
        _verifications.clear()
        verificationResults.clear()
    }
}
