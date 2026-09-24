package com.gyro.api.subscription.billing

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gyro.api.subscription.domain.Money
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PayPingBillingProviderTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `create checkout posts PayPing payload with bearer token and stores request id`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json", "X-PayPingRequest-ID", "payping-req-1"),
                body = """{"code":"ABCD"}""",
            ),
        )
        server.start()

        val provider = provider()
        val result = provider.createCheckout(
            CheckoutRequest(
                paymentAttemptId = UUID.randomUUID(),
                clientRefId = "checkout-client-ref",
                amount = Money(BigDecimal("1990000.00"), "IRR"),
                returnUrl = "https://app.example.com/profile/billing/verify",
                description = "Gyro Premium Subscription",
            ),
        )

        val success = assertIs<CheckoutResult.Success>(result)
        assertEquals("${server.url("/").toString().removeSuffix("/")}/v3/pay/start/ABCD", success.gatewayUrl)
        assertEquals("ABCD", success.providerCode)
        assertEquals("payping-req-1", success.providerRequestId)

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertEquals("/v3/pay", recorded.target)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertTrue(body.contains("\"amount\":199000"))
        assertTrue(body.contains("\"clientRefId\":\"checkout-client-ref\""))
        assertTrue(body.contains("\"returnUrl\":\"https://app.example.com/profile/billing/verify\""))
        assertFalse(body.contains("isReversible"))
    }

    @Test
    fun `verify payment posts amount payment code and numeric payment ref id`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json", "X-PayPingRequest-ID", "payping-verify-1"),
                body = """{"amount":199000,"clientRefId":"client-ref-1","paymentRefId":123456,"code":"PAY-CODE-123","cardNumber":"603799******1234"}""",
            ),
        )
        server.start()

        val provider = provider()
        val result = provider.verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = UUID.randomUUID(),
                providerCode = "PAY-CODE-123",
                providerRefId = "123456",
                expectedAmount = Money(BigDecimal("1990000.00"), "IRR"),
                clientRefId = "client-ref-1",
            ),
        )

        val confirmed = assertIs<PaymentVerificationResult.Confirmed>(result)
        assertEquals("123456", confirmed.confirmation.providerRefId)
        assertEquals("payping-verify-1", confirmed.confirmation.providerRequestId)
        assertEquals("1234", confirmed.confirmation.cardLast4)

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertEquals("/v3/pay/verify", recorded.target)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertTrue(body.contains("\"amount\":199000"))
        assertTrue(body.contains("\"paymentCode\":\"PAY-CODE-123\""))
        assertTrue(body.contains("\"paymentRefId\":123456"))
        assertFalse(body.contains("\"refId\""))
    }

    @Test
    fun `verify payment fails when provider amount does not match expected amount`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"amount":1,"clientRefId":"client-ref-1"}""",
            ),
        )
        server.start()

        val result = provider().verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = UUID.randomUUID(),
                providerCode = "PAY-CODE-123",
                providerRefId = "123456",
                expectedAmount = Money(BigDecimal("2000.00"), "IRT"),
                clientRefId = "client-ref-1",
            ),
        )

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_VERIFY_REJECTED_AMOUNT_MISMATCH", failed.providerErrorCode)
    }

    @Test
    fun `verify payment fails when provider client ref does not match local attempt`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"amount":2000,"clientRefId":"someone-else"}""",
            ),
        )
        server.start()

        val result = provider().verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = UUID.randomUUID(),
                providerCode = "PAY-CODE-123",
                providerRefId = "123456",
                expectedAmount = Money(BigDecimal("2000.00"), "IRT"),
                clientRefId = "client-ref-1",
            ),
        )

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_VERIFY_REJECTED_CLIENT_REF_MISMATCH", failed.providerErrorCode)
    }

    @Test
    fun `verify payment rejects successful response with empty body`() {
        assertInvalidVerificationResponse(200, "")
    }

    @Test
    fun `verify payment rejects malformed successful response`() {
        assertInvalidVerificationResponse(200, "not-json")
    }

    @Test
    fun `verify payment rejects successful response missing amount`() {
        assertInvalidVerificationResponse(200, """{"clientRefId":"client-ref-1"}""")
    }

    @Test
    fun `verify payment rejects successful response missing client ref id`() {
        assertInvalidVerificationResponse(200, """{"amount":2000}""")
    }

    @Test
    fun `verify payment info logs mask operational payment references`() {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"amount":2000,"clientRefId":"client-ref-1"}""",
            ),
        )
        server.start()
        val logger = LoggerFactory.getLogger(PayPingBillingProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val request = verifyRequest()

        try {
            assertIs<PaymentVerificationResult.Confirmed>(provider().verifyPayment(request))
            val rendered = appender.list
                .map { it.formattedMessage }
                .single { message ->
                    message.contains("event=payping_verify stage=request") &&
                        message.contains("payment_attempt_id=${request.paymentAttemptId}")
                }
            assertFalse(rendered.contains("PAY-CODE-123"))
            assertFalse(rendered.contains("client-ref-1"))
            assertFalse(rendered.contains("provider_ref_id=123456"))
            assertTrue(rendered.contains("payment_code_suffix=***-123"))
            assertTrue(rendered.contains("provider_ref_suffix=***3456"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `verify payment treats already verified conflict as confirmed`() {
        server.enqueue(
            MockResponse(
                code = 409,
                headers = headersOf("Content-Type", "application/json"),
                body = """
                    {"title":"ConflictException","status":409,"paypingTraceId":"trace-409",
                     "metaData":{"code":110,"message":{"Amount":2000,"ClientRefId":"client-ref-1","PaymentRefId":123456,"CardNumber":"603799******1234"}}}
                """.trimIndent(),
            ),
        )
        server.start()

        val result = provider().verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = UUID.randomUUID(),
                providerCode = "PAY-CODE-123",
                providerRefId = "123456",
                expectedAmount = Money(BigDecimal("2000.00"), "IRT"),
                clientRefId = "client-ref-1",
            ),
        )

        val confirmed = assertIs<PaymentVerificationResult.Confirmed>(result)
        assertEquals("1234", confirmed.confirmation.cardLast4)
    }

    @Test
    fun `verify payment fails already verified conflict when amount differs`() {
        server.enqueue(
            MockResponse(
                code = 409,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"metaData":{"code":110,"message":{"Amount":9999,"ClientRefId":"client-ref-1"}}}""",
            ),
        )
        server.start()

        val result = provider().verifyPayment(
            VerifyPaymentRequest(
                paymentAttemptId = UUID.randomUUID(),
                providerCode = "PAY-CODE-123",
                providerRefId = "123456",
                expectedAmount = Money(BigDecimal("2000.00"), "IRT"),
                clientRefId = "client-ref-1",
            ),
        )

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_VERIFY_REJECTED_AMOUNT_MISMATCH", failed.providerErrorCode)
    }

    @Test
    fun `verify payment rejects already verified conflict missing amount`() {
        assertInvalidVerificationResponse(
            409,
            """{"metaData":{"code":110,"message":{"ClientRefId":"client-ref-1"}}}""",
        )
    }

    @Test
    fun `verify payment rejects already verified conflict missing client ref id`() {
        assertInvalidVerificationResponse(
            409,
            """{"metaData":{"code":110,"message":{"Amount":2000}}}""",
        )
    }

    @Test
    fun `verify payment keeps unknown conflict codes pending`() {
        server.enqueue(
            MockResponse(
                code = 409,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"metaData":{"code":42}}""",
            ),
        )
        server.start()

        val result = provider().verifyPayment(verifyRequest())

        val pending = assertIs<PaymentVerificationResult.Pending>(result)
        assertEquals("PAYPING_VERIFY_CONFLICT", pending.providerErrorCode)
    }

    @Test
    fun `verify payment keeps processing responses pending`() {
        server.enqueue(MockResponse(code = 202, body = ""))
        server.start()

        val result = provider().verifyPayment(verifyRequest())

        val pending = assertIs<PaymentVerificationResult.Pending>(result)
        assertEquals("PAYPING_VERIFY_PROCESSING", pending.providerErrorCode)
    }

    @Test
    fun `verify payment fails without a payment code`() {
        server.start()

        val result = provider().verifyPayment(verifyRequest(providerCode = null))

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_PAYMENT_CODE_MISSING", failed.providerErrorCode)
    }

    @Test
    fun `verify payment fails on non numeric payment ref id`() {
        server.start()

        val result = provider().verifyPayment(verifyRequest(providerRefId = "PAYPING_REF_NOT_NUMERIC"))

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_PAYMENT_REF_INVALID", failed.providerErrorCode)
    }

    @Test
    fun `verify payment keeps transient provider failures pending`() {
        server.enqueue(MockResponse(code = 503, body = ""))
        server.start()

        val result = provider().verifyPayment(verifyRequest())

        val pending = assertIs<PaymentVerificationResult.Pending>(result)
        assertEquals("PAYPING_VERIFY_UNAVAILABLE", pending.providerErrorCode)
        assertEquals(503, pending.providerHttpStatus)
    }

    @Test
    fun `verify payment marks PayPing rejection failed with parsed problem details`() {
        server.enqueue(
            MockResponse(
                code = 400,
                headers = headersOf("Content-Type", "application/json"),
                body = """
                    {"type":"https://datatracker.ietf.org/doc/html/rfc7231#section-6.5.1","title":"BadRequestException",
                     "status":400,"instance":"/v3/pay/verify","paypingTraceId":"trace-400",
                     "metaData":{"code":18,"errors":[{"field":"paymentRefId","message":"تراکنش ناموفق بود"}]}}
                """.trimIndent(),
            ),
        )
        server.start()

        val result = provider().verifyPayment(verifyRequest())

        val failed = assertIs<PaymentVerificationResult.Failed>(result)
        assertEquals("PAYPING_VERIFY_REJECTED", failed.providerErrorCode)
        assertEquals(400, failed.providerHttpStatus)
        assertTrue(failed.message.orEmpty().contains("code=18"))
        assertTrue(failed.message.orEmpty().contains("تراکنش ناموفق بود"))
    }

    @Test
    fun `create checkout returns typed failure on rejected provider response`() {
        server.enqueue(MockResponse(code = 400, body = """{"message":"bad request"}"""))
        server.start()

        val result = provider().createCheckout(
            CheckoutRequest(
                paymentAttemptId = UUID.randomUUID(),
                clientRefId = "checkout-client-ref",
                amount = Money(BigDecimal("1990000.00"), "IRR"),
                returnUrl = "https://app.example.com/profile/billing/verify",
                description = "Gyro Premium Subscription",
            ),
        )

        assertIs<CheckoutResult.Failure>(result)
    }

    @Test
    fun `malformed PayPing log payload is redacted instead of returned raw`() {
        server.start()
        val provider = provider()
        val sanitizer = PayPingBillingProvider::class.java
            .getDeclaredMethod("sanitizePayPingLogPayload", String::class.java)
            .apply { isAccessible = true }

        val sanitized = sanitizer.invoke(
            provider,
            "<html>cardnumber=6219861012345678 nationalcode=0012345678</html>",
        ) as String

        assertEquals("[UNPARSEABLE_PAYPING_PAYLOAD_REDACTED]", sanitized)
        assertFalse(sanitized.contains("6219861012345678"))
        assertFalse(sanitized.contains("0012345678"))
    }

    private fun verifyRequest(
        providerCode: String? = "PAY-CODE-123",
        providerRefId: String = "123456",
    ): VerifyPaymentRequest {
        return VerifyPaymentRequest(
            paymentAttemptId = UUID.randomUUID(),
            providerCode = providerCode,
            providerRefId = providerRefId,
            expectedAmount = Money(BigDecimal("2000.00"), "IRT"),
            clientRefId = "client-ref-1",
        )
    }

    private fun assertInvalidVerificationResponse(status: Int, body: String) {
        server.enqueue(
            MockResponse(
                code = status,
                headers = headersOf("Content-Type", "application/json"),
                body = body,
            ),
        )
        server.start()

        val failed = assertIs<PaymentVerificationResult.Failed>(provider().verifyPayment(verifyRequest()))
        assertEquals("PAYPING_VERIFY_INVALID_RESPONSE", failed.providerErrorCode)
        assertEquals(status, failed.providerHttpStatus)
    }

    private fun provider(): PayPingBillingProvider {
        return PayPingBillingProvider(
            properties = PayPingProperties(
                enabled = true,
                baseUrl = server.url("/").toString().removeSuffix("/"),
                apiKey = "test-key",
            ),
            objectMapper = ObjectMapper(),
        )
    }
}
