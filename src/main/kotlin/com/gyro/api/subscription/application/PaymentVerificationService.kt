package com.gyro.api.subscription.application

import com.gyro.api.common.error.ForbiddenResourceException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.request.RequestIds
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.billing.BillingProvider
import com.gyro.api.subscription.billing.PaymentConfirmation
import com.gyro.api.subscription.billing.PaymentVerificationResult
import com.gyro.api.subscription.billing.VerifyPaymentRequest
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.InvoiceRepository
import com.gyro.api.subscription.infrastructure.PaymentAttemptRepository
import com.gyro.api.subscription.infrastructure.PaymentEventRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.util.*

@Service
class PaymentVerificationService(
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val invoiceRepository: InvoiceRepository,
    private val paymentEventRepository: PaymentEventRepository,
    private val billingProvider: BillingProvider,
    private val paymentTransactions: PaymentVerificationTransactionService,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class VerificationOutcome(
        val kind: PaymentReturnKind,
        val reason: String? = null,
        val providerHttpStatus: Int? = null,
        val providerRequestId: String? = null,
    )

    fun handlePayPingReturn(
        authenticatedUserId: UUID,
        command: PayPingCallbackCommand,
    ): PaymentReturnStatus {
        log.debug(
            "event=payping_return_received user_id={} code={} ref_id={} client_ref_id={} has_card_number={} has_card_hash_pan={}",
            authenticatedUserId,
            command.code,
            command.refId,
            command.clientRefId,
            !command.cardNumber.isNullOrBlank(),
            !command.cardHashPan.isNullOrBlank(),
        )
        if (command.refId.isNullOrBlank() && command.clientRefId.isNullOrBlank()) {
            return PaymentReturnStatus.abandoned()
        }

        val attempt = command.clientRefId
            ?.takeIf { it.isNotBlank() }
            ?.let { paymentAttemptRepository.findByClientRefId(it).orElse(null) }
            ?: return PaymentReturnStatus.supportNeeded(
                requestId = RequestIds.current(),
                message = "Payment attempt could not be matched.",
            )

        val invoice = invoiceRepository.findById(attempt.invoiceId)
            .orElseThrow { ResourceNotFoundException("Invoice") }
        if (invoice.userId != authenticatedUserId) {
            throw ForbiddenResourceException("payment attempt")
        }

        return ingestAndVerify(command)
    }

    fun ingestPayPingCallback(command: PayPingCallbackCommand): PaymentReturnStatus {
        log.debug(
            "event=payping_callback_received code={} ref_id={} client_ref_id={} has_card_number={} has_card_hash_pan={}",
            command.code,
            command.refId,
            command.clientRefId,
            !command.cardNumber.isNullOrBlank(),
            !command.cardHashPan.isNullOrBlank(),
        )
        if (command.refId.isNullOrBlank() && command.clientRefId.isNullOrBlank()) {
            return PaymentReturnStatus.abandoned()
        }
        return ingestAndVerify(command)
    }

    fun statusForAttempt(authenticatedUserId: UUID, paymentAttemptId: UUID): PaymentReturnStatus {
        val attempt = paymentAttemptRepository.findById(paymentAttemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        val invoice = invoiceRepository.findById(attempt.invoiceId)
            .orElseThrow { ResourceNotFoundException("Invoice") }
        if (invoice.userId != authenticatedUserId) {
            throw ForbiddenResourceException("payment attempt")
        }
        return statusFromAttempt(attempt, invoice)
    }

    fun reconcile(command: PaymentReconciliationCommand): PaymentReconciliationSummary {
        val localCandidates = resolveReconciliationCandidates(command)
        var verified = 0
        var pending = 0
        var failed = 0

        localCandidates.forEach { attempt ->
            val refId = attempt.providerRefId ?: return@forEach
            when (verifyAttempt(attempt.id!!, refId, cardLast4 = null)) {
                PaymentReturnKind.SUCCESS -> verified += 1
                PaymentReturnKind.PENDING -> pending += 1
                PaymentReturnKind.FAILED, PaymentReturnKind.SUPPORT_NEEDED -> failed += 1
                else -> pending += 1
            }
        }

        billingProvider.listUnverifiedPayments().forEach { providerPayment ->
            val attempt = paymentAttemptRepository.findByClientRefId(providerPayment.clientRefId).orElse(null)
                ?: return@forEach
            if (command.paymentAttemptId != null && attempt.id != command.paymentAttemptId) return@forEach
            val expected = attempt.amount.toPayPingComparableAmount()
            val actual = providerPayment.amount.toPayPingComparableAmount()
            if (expected.compareTo(actual) != 0) {
                paymentTransactions.markVerificationFailed(
                    attemptId = attempt.id!!,
                    providerRefId = providerPayment.providerRefId,
                    reason = "AMOUNT_MISMATCH",
                )
                failed += 1
            } else {
                when (verifyAttempt(attempt.id!!, providerPayment.providerRefId, cardLast4 = null)) {
                    PaymentReturnKind.SUCCESS -> verified += 1
                    PaymentReturnKind.PENDING -> pending += 1
                    PaymentReturnKind.FAILED, PaymentReturnKind.SUPPORT_NEEDED -> failed += 1
                    else -> pending += 1
                }
            }
        }

        log.info(
            "event=payping_reconciliation outcome=completed candidates={} verified={} pending={} failed={}",
            localCandidates.size,
            verified,
            pending,
            failed,
        )
        recordReconciliationMetrics(localCandidates.size, verified, pending, failed)
        return PaymentReconciliationSummary(localCandidates.size, verified, pending, failed)
    }

    /**
     * Scheduled reconciliation: retries verification for recent pending attempts that already
     * carry a provider ref. Terminal provider outcomes stop retries permanently; the retry
     * limit and age window bound how often PayPing is called for attempts that stay pending.
     */
    fun reconcilePendingAttempts(maxAge: Duration, maxRetries: Int, batchSize: Int): PaymentReconciliationSummary {
        val boundedMaxAge = minOf(maxAge, MAX_PAYPING_RECONCILIATION_AGE)
        val threshold = timeProvider.now().minus(boundedMaxAge)
        val expired = paymentAttemptRepository.findExpiredReconciliationCandidates(
            threshold,
            PageRequest.of(0, batchSize),
        )
        val remainingBatchSize = (batchSize - expired.size).coerceAtLeast(0)
        val candidates = if (remainingBatchSize > 0) {
            paymentAttemptRepository.findReconciliationCandidates(threshold, PageRequest.of(0, remainingBatchSize))
        } else {
            emptyList()
        }
        var verified = 0
        var pending = 0
        var failed = 0

        expired.forEach { attempt ->
            val terminalized = paymentTransactions.markReconciliationTerminal(
                attempt.id!!,
                reason = "RECONCILIATION_MAX_AGE_EXCEEDED",
            )
            if (terminalized.status == PaymentAttemptStatus.STALE) {
                failed += 1
                log.info(
                    "event=payment_reconciliation payment_attempt_id={} invoice_id={} outcome=reversed reason=max_age_exceeded max_age_minutes={}",
                    attempt.id,
                    attempt.invoiceId,
                    boundedMaxAge.toMinutes(),
                )
            }
        }

        candidates.forEach { attempt ->
            val attemptId = attempt.id!!
            val refId = attempt.providerRefId ?: return@forEach
            val previousTries = paymentEventRepository.findByPaymentAttemptId(attemptId)
                .count { it.eventType == RECONCILIATION_EVENT_TYPE }
            if (previousTries >= maxRetries) {
                val terminalized = paymentTransactions.markReconciliationTerminal(
                    attempt.id!!,
                    reason = "RECONCILIATION_RETRY_EXHAUSTED",
                )
                if (terminalized.status == PaymentAttemptStatus.STALE) failed += 1
                log.info(
                    "event=payment_reconciliation payment_attempt_id={} invoice_id={} attempt={} outcome={} reason=retry_limit_reached",
                    attemptId,
                    attempt.invoiceId,
                    previousTries,
                    if (terminalized.status == PaymentAttemptStatus.STALE) "reversed" else "state_changed",
                )
                return@forEach
            }
            recordPaymentEvent(
                attempt = attempt,
                eventType = RECONCILIATION_EVENT_TYPE,
                providerRefId = refId,
                summary = mapOf("outcome" to "attempted", "try" to (previousTries + 1).toString()),
            )
            val outcome = verifyAttemptDetailed(attemptId, refId, cardLast4 = null)
            when (outcome.kind) {
                PaymentReturnKind.SUCCESS -> verified += 1
                PaymentReturnKind.PENDING -> pending += 1
                else -> failed += 1
            }
            log.info(
                "event=payment_reconciliation payment_attempt_id={} invoice_id={} attempt={} outcome={} provider_status={} provider_error_code={}",
                attemptId,
                attempt.invoiceId,
                previousTries + 1,
                when (outcome.kind) {
                    PaymentReturnKind.SUCCESS -> "verified"
                    PaymentReturnKind.PENDING -> "pending"
                    else -> "failed"
                },
                outcome.providerHttpStatus,
                outcome.reason,
            )
        }

        val totalCandidates = expired.size + candidates.size
        log.info(
            "event=payment_reconciliation outcome=completed candidates={} verified={} pending={} failed={}",
            totalCandidates,
            verified,
            pending,
            failed,
        )
        recordReconciliationMetrics(totalCandidates, verified, pending, failed)
        return PaymentReconciliationSummary(totalCandidates, verified, pending, failed)
    }

    private fun ingestAndVerify(command: PayPingCallbackCommand): PaymentReturnStatus {
        val clientRefId = command.clientRefId?.takeIf { it.isNotBlank() }
            ?: return PaymentReturnStatus.supportNeeded(RequestIds.current(), "Missing client reference.")
        val refId = command.refId?.takeIf { it.isNotBlank() }
            ?: return PaymentReturnStatus.abandoned(clientRefId = clientRefId)
        val normalizedCommand = command.copy(clientRefId = clientRefId, refId = refId)
        val attempt = try {
            paymentTransactions.prepareCallback(normalizedCommand)
        } catch (ex: DataIntegrityViolationException) {
            if (!ex.isDuplicateProviderReference()) throw ex
            // Handle the unique provider-reference race only after the failed transaction has
            // rolled back. Continuing in the original transaction would leave it rollback-only.
            paymentTransactions.rejectDuplicateProviderReference(normalizedCommand)
        }
            ?: return PaymentReturnStatus.supportNeeded(RequestIds.current(), "Payment attempt could not be matched.")

        val attemptId = attempt.id!!
        return verifyAttempt(attemptId, refId, command.safeCardLast4())
            .let { statusFromAttempt(paymentAttemptRepository.findById(attemptId).orElse(attempt)) }
    }

    private fun verifyAttempt(attemptId: UUID, providerRefId: String, cardLast4: String?): PaymentReturnKind {
        return verifyAttemptDetailed(attemptId, providerRefId, cardLast4).kind
    }

    private fun verifyAttemptDetailed(attemptId: UUID, providerRefId: String, cardLast4: String?): VerificationOutcome {
        val attempt = paymentAttemptRepository.findById(attemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        val invoice = invoiceRepository.findById(attempt.invoiceId)
            .orElseThrow { ResourceNotFoundException("Invoice") }

        if (attempt.status == PaymentAttemptStatus.VERIFIED && invoice.status == InvoiceStatus.PAID) {
            return VerificationOutcome(PaymentReturnKind.SUCCESS, reason = "ALREADY_VERIFIED")
        }
        if (attempt.status == PaymentAttemptStatus.CANCELLED) {
            return VerificationOutcome(PaymentReturnKind.FAILED, reason = "ATTEMPT_CANCELLED")
        }
        if (attempt.status == PaymentAttemptStatus.FAILED &&
            (invoice.status != InvoiceStatus.OPEN || attempt.providerRefId != providerRefId || !failedAttemptCanBeRetried(attempt))
        ) {
            return VerificationOutcome(PaymentReturnKind.FAILED, reason = "ATTEMPT_ALREADY_FAILED")
        }
        if (invoice.status != InvoiceStatus.OPEN && invoice.status != InvoiceStatus.PAID) {
            paymentTransactions.markVerificationFailed(attempt.id!!, providerRefId, "INVOICE_NOT_OPEN")
            return VerificationOutcome(PaymentReturnKind.FAILED, reason = "INVOICE_NOT_OPEN")
        }

        log.info(
            "event=payping_verify stage=request payment_attempt_id={} invoice_id={} provider_ref_suffix={} client_ref_hash={} amount={} currency={}",
            attempt.id,
            invoice.id,
            providerRefId.safeSuffix(),
            attempt.clientRefId.safeCorrelationHash(),
            attempt.amount.amount,
            attempt.amount.currency,
        )

        val outcome = billingProvider.verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = attempt.id!!,
                providerCode = attempt.providerCode,
                providerRefId = providerRefId,
                expectedAmount = attempt.amount,
                clientRefId = attempt.clientRefId,
            ),
        )

        val result = when (outcome) {
            is PaymentVerificationResult.Confirmed -> {
                val confirmation = outcome.confirmation.copy(
                    cardLast4 = cardLast4 ?: outcome.confirmation.cardLast4,
                )
                val saved = paymentTransactions.applyVerifiedPayment(attempt.id!!, providerRefId, confirmation)
                if (saved.status == PaymentAttemptStatus.VERIFIED) {
                    recordVerificationMetric("verified")
                    VerificationOutcome(PaymentReturnKind.SUCCESS, providerRequestId = outcome.confirmation.providerRequestId)
                } else {
                    recordVerificationMetric("failed")
                    VerificationOutcome(PaymentReturnKind.FAILED, reason = "LOCAL_CONFIRMATION_REJECTED")
                }
            }
            is PaymentVerificationResult.Pending -> {
                paymentTransactions.markVerificationPending(attempt.id!!, providerRefId, outcome.providerErrorCode)
                recordVerificationMetric("pending")
                VerificationOutcome(
                    PaymentReturnKind.PENDING,
                    reason = outcome.providerErrorCode,
                    providerHttpStatus = outcome.providerHttpStatus,
                    providerRequestId = outcome.providerRequestId,
                )
            }
            is PaymentVerificationResult.Failed -> {
                paymentTransactions.markVerificationFailed(attempt.id!!, providerRefId, outcome.providerErrorCode)
                recordVerificationMetric("failed")
                VerificationOutcome(
                    PaymentReturnKind.FAILED,
                    reason = outcome.providerErrorCode,
                    providerHttpStatus = outcome.providerHttpStatus,
                    providerRequestId = outcome.providerRequestId,
                )
            }
        }

        log.info(
            "event=payping_verify stage=response outcome={} status={} payment_attempt_id={} invoice_id={} provider_ref_suffix={} provider_error_code={} provider_request_id={}",
            when (result.kind) {
                PaymentReturnKind.SUCCESS -> "success"
                PaymentReturnKind.PENDING -> "pending"
                else -> "failed"
            },
            result.providerHttpStatus,
            attempt.id,
            invoice.id,
            providerRefId.safeSuffix(),
            result.reason,
            result.providerRequestId,
        )
        return result
    }

    fun applyVerifiedPayment(
        attemptId: UUID,
        providerRefId: String,
        confirmation: PaymentConfirmation,
    ): PaymentAttempt = paymentTransactions.applyVerifiedPayment(attemptId, providerRefId, confirmation)

    private fun resolveReconciliationCandidates(command: PaymentReconciliationCommand): List<PaymentAttempt> {
        command.paymentAttemptId?.let { id ->
            return listOf(paymentAttemptRepository.findById(id).orElseThrow { ResourceNotFoundException("PaymentAttempt") })
        }
        command.invoiceId?.let { invoiceId ->
            return paymentAttemptRepository.findByInvoiceId(invoiceId)
                .filter { it.status == PaymentAttemptStatus.PENDING || it.status == PaymentAttemptStatus.VERIFY_PENDING }
        }
        command.userId?.let { userId ->
            return invoiceRepository.findByUserIdAndStatus(userId, InvoiceStatus.OPEN)
                .flatMap { paymentAttemptRepository.findByInvoiceId(it.id!!) }
                .filter { it.status == PaymentAttemptStatus.PENDING || it.status == PaymentAttemptStatus.VERIFY_PENDING }
        }
        return paymentAttemptRepository.findLocallyVerifiableAttempts()
    }

    private fun statusFromAttempt(attempt: PaymentAttempt, invoice: Invoice? = null): PaymentReturnStatus {
        val resolvedInvoice = invoice ?: invoiceRepository.findById(attempt.invoiceId).orElse(null)
        return when {
            attempt.status == PaymentAttemptStatus.VERIFIED && resolvedInvoice?.status == InvoiceStatus.PAID ->
                PaymentReturnStatus.success(attempt, RequestIds.current())
            attempt.status == PaymentAttemptStatus.FAILED ->
                PaymentReturnStatus.failed(attempt, RequestIds.current())
            attempt.status == PaymentAttemptStatus.VERIFY_PENDING || attempt.status == PaymentAttemptStatus.PENDING ->
                PaymentReturnStatus.pending(attempt, RequestIds.current())
            else -> PaymentReturnStatus.supportNeeded(RequestIds.current(), "Payment requires support review.")
        }
    }

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

    private fun failedAttemptCanBeRetried(attempt: PaymentAttempt): Boolean {
        return paymentEventRepository.findByPaymentAttemptId(attempt.id!!)
            .any { event ->
                event.eventType == "PAYPING_VERIFICATION_FAILED" &&
                    event.safeSummary?.contains("PAYPING_VERIFY_REJECTED") == true
            }
    }

    private fun PayPingCallbackCommand.safeCardLast4(): String? {
        return cardNumber
            ?.filter(Char::isDigit)
            ?.takeIf { it.length >= 4 }
            ?.takeLast(4)
    }

    private fun Money.toPayPingComparableAmount(): BigDecimal {
        val tomanAmount = when (currency.uppercase()) {
            "IRR" -> amount.divide(BigDecimal.TEN)
            "IRT" -> amount
            else -> amount
        }
        return tomanAmount.setScale(0, RoundingMode.UNNECESSARY)
    }

    private fun String.safeSuffix(): String = "***${takeLast(SAFE_IDENTIFIER_SUFFIX_LENGTH)}"

    private fun String.safeCorrelationHash(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(SAFE_CORRELATION_HASH_LENGTH)
    }

    private fun DataIntegrityViolationException.isDuplicateProviderReference(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { cause ->
                cause.message?.contains(PROVIDER_REFERENCE_UNIQUE_INDEX, ignoreCase = true) == true
            }

    private companion object {
        private const val RECONCILIATION_EVENT_TYPE = "PAYPING_RECONCILIATION_ATTEMPT"
        private const val PROVIDER_REFERENCE_UNIQUE_INDEX = "uk_payment_attempt_provider_ref"
        private val MAX_PAYPING_RECONCILIATION_AGE = Duration.ofMinutes(15)
        private const val SAFE_IDENTIFIER_SUFFIX_LENGTH = 4
        private const val SAFE_CORRELATION_HASH_LENGTH = 12
    }

    private fun recordVerificationMetric(outcome: String) {
        meterRegistryProvider.ifAvailable { registry ->
            Counter.builder("gyro.billing.payping.verification")
                .tag("outcome", outcome)
                .register(registry)
                .increment()
        }
    }

    private fun recordReconciliationMetrics(candidates: Int, verified: Int, pending: Int, failed: Int) {
        meterRegistryProvider.ifAvailable { registry ->
            mapOf(
                "candidates" to candidates,
                "verified" to verified,
                "pending" to pending,
                "failed" to failed,
            ).forEach { (outcome, count) ->
                Counter.builder("gyro.billing.payping.reconciliation")
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment(count.toDouble())
            }
        }
    }
}

data class PayPingCallbackCommand(
    val code: String?,
    val refId: String?,
    val clientRefId: String?,
    val cardNumber: String? = null,
    val cardHashPan: String? = null,
)

data class PaymentReconciliationCommand(
    val userId: UUID? = null,
    val invoiceId: UUID? = null,
    val paymentAttemptId: UUID? = null,
)

data class PaymentReconciliationSummary(
    val candidates: Int,
    val verified: Int,
    val pending: Int,
    val failed: Int,
)

data class PaymentReturnStatus(
    val state: PaymentReturnKind,
    val paymentAttemptId: UUID? = null,
    val invoiceId: UUID? = null,
    val providerCode: String? = null,
    val providerRefId: String? = null,
    val clientRefId: String? = null,
    val requestId: String? = null,
    val message: String,
) {
    companion object {
        fun abandoned(clientRefId: String? = null) = PaymentReturnStatus(
            state = PaymentReturnKind.ABANDONED,
            clientRefId = clientRefId,
            requestId = RequestIds.current(),
            message = "Payment was not completed.",
        )

        fun pending(attempt: PaymentAttempt, requestId: String?) = PaymentReturnStatus(
            state = PaymentReturnKind.PENDING,
            paymentAttemptId = attempt.id,
            invoiceId = attempt.invoiceId,
            providerCode = attempt.providerCode,
            providerRefId = attempt.providerRefId,
            clientRefId = attempt.clientRefId,
            requestId = requestId,
            message = "Payment verification is pending.",
        )

        fun success(attempt: PaymentAttempt, requestId: String?) = PaymentReturnStatus(
            state = PaymentReturnKind.SUCCESS,
            paymentAttemptId = attempt.id,
            invoiceId = attempt.invoiceId,
            providerCode = attempt.providerCode,
            providerRefId = attempt.providerRefId,
            clientRefId = attempt.clientRefId,
            requestId = requestId,
            message = "Payment was verified.",
        )

        fun failed(attempt: PaymentAttempt, requestId: String?) = PaymentReturnStatus(
            state = PaymentReturnKind.FAILED,
            paymentAttemptId = attempt.id,
            invoiceId = attempt.invoiceId,
            providerCode = attempt.providerCode,
            providerRefId = attempt.providerRefId,
            clientRefId = attempt.clientRefId,
            requestId = requestId,
            message = "Payment could not be verified.",
        )

        fun supportNeeded(requestId: String?, message: String) = PaymentReturnStatus(
            state = PaymentReturnKind.SUPPORT_NEEDED,
            requestId = requestId,
            message = message,
        )
    }
}

enum class PaymentReturnKind {
    CONFIRMING,
    SUCCESS,
    PENDING,
    ABANDONED,
    FAILED,
    SUPPORT_NEEDED,
}
