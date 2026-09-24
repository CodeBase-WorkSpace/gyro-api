package com.gyro.api.subscription.billing

import com.gyro.api.subscription.domain.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class LocalDemoBillingProviderTest {
    private val provider = LocalDemoBillingProvider(8080, "/api/v1")
    private val attemptId = UUID.randomUUID()
    private val checkoutRequest = CheckoutRequest(
        paymentAttemptId = attemptId,
        clientRefId = "local-client-$attemptId",
        amount = Money(BigDecimal("125000.00"), "IRR"),
        returnUrl = "http://localhost:8080/api/v1/billing/payping/callback",
        description = "Local demo",
    )

    @Test
    fun `checkout grants a token scoped to one attempt and defaults to pending`() {
        val result = provider.createCheckout(checkoutRequest) as CheckoutResult.Success
        assertTrue(result.gatewayUrl.startsWith("http://localhost:8080/api/v1/billing/demo/checkout/$attemptId?token="))
        val token = result.gatewayUrl.substringAfter("token=")
        assertNull(provider.findCheckout(attemptId, "invalid-token"))
        assertNull(provider.findCheckout(UUID.randomUUID(), token))
        assertNotNull(provider.findCheckout(attemptId, token))
        assertTrue(provider.verifyPayment(verifyRequest(result.providerCode)) is PaymentVerificationResult.Pending)
    }

    @Test
    fun `selected outcomes drive verification without a provider network call`() {
        val result = provider.createCheckout(checkoutRequest) as CheckoutResult.Success
        val token = result.gatewayUrl.substringAfter("token=")
        assertNull(provider.chooseOutcome(attemptId, "invalid-token", LocalDemoBillingProvider.Outcome.SUCCESS))

        provider.chooseOutcome(attemptId, token, LocalDemoBillingProvider.Outcome.FAILED)
        assertTrue(provider.verifyPayment(verifyRequest(result.providerCode)) is PaymentVerificationResult.Failed)

        provider.chooseOutcome(attemptId, token, LocalDemoBillingProvider.Outcome.PENDING)
        assertTrue(provider.verifyPayment(verifyRequest(result.providerCode)) is PaymentVerificationResult.Pending)

        provider.chooseOutcome(attemptId, token, LocalDemoBillingProvider.Outcome.SUCCESS)
        val confirmed = provider.verifyPayment(verifyRequest(result.providerCode)) as PaymentVerificationResult.Confirmed
        assertEquals(checkoutRequest.amount, confirmed.confirmation.amount)
        assertEquals("demo-$attemptId", confirmed.confirmation.providerRefId)
    }

    @Test
    fun `verification rejects mismatched references and amounts`() {
        val result = provider.createCheckout(checkoutRequest) as CheckoutResult.Success
        val token = result.gatewayUrl.substringAfter("token=")
        provider.chooseOutcome(attemptId, token, LocalDemoBillingProvider.Outcome.SUCCESS)

        assertTrue(provider.verifyPayment(verifyRequest("wrong-code")) is PaymentVerificationResult.Failed)
        assertTrue(
            provider.verifyPayment(
                verifyRequest(result.providerCode).copy(expectedAmount = Money(BigDecimal("1.00"), "IRR")),
            ) is PaymentVerificationResult.Failed,
        )
    }

    private fun verifyRequest(code: String) = VerifyPaymentRequest(
        paymentAttemptId = attemptId,
        providerCode = code,
        providerRefId = "demo-$attemptId",
        expectedAmount = checkoutRequest.amount,
        clientRefId = checkoutRequest.clientRefId,
    )
}
