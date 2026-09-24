package com.gyro.api.subscription.billing

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
@ConditionalOnMissingBean(BillingProvider::class)
class DisabledBillingProvider : BillingProvider {
    override fun createCheckout(request: CheckoutRequest): CheckoutResult {
        return CheckoutResult.Failure("BILLING_DISABLED", "Billing is disabled.")
    }

    override fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult {
        return PaymentVerificationResult.Pending("BILLING_DISABLED", "Billing is disabled.")
    }

    override fun listUnverifiedPayments(): List<UnverifiedPayment> = emptyList()
}
