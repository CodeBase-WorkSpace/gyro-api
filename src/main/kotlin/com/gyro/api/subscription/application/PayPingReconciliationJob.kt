package com.gyro.api.subscription.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class PayPingReconciliationJob(
    private val paymentVerificationService: PaymentVerificationService,
    @Value("\${app.billing.payping.reconciliation-enabled:false}")
    private val enabled: Boolean,
    @Value("\${app.billing.payping.reconciliation-max-age:15m}")
    private val maxAge: Duration,
    @Value("\${app.billing.payping.reconciliation-max-retries:3}")
    private val maxRetries: Int,
    @Value("\${app.billing.payping.reconciliation-batch-size:50}")
    private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.billing.payping.reconciliation-delay:300000}")
    fun reconcilePendingPayPingAttempts() {
        if (!enabled) return
        try {
            paymentVerificationService.reconcilePendingAttempts(
                maxAge = maxAge,
                maxRetries = maxRetries,
                batchSize = batchSize,
            )
        } catch (ex: RuntimeException) {
            log.warn("event=payment_reconciliation outcome=failure reason={}", ex::class.simpleName)
        }
    }
}
