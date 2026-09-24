package com.gyro.api.subscription.application

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.request.RequestIds
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.billing.PaymentConfirmation
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationOutboxWriter
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationPayload
import com.gyro.api.subscription.infrastructure.InvoiceRepository
import com.gyro.api.subscription.infrastructure.PaymentAttemptRepository
import com.gyro.api.subscription.infrastructure.PaymentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.*

/**
 * Owns the local database phases of PayPing verification.
 *
 * Provider I/O remains in [PaymentVerificationService], so row locks and persistence contexts
 * never span a network call. Each method re-reads and locks the mutable payment attempt before
 * applying a transition, preventing a late pending or failed result from regressing a verified
 * payment.
 */
@Service
class PaymentVerificationTransactionService(
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentEventRepository: PaymentEventRepository,
    private val userRepository: UserRepository,
    private val promotionService: PromotionService,
    private val affiliateCommissionService: AffiliateCommissionService,
    private val lifecycleService: SubscriptionLifecycleService,
    private val entitlementCacheService: EntitlementCacheService,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
    private val paymentNotifications: PaymentVerifiedNotificationOutboxWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun prepareCallback(command: PayPingCallbackCommand): PaymentAttempt? {
        val attempt = paymentAttemptRepository.findByClientRefIdForUpdate(command.clientRefId!!).orElse(null)
            ?: return null
        val invoice = invoiceRepository.findById(attempt.invoiceId)
            .orElseThrow { ResourceNotFoundException("Invoice") }

        val existingByRef = paymentAttemptRepository
            .findByProviderAndProviderRefId(PaymentProvider.PAYPING, command.refId!!)
            .orElse(null)
        if (existingByRef != null && existingByRef.id != attempt.id) {
            recordPaymentEvent(
                attempt = attempt,
                eventType = "PAYPING_CALLBACK_REPLAY_REJECTED",
                providerRefId = command.refId,
                summary = command.safeSummary("replay_rejected"),
            )
            attempt.status = PaymentAttemptStatus.FAILED
            attempt.updatedAt = timeProvider.now()
            return paymentAttemptRepository.save(attempt)
        }

        recordPaymentEvent(
            attempt = attempt,
            eventType = "PAYPING_CALLBACK_RECEIVED",
            providerRefId = command.refId,
            summary = command.safeSummary("received"),
        )

        if (attempt.status == PaymentAttemptStatus.VERIFIED || invoice.status == InvoiceStatus.PAID) {
            return attempt
        }
        if (attempt.status == PaymentAttemptStatus.FAILED || attempt.status == PaymentAttemptStatus.CANCELLED) {
            return attempt
        }

        attempt.providerCode = command.code ?: attempt.providerCode
        attempt.providerRefId = command.refId
        attempt.status = PaymentAttemptStatus.VERIFY_PENDING
        attempt.updatedAt = timeProvider.now()
        return paymentAttemptRepository.saveAndFlush(attempt)
    }

    @Transactional
    fun rejectDuplicateProviderReference(command: PayPingCallbackCommand): PaymentAttempt? {
        val attempt = paymentAttemptRepository.findByClientRefIdForUpdate(command.clientRefId!!).orElse(null)
            ?: return null
        if (attempt.status == PaymentAttemptStatus.VERIFIED) return attempt

        log.warn(
            "event=payping_callback_replay_rejected outcome=duplicate_provider_ref provider={} payment_attempt_id={}",
            PaymentProvider.PAYPING,
            attempt.id,
        )
        recordPaymentEvent(
            attempt = attempt,
            eventType = "PAYPING_CALLBACK_REPLAY_REJECTED",
            providerRefId = command.refId,
            summary = command.safeSummary("duplicate_provider_ref"),
        )
        attempt.providerRefId = null
        attempt.status = PaymentAttemptStatus.FAILED
        attempt.updatedAt = timeProvider.now()
        return paymentAttemptRepository.save(attempt)
    }

    @Transactional
    fun markReconciliationTerminal(attemptId: UUID, reason: String): PaymentAttempt {
        val attempt = paymentAttemptRepository.findByIdForUpdate(attemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        if (attempt.status != PaymentAttemptStatus.PENDING && attempt.status != PaymentAttemptStatus.VERIFY_PENDING) {
            return attempt
        }

        attempt.status = PaymentAttemptStatus.STALE
        attempt.updatedAt = timeProvider.now()
        val saved = paymentAttemptRepository.save(attempt)
        promotionService.releaseInvoiceReservation(attempt.invoiceId)
        recordPaymentEvent(
            attempt = saved,
            eventType = RECONCILIATION_TERMINAL_EVENT_TYPE,
            providerRefId = attempt.providerRefId,
            summary = mapOf(
                "outcome" to "reversed",
                "reason" to reason,
                "providerBehavior" to "automatic_reversal_after_verification_window",
            ),
        )
        return saved
    }

    @Transactional
    fun applyVerifiedPayment(
        attemptId: UUID,
        providerRefId: String,
        confirmation: PaymentConfirmation,
    ): PaymentAttempt {
        val attempt = paymentAttemptRepository.findByIdForUpdate(attemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        val invoiceOwnerId = invoiceRepository.findUserIdById(attempt.invoiceId)
            ?: throw ResourceNotFoundException("Invoice")

        // Serialize successful-payment transitions for one account. Locking only the invoice is
        // insufficient because two distinct affiliate invoices can verify concurrently.
        userRepository.findByIdForUpdate(invoiceOwnerId)
            .orElseThrow { ResourceNotFoundException("User") }
        val invoice = invoiceRepository.findByIdForUpdate(attempt.invoiceId)
            .orElseThrow { ResourceNotFoundException("Invoice") }

        // The provider call happens before this locked database phase, so concurrent callbacks can
        // both reach this method with an earlier non-terminal snapshot. Re-check under the row locks
        // to keep lifecycle side effects and the success audit event exactly-once.
        if (attempt.status == PaymentAttemptStatus.VERIFIED && invoice.status == InvoiceStatus.PAID) {
            return attempt
        }
        if (attempt.status == PaymentAttemptStatus.STALE || attempt.status == PaymentAttemptStatus.CANCELLED) {
            return attempt
        }

        if (attempt.amount != confirmation.amount) {
            return markVerificationFailedInCurrentTransaction(attempt, providerRefId, "AMOUNT_MISMATCH")
        }

        attempt.providerRefId = providerRefId
        attempt.providerRequestId = confirmation.providerRequestId ?: attempt.providerRequestId
        attempt.status = PaymentAttemptStatus.VERIFIED
        attempt.reversible = true
        attempt.updatedAt = timeProvider.now()
        val verifiedAttempt = paymentAttemptRepository.save(attempt)

        val paidInvoice = when (invoice.status) {
            InvoiceStatus.OPEN -> {
                invoice.status = InvoiceStatus.PAID
                invoice.updatedAt = timeProvider.now()
                val paid = invoiceRepository.save(invoice)
                val redemption = promotionService.redeemInvoicePromotion(invoice.id!!)
                affiliateCommissionService.createForPaidInvoice(paid, redemption)
                paid
            }

            InvoiceStatus.PAID -> invoice
            else -> return markVerificationFailedInCurrentTransaction(attempt, providerRefId, "INVOICE_NOT_OPEN")
        }

        lifecycleService.applyPaidInvoice(paidInvoice)
        entitlementCacheService.invalidate(paidInvoice.userId)
        recordPaymentEvent(
            attempt = verifiedAttempt,
            eventType = "PAYPING_VERIFICATION_SUCCEEDED",
            providerRefId = providerRefId,
            summary = mapOf(
                "outcome" to "verified",
                "hasCardLast4" to (!confirmation.cardLast4.isNullOrBlank()).toString(),
                "requestId" to RequestIds.current().orEmpty(),
            ),
        )
        paymentNotifications.write(
            PaymentVerifiedNotificationPayload(
                userId = paidInvoice.userId,
                paymentAttemptId = requireNotNull(verifiedAttempt.id),
                amount = verifiedAttempt.amount,
                occurredAt = timeProvider.now(),
                requestId = RequestIds.current().orEmpty().ifBlank { "payment-${verifiedAttempt.id}" },
            ),
        )
        return verifiedAttempt
    }

    @Transactional
    fun markVerificationPending(attemptId: UUID, providerRefId: String, reason: String): PaymentAttempt {
        val attempt = paymentAttemptRepository.findByIdForUpdate(attemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        if (attempt.isTerminal()) return attempt

        attempt.providerRefId = providerRefId
        attempt.status = PaymentAttemptStatus.VERIFY_PENDING
        attempt.updatedAt = timeProvider.now()
        val saved = paymentAttemptRepository.save(attempt)
        recordPaymentEvent(
            attempt = saved,
            eventType = "PAYPING_VERIFICATION_PENDING",
            providerRefId = providerRefId,
            summary = mapOf("outcome" to "pending", "reason" to reason),
        )
        return saved
    }

    @Transactional
    fun markVerificationFailed(attemptId: UUID, providerRefId: String, reason: String): PaymentAttempt {
        val attempt = paymentAttemptRepository.findByIdForUpdate(attemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        if (attempt.isTerminal()) return attempt
        return markVerificationFailedInCurrentTransaction(attempt, providerRefId, reason)
    }

    private fun markVerificationFailedInCurrentTransaction(
        attempt: PaymentAttempt,
        providerRefId: String,
        reason: String,
    ): PaymentAttempt {
        attempt.providerRefId = providerRefId
        attempt.status = PaymentAttemptStatus.FAILED
        attempt.updatedAt = timeProvider.now()
        val saved = paymentAttemptRepository.save(attempt)
        promotionService.releaseInvoiceReservation(attempt.invoiceId)
        recordPaymentEvent(
            attempt = saved,
            eventType = "PAYPING_VERIFICATION_FAILED",
            providerRefId = providerRefId,
            summary = mapOf("outcome" to "failed", "reason" to reason),
        )
        return saved
    }

    private fun PaymentAttempt.isTerminal(): Boolean =
        status == PaymentAttemptStatus.VERIFIED ||
                status == PaymentAttemptStatus.CANCELLED ||
                status == PaymentAttemptStatus.STALE

    private fun recordPaymentEvent(
        attempt: PaymentAttempt,
        eventType: String,
        providerRefId: String?,
        summary: Map<String, String>,
    ) {
        val safeJson = objectMapper.writeValueAsString(summary)
        paymentEventRepository.save(
            PaymentEvent(
                paymentAttemptId = attempt.id,
                provider = PaymentProvider.PAYPING.name,
                eventType = eventType,
                providerRefId = providerRefId,
                rawPayload = safeJson,
                safeSummary = safeJson,
                createdAt = timeProvider.now(),
            ),
        )
    }

    private fun PayPingCallbackCommand.safeSummary(outcome: String): Map<String, String> = buildMap {
        put("outcome", outcome)
        code?.takeIf { it.isNotBlank() }?.let { put("code", it) }
        refId?.takeIf { it.isNotBlank() }?.let { put("refId", it) }
        clientRefId?.takeIf { it.isNotBlank() }?.let { put("clientRefId", it) }
        put("hasCardNumber", (!cardNumber.isNullOrBlank()).toString())
        put("hasCardHashPan", (!cardHashPan.isNullOrBlank()).toString())
        safeCardLast4()?.let { put("cardLast4", it) }
        RequestIds.current()?.let { put("requestId", it) }
    }

    private fun PayPingCallbackCommand.safeCardLast4(): String? = cardNumber
        ?.filter(Char::isDigit)
        ?.takeIf { it.length >= 4 }
        ?.takeLast(4)

    private companion object {
        private const val RECONCILIATION_TERMINAL_EVENT_TYPE = "PAYPING_RECONCILIATION_TERMINATED"
    }
}
