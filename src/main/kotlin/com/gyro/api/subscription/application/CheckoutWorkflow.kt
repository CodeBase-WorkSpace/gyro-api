package com.gyro.api.subscription.application

import com.gyro.api.subscription.billing.BillingProvider
import com.gyro.api.subscription.billing.CheckoutRequest
import com.gyro.api.subscription.billing.CheckoutResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class CheckoutWorkflow(
    private val checkoutTransactions: CheckoutTransactions,
    private val billingProvider: BillingProvider,
    @Value("\${app.billing.return-url:https://api.gyrohealth.ir/api/v1/billing/payping/callback}")
    private val returnUrl: String,
) {
    /**
     * Reject an accidental surrounding transaction while checkout performs its independently
     * committed database phases and provider I/O. The transactional collaborator is invoked
     * through its Spring proxy, so no database transaction is active during the network call.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun createCheckout(
        userId: UUID,
        priceId: Long,
        promotionCode: String?,
        idempotencyKey: String?,
    ): CachedCheckoutResponse {
        val prepared = checkoutTransactions.prepareCheckout(userId, priceId, promotionCode, idempotencyKey)
        val providerResult = billingProvider.createCheckout(
            CheckoutRequest(
                paymentAttemptId = prepared.paymentAttemptId,
                clientRefId = prepared.clientRefId,
                amount = prepared.checkoutAmount,
                returnUrl = returnUrl,
                description = "Gyro Premium Subscription",
            ),
        )

        return when (providerResult) {
            is CheckoutResult.Success -> {
                checkoutTransactions.recordProviderSuccess(
                    paymentAttemptId = prepared.paymentAttemptId,
                    providerCode = providerResult.providerCode,
                    providerRequestId = providerResult.providerRequestId,
                )
                CachedCheckoutResponse(
                    invoiceId = prepared.invoiceId,
                    paymentAttemptId = prepared.paymentAttemptId,
                    gatewayUrl = providerResult.gatewayUrl,
                    providerCode = providerResult.providerCode,
                    amount = prepared.checkoutAmount,
                    failed = false,
                    failureReason = null,
                )
            }

            is CheckoutResult.Failure -> {
                checkoutTransactions.recordProviderFailure(prepared.invoiceId, prepared.paymentAttemptId)
                log.warn(
                    "Checkout provider failed: code={}, message={}, invoiceId={}, paymentAttemptId={}",
                    providerResult.providerErrorCode,
                    providerResult.message,
                    prepared.invoiceId,
                    prepared.paymentAttemptId,
                )
                CachedCheckoutResponse(
                    invoiceId = prepared.invoiceId,
                    paymentAttemptId = prepared.paymentAttemptId,
                    gatewayUrl = "",
                    providerCode = "",
                    amount = prepared.checkoutAmount,
                    failed = true,
                    failureReason = providerResult.message ?: providerResult.providerErrorCode,
                )
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CheckoutWorkflow::class.java)
    }
}
