package com.gyro.api.subscription.billing

import com.gyro.api.subscription.domain.Money
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(prefix = "app.billing.payping", name = ["enabled"], havingValue = "true")
class PayPingBillingProvider(
    private val properties: PayPingProperties,
    private val objectMapper: ObjectMapper,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>? = null,
) : BillingProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(properties.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(properties.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    override fun createCheckout(request: CheckoutRequest): CheckoutResult {
        if (properties.apiKey.isBlank()) {
            log.error("event=payping_create outcome=misconfigured reason=missing_api_key")
            return CheckoutResult.Failure("PAYPING_MISCONFIGURED", "PayPing is not configured.")
        }

        val payload = objectMapper.writeValueAsString(
            PayPingCreatePaymentRequest(
                amount = request.amount.toPayPingAmount(),
                returnUrl = request.returnUrl,
                description = request.description,
                clientRefId = request.clientRefId,
            ),
        )
        val httpRequest = payPingRequest("/v3/pay")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        logPayPingRequest("create", "/v3/pay", payload)

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val payPingRequestId = resolvePayPingRequestId(response.headers[PAYPING_REQUEST_ID_HEADER], responseBody)
                logPayPingResponse("create", "/v3/pay", response.code, payPingRequestId, responseBody)

                if (!response.isSuccessful) {
                    recordProviderRequestMetric("create", "rejected")
                    log.warn(
                        "event=payping_create outcome=failure status={} payping_request_id={}",
                        response.code,
                        payPingRequestId,
                    )
                    return CheckoutResult.Failure("PAYPING_CREATE_FAILED", "PayPing could not create checkout.")
                }

                val responseNode = objectMapper.readTree(responseBody)
                val paymentCode = (responseNode.get("code") ?: responseNode.get("paymentCode"))
                    ?.asString()
                    ?.trim()
                    .orEmpty()
                if (paymentCode.isBlank()) {
                    log.warn(
                        "event=payping_create outcome=failure reason=missing_payment_code payping_request_id={}",
                        payPingRequestId,
                    )
                    return CheckoutResult.Failure("PAYPING_MISSING_PAYMENT_CODE", "PayPing did not return a checkout code.")
                }
                val gatewayUrl = responseNode.get("url")?.asString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "${properties.baseUrl.trimEnd('/')}/v3/pay/start/$paymentCode"

                CheckoutResult.Success(
                    gatewayUrl = gatewayUrl,
                    providerCode = paymentCode,
                    providerRequestId = payPingRequestId,
                ).also { recordProviderRequestMetric("create", "succeeded") }
            }
        } catch (ex: IOException) {
            recordProviderRequestMetric("create", "unavailable")
            log.warn("event=payping_create outcome=provider_unavailable reason=io_exception")
            CheckoutResult.Failure("PAYPING_UNAVAILABLE", "PayPing is temporarily unavailable.")
        } catch (ex: RuntimeException) {
            recordProviderRequestMetric("create", "invalid_response")
            log.warn("event=payping_create outcome=failure reason=response_parse_failed")
            CheckoutResult.Failure("PAYPING_INVALID_RESPONSE", "PayPing returned an invalid response.")
        }
    }

    override fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult {
        if (properties.apiKey.isBlank()) {
            log.error("event=payping_verify outcome=misconfigured reason=missing_api_key")
            return PaymentVerificationResult.Pending("PAYPING_MISCONFIGURED", "PayPing is not configured.")
        }
        // PayPing v3 verify requires the numeric paymentRefId from the callback plus the
        // paymentCode issued at creation. Missing or malformed values can never verify.
        val paymentRefId = request.providerRefId.trim().toLongOrNull()
            ?: return PaymentVerificationResult.Failed(
                "PAYPING_PAYMENT_REF_INVALID",
                "PayPing payment reference is not a numeric paymentRefId.",
            )
        val paymentCode = request.providerCode?.trim()?.takeIf { it.isNotBlank() }
            ?: return PaymentVerificationResult.Failed(
                "PAYPING_PAYMENT_CODE_MISSING",
                "PayPing payment code is missing; verification is impossible.",
            )
        val expectedAmount = request.expectedAmount.toPayPingAmount()

        val payload = objectMapper.writeValueAsString(
            PayPingVerifyPaymentRequest(
                amount = expectedAmount,
                paymentCode = paymentCode,
                paymentRefId = paymentRefId,
            ),
        )
        val verifyUrl = "${properties.baseUrl.trimEnd('/')}/v3/pay/verify"
        val httpRequest = payPingRequest("/v3/pay/verify")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        log.info(
            "event=payping_verify stage=request payment_attempt_id={} provider_ref_suffix={} has_payment_code={} payment_code_suffix={} client_ref_hash={} amount={} currency=IRT verify_url={}",
            request.paymentAttemptId,
            paymentRefId.toString().safeSuffix(),
            true,
            paymentCode.safeSuffix(),
            request.clientRefId.safeCorrelationHash(),
            expectedAmount,
            verifyUrl,
        )
        logPayPingRequest("verify", "/v3/pay/verify", payload)

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val payPingRequestId = resolvePayPingRequestId(response.headers[PAYPING_REQUEST_ID_HEADER], responseBody)
                logPayPingResponse("verify", "/v3/pay/verify", response.code, payPingRequestId, responseBody)
                classifyVerifyResponse(request, response.code, responseBody, payPingRequestId)
                    .also { outcome ->
                        val (label, metric) = when (outcome) {
                            is PaymentVerificationResult.Confirmed -> "success" to "succeeded"
                            is PaymentVerificationResult.Pending -> "pending" to "pending"
                            is PaymentVerificationResult.Failed -> "failed" to "rejected"
                        }
                        recordProviderRequestMetric("verify", metric)
                        val problem = parseProblemDetails(responseBody)
                        log.info(
                            "event=payping_verify stage=response outcome={} status={} payment_attempt_id={} provider_ref_suffix={} provider_error_code={} provider_error_message={} provider_request_id={}",
                            label,
                            response.code,
                            request.paymentAttemptId,
                            paymentRefId.toString().safeSuffix(),
                            problem?.metaCode,
                            problem?.safeMessage,
                            payPingRequestId,
                        )
                    }
            }
        } catch (ex: IOException) {
            recordProviderRequestMetric("verify", "timeout")
            log.warn(
                "event=payping_verify stage=response outcome=pending payment_attempt_id={} reason=io_exception exception={}",
                request.paymentAttemptId,
                ex::class.simpleName,
            )
            PaymentVerificationResult.Pending("PAYPING_VERIFY_TIMEOUT", "PayPing verification timed out.")
        } catch (ex: RuntimeException) {
            recordProviderRequestMetric("verify", "invalid_response")
            log.warn(
                "event=payping_verify stage=response outcome=pending payment_attempt_id={} reason=response_processing_failed exception={}",
                request.paymentAttemptId,
                ex::class.simpleName,
            )
            PaymentVerificationResult.Pending("PAYPING_VERIFY_PENDING", "PayPing returned an unprocessable response.")
        }
    }

    /**
     * Maps PayPing v3 verify responses to typed outcomes:
     * 200 => confirmed (after amount/clientRefId cross-check mandated by PayPing docs),
     * 202/5xx/timeouts => retryable pending,
     * 409 metaData.code=110 => already verified (idempotent success),
     * 400/404 => terminal failure.
     */
    private fun classifyVerifyResponse(
        request: VerifyPaymentRequest,
        status: Int,
        responseBody: String,
        payPingRequestId: String?,
    ): PaymentVerificationResult {
        val expectedAmount = request.expectedAmount.toPayPingAmount()
        return when {
            status in 200..299 && status != 202 -> {
                val node = responseBody.takeIf { it.isNotBlank() }
                    ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
                    ?.takeIf { it.isObject }
                    ?: return invalidVerificationEvidence(status, payPingRequestId, "missing_or_malformed_body")
                val providerAmount = node.get("amount")
                    ?.takeIf { it.isNumber || it.isString }
                    ?.asString()
                    ?.toLongOrNull()
                val providerClientRefId = node?.get("clientRefId")?.asString()?.takeIf { it.isNotBlank() }
                when {
                    providerAmount == null -> invalidVerificationEvidence(status, payPingRequestId, "missing_amount")
                    request.clientRefId != null && providerClientRefId == null ->
                        invalidVerificationEvidence(status, payPingRequestId, "missing_client_ref_id")
                    providerAmount != expectedAmount.toLong() ->
                        PaymentVerificationResult.Failed(
                            "PAYPING_VERIFY_REJECTED_AMOUNT_MISMATCH",
                            "PayPing verified amount $providerAmount does not match expected $expectedAmount.",
                            status,
                            payPingRequestId,
                        )
                    request.clientRefId != null && providerClientRefId != request.clientRefId ->
                        PaymentVerificationResult.Failed(
                            "PAYPING_VERIFY_REJECTED_CLIENT_REF_MISMATCH",
                            "PayPing clientRefId does not match the local payment attempt.",
                            status,
                            payPingRequestId,
                        )
                    else -> PaymentVerificationResult.Confirmed(
                        PaymentConfirmation(
                            providerRefId = request.providerRefId,
                            amount = request.expectedAmount,
                            verifiedAt = Instant.now(),
                            cardLast4 = node?.get("cardNumber")?.asString()?.let(::maskedCardLast4),
                            providerRequestId = payPingRequestId,
                        ),
                    )
                }
            }
            status == 202 -> PaymentVerificationResult.Pending(
                "PAYPING_VERIFY_PROCESSING",
                "PayPing is still processing the verification.",
                status,
                payPingRequestId,
            )
            status == 409 -> classifyConflict(request, responseBody, payPingRequestId, expectedAmount)
            status == 400 -> {
                val problem = parseProblemDetails(responseBody)
                PaymentVerificationResult.Failed(
                    "PAYPING_VERIFY_REJECTED",
                    "PayPing rejected verification: code=${problem?.metaCode} ${problem?.safeMessage.orEmpty()}".trim(),
                    status,
                    payPingRequestId,
                )
            }
            status == 404 -> PaymentVerificationResult.Failed(
                "PAYPING_VERIFY_NOT_FOUND",
                "PayPing could not find the payment.",
                status,
                payPingRequestId,
            )
            else -> PaymentVerificationResult.Pending(
                "PAYPING_VERIFY_UNAVAILABLE",
                "PayPing verification is temporarily unavailable (HTTP $status).",
                status,
                payPingRequestId,
            )
        }
    }

    /** 409 + metaData.code=110 means the payment was already verified; PayPing echoes the payment details. */
    private fun classifyConflict(
        request: VerifyPaymentRequest,
        responseBody: String,
        payPingRequestId: String?,
        expectedAmount: Int,
    ): PaymentVerificationResult {
        val problem = parseProblemDetails(responseBody)
        if (problem?.metaCode != ALREADY_VERIFIED_META_CODE) {
            return PaymentVerificationResult.Pending(
                "PAYPING_VERIFY_CONFLICT",
                "PayPing reported an unexpected verification conflict: code=${problem?.metaCode}.",
                409,
                payPingRequestId,
            )
        }
        val message = runCatching { objectMapper.readTree(responseBody).path("metaData").path("message") }
            .getOrNull()
            ?.takeIf { it.isObject }
            ?: return invalidVerificationEvidence(409, payPingRequestId, "missing_already_verified_message")
        val providerAmount = message.get("Amount")
            ?.takeIf { it.isNumber || it.isString }
            ?.asString()
            ?.toLongOrNull()
        val providerClientRefId = message?.get("ClientRefId")?.asString()?.takeIf { it.isNotBlank() }
        return when {
            providerAmount == null -> invalidVerificationEvidence(409, payPingRequestId, "missing_amount")
            request.clientRefId != null && providerClientRefId == null ->
                invalidVerificationEvidence(409, payPingRequestId, "missing_client_ref_id")
            providerAmount != expectedAmount.toLong() ->
                PaymentVerificationResult.Failed(
                    "PAYPING_VERIFY_REJECTED_AMOUNT_MISMATCH",
                    "PayPing already-verified amount $providerAmount does not match expected $expectedAmount.",
                    409,
                    payPingRequestId,
                )
            request.clientRefId != null && providerClientRefId != request.clientRefId ->
                PaymentVerificationResult.Failed(
                    "PAYPING_VERIFY_REJECTED_CLIENT_REF_MISMATCH",
                    "PayPing already-verified clientRefId does not match the local payment attempt.",
                    409,
                    payPingRequestId,
                )
            else -> PaymentVerificationResult.Confirmed(
                PaymentConfirmation(
                    providerRefId = request.providerRefId,
                    amount = request.expectedAmount,
                    verifiedAt = Instant.now(),
                    cardLast4 = message?.get("CardNumber")?.asString()?.let(::maskedCardLast4),
                    providerRequestId = payPingRequestId,
                ),
            )
        }
    }

    private fun invalidVerificationEvidence(
        status: Int,
        payPingRequestId: String?,
        reason: String,
    ): PaymentVerificationResult.Failed {
        return PaymentVerificationResult.Failed(
            "PAYPING_VERIFY_INVALID_RESPONSE",
            "PayPing verification response lacks mandatory evidence: $reason.",
            status,
            payPingRequestId,
        )
    }

    /** Parses PayPing ProblemDetails error bodies into safe, truncated fields for logging. */
    private fun parseProblemDetails(body: String): PayPingProblemDetails? {
        if (body.isBlank()) return null
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return null
        if (!node.isObject) return null
        val metaData = node.path("metaData")
        val errors = metaData.path("errors")
            .takeIf { it.isArray }
            ?.mapNotNull { err -> err.get("message")?.asString()?.takeIf { it.isNotBlank() } }
            .orEmpty()
        val parts = buildList {
            node.get("title")?.asString()?.takeIf { it.isNotBlank() }?.let(::add)
            node.get("detail")?.asString()?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(errors)
        }
        val metaCode = metaData.get("code")?.takeIf { it.isNumber || it.isString }?.asInt()
        if (metaCode == null && parts.isEmpty()) return null
        return PayPingProblemDetails(
            metaCode = metaCode,
            safeMessage = parts.joinToString(" | ").take(MAX_PROVIDER_ERROR_LOG_LENGTH).takeIf { it.isNotBlank() },
        )
    }

    private fun maskedCardLast4(cardNumber: String): String? {
        return cardNumber.filter(Char::isDigit).takeIf { it.length >= 4 }?.takeLast(4)
    }

    override fun listUnverifiedPayments(): List<UnverifiedPayment> {
        if (!properties.reportEnabled || properties.apiKey.isBlank()) return emptyList()

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "offset" to 0,
                "limit" to 50,
                "filter" to listOf("unverified"),
            ),
        )
        val httpRequest = payPingRequest("/v1/report/TransactionReport")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        logPayPingRequest("report", "/v1/report/TransactionReport", payload)

        return try {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val payPingRequestId = resolvePayPingRequestId(response.headers[PAYPING_REQUEST_ID_HEADER], body)
                logPayPingResponse(
                    "report",
                    "/v1/report/TransactionReport",
                    response.code,
                    payPingRequestId,
                    body,
                )
                if (!response.isSuccessful) {
                    log.warn("event=payping_report outcome=failure status={}", response.code)
                    return emptyList()
                }
                objectMapper.readTree(body).mapNotNull { node ->
                    val refId = node.get("rrn")?.asString()?.takeIf { it.isNotBlank() }
                        ?: node.get("refId")?.asString()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val clientRefId = node.get("clientRefId")?.asString()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val amount = node.get("amount")?.asString()?.toBigDecimalOrNull()
                        ?: return@mapNotNull null
                    UnverifiedPayment(
                        providerRefId = refId,
                        clientRefId = clientRefId,
                        amount = Money(amount, "IRT"),
                        payDate = node.get("payDate")?.asString()?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Instant.parse(it) }.getOrNull()
                        },
                    )
                }
            }
        } catch (ex: RuntimeException) {
            log.warn("event=payping_report outcome=failure reason=response_processing_failed")
            emptyList()
        } catch (ex: IOException) {
            log.warn("event=payping_report outcome=failure reason=io_exception")
            emptyList()
        }
    }

    private fun payPingRequest(path: String): Request.Builder {
        return Request.Builder()
            .url("${properties.baseUrl.trimEnd('/')}${path.ensureLeadingSlash()}")
            .addHeader("Authorization", "Bearer ${properties.apiKey}")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
    }

    private fun logPayPingRequest(operation: String, path: String, payload: String) {
        log.debug(
            "event=payping_http_request operation={} method=POST path={} content_type={} body={}",
            operation,
            path,
            JSON_MEDIA_TYPE,
            sanitizePayPingLogPayload(payload),
        )
    }

    private fun logPayPingResponse(
        operation: String,
        path: String,
        status: Int,
        payPingRequestId: String?,
        body: String,
    ) {
        log.debug(
            "event=payping_http_response operation={} method=POST path={} status={} payping_request_id={} body={}",
            operation,
            path,
            status,
            payPingRequestId,
            sanitizePayPingLogPayload(body),
        )
    }

    private fun resolvePayPingRequestId(headerValue: String?, body: String): String? {
        if (!headerValue.isNullOrBlank()) return headerValue
        if (body.isBlank()) return null
        return runCatching {
            objectMapper.readTree(body).get("paypingTraceId")?.asString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun sanitizePayPingLogPayload(payload: String): String {
        if (payload.isBlank()) return ""
        return runCatching {
            objectMapper.writeValueAsString(redactPayPingNode(objectMapper.readTree(payload)))
        }.getOrElse { UNPARSEABLE_PAYPING_PAYLOAD_REDACTED }
    }

    private fun redactPayPingNode(node: tools.jackson.databind.JsonNode): tools.jackson.databind.JsonNode {
        if (node.isObject) {
            val copy = objectMapper.createObjectNode()
            node.properties().forEach { (key, value) ->
                copy.set(
                    key,
                    if (key.lowercase() in SENSITIVE_LOG_FIELDS) {
                        objectMapper.valueToTree("[REDACTED]")
                    } else {
                        redactPayPingNode(value)
                    },
                )
            }
            return copy
        }
        if (node.isArray) {
            val copy = objectMapper.createArrayNode()
            node.forEach { copy.add(redactPayPingNode(it)) }
            return copy
        }
        return node
    }

    private fun Money.toPayPingAmount(): Int {
        val tomanAmount = when (currency.uppercase()) {
            "IRR" -> amount.divide(BigDecimal.TEN)
            "IRT" -> amount
            else -> amount
        }
        return tomanAmount.setScale(0, RoundingMode.UNNECESSARY).intValueExact()
    }

    private fun String.ensureLeadingSlash(): String = if (startsWith('/')) this else "/$this"

    private fun String.safeSuffix(): String = "***${takeLast(SAFE_IDENTIFIER_SUFFIX_LENGTH)}"

    private fun String?.safeCorrelationHash(): String? {
        if (this.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(SAFE_CORRELATION_HASH_LENGTH)
    }

    private fun recordProviderRequestMetric(operation: String, outcome: String) {
        meterRegistryProvider?.ifAvailable { registry ->
            Counter.builder("gyro.billing.payping.provider.requests")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment()
        }
    }

    private data class PayPingCreatePaymentRequest(
        val amount: Int,
        val returnUrl: String,
        val payerIdentity: String? = null,
        val payerName: String? = null,
        val description: String?,
        val clientRefId: String,
    )

    private data class PayPingVerifyPaymentRequest(
        val amount: Int,
        val paymentCode: String,
        val paymentRefId: Long,
    )

    private data class PayPingProblemDetails(
        val metaCode: Int?,
        val safeMessage: String?,
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val PAYPING_REQUEST_ID_HEADER = "X-PayPingRequest-ID"
        private const val ALREADY_VERIFIED_META_CODE = 110
        private const val MAX_PROVIDER_ERROR_LOG_LENGTH = 300
        private const val SAFE_IDENTIFIER_SUFFIX_LENGTH = 4
        private const val SAFE_CORRELATION_HASH_LENGTH = 12
        private const val UNPARSEABLE_PAYPING_PAYLOAD_REDACTED = "[UNPARSEABLE_PAYPING_PAYLOAD_REDACTED]"
        private val SENSITIVE_LOG_FIELDS = setOf(
            "authorization",
            "apikey",
            "token",
            "cardnumber",
            "cardhashpan",
            "payeridentity",
            "nationalcode",
            "payername",
        )
        private val log = LoggerFactory.getLogger(PayPingBillingProvider::class.java)
    }
}
