package com.gyro.api.subscription.web

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.BillingObservability
import com.gyro.api.subscription.application.PaymentVerificationTransactionService
import com.gyro.api.subscription.billing.FakeBillingProvider
import com.gyro.api.subscription.billing.PaymentConfirmation
import com.gyro.api.subscription.billing.PaymentVerificationResult
import com.gyro.api.subscription.domain.InvoiceStatus
import com.gyro.api.subscription.domain.Money
import com.gyro.api.subscription.domain.PaymentAttemptStatus
import com.gyro.api.subscription.domain.SubscriptionStatus
import io.micrometer.core.instrument.MeterRegistry
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.billing.lifecycle.jobs-enabled=false",
    ],
)
class PayPingControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val billingProvider: FakeBillingProvider,
    @Autowired private val billingObservability: BillingObservability,
    @Autowired private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    @Autowired private val paymentVerificationService: com.gyro.api.subscription.application.PaymentVerificationService,
    @Autowired private val paymentTransactions: PaymentVerificationTransactionService,
) {
    private val objectMapper = JsonMapper.builder().build()
    private val createdUserIds = mutableSetOf<UUID>()
    private var planId: Long = 0L
    private var priceId: Long = 0L
    private var testUserId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        billingProvider.reset()
        testUserId = createUser()
        planId = seedPlan("PAYPING_${System.nanoTime()}", "PayPing Advanced")
        priceId = seedPrice(planId)
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update(
            "DELETE FROM outbox_event_consumptions WHERE event_id IN " +
                "(SELECT id FROM outbox_events WHERE aggregate_type = 'UserSubscription')",
        )
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_type = 'UserSubscription'")
        createdUserIds.forEach { userId ->
            jdbcTemplate.update("DELETE FROM subscription_events WHERE user_id = ?", userId)
            jdbcTemplate.update("DELETE FROM user_subscriptions WHERE user_id = ?", userId)
        }
        jdbcTemplate.update(
            "DELETE FROM promotion_redemptions WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)",
            planId,
        )
        jdbcTemplate.update(
            "DELETE FROM payment_events WHERE payment_attempt_id IN (SELECT pa.id FROM payment_attempts pa JOIN invoices i ON i.id = pa.invoice_id WHERE i.plan_id = ?)",
            planId,
        )
        jdbcTemplate.update("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM invoices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM provider_price_mappings WHERE subscription_price_id = ?", priceId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
        createdUserIds.forEach { userId ->
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)
        }
        createdUserIds.clear()
    }

    @Test
    fun `return status verifies PayPing payment before activating subscription`() {
        val checkout = createCheckout()
        val clientRefId = checkout.clientRefId

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("paymentCode", checkout.providerCode)
            param("paymentRefId", "123456789")
            param("clientrefid", clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("SUCCESS") }
            jsonPath("$.clientRefId") { value(clientRefId) }
        }

        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val subscriptionStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM user_subscriptions WHERE user_id = ?",
            String::class.java,
            testUserId,
        )

        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
        assertEquals(SubscriptionStatus.ACTIVE.name, subscriptionStatus)
    }

    @Test
    fun `public callback stores only safe PayPing metadata`() {
        val checkout = createCheckout()

        mockMvc.post("/api/v1/billing/payping/callback") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("paymentCode", checkout.providerCode)
            param("paymentRefId", "456789123")
            param("clientrefid", checkout.clientRefId)
            param("cardnumber", "6219861012345678")
            param("cardhashpan", "unsafe-card-hash")
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=SUCCESS")) }
        }

        val safePayloads = jdbcTemplate.queryForList(
            "SELECT raw_payload FROM payment_events WHERE payment_attempt_id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertFalse(safePayloads.any { it.contains("6219861012345678") })
        assertFalse(safePayloads.any { it.contains("unsafe-card-hash") })
    }

    @Test
    fun `v3 callback with data JSON verifies payment and activates subscription`() {
        val checkout = createCheckout()
        val dataJson = objectMapper.writeValueAsString(
            mapOf(
                "clientRefId" to checkout.clientRefId,
                "paymentCode" to checkout.providerCode,
                "paymentRefId" to 987654321,
                "amount" to 2000,
                "gatewayAmount" to 2000,
                "cardNumber" to "6219861012345678",
                "cardHashPan" to "hash-pan-value",
            ),
        )

        mockMvc.post("/api/v1/billing/payping/callback") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("status", "1")
            param("errorCode", "")
            param("data", dataJson)
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=SUCCESS")) }
        }

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        val subscriptionStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM user_subscriptions WHERE user_id = ?",
            String::class.java,
            testUserId,
        )
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
        assertEquals(SubscriptionStatus.ACTIVE.name, subscriptionStatus)
    }

    @Test
    fun `v3 callback with failed status redirects to frontend with abandoned state`() {
        val checkout = createCheckout()
        val dataJson = objectMapper.writeValueAsString(
            mapOf(
                "clientRefId" to checkout.clientRefId,
                "paymentCode" to checkout.providerCode,
                "amount" to 2000,
                "gatewayAmount" to 2000,
            ),
        )

        mockMvc.post("/api/v1/billing/payping/callback") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("status", "0")
            param("errorCode", "127")
            param("data", dataJson)
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=ABANDONED")) }
        }

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(PaymentAttemptStatus.PENDING.name, attemptStatus)
    }

    @Test
    fun `GET callback verifies payment then redirects to frontend with status`() {
        val checkout = createCheckout()

        mockMvc.get("/api/v1/billing/payping/callback") {
            param("paymentCode", checkout.providerCode)
            param("paymentRefId", "789456123")
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=SUCCESS")) }
            header { string("Location", containsString("paymentAttemptId=")) }
        }

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
    }

    @Test
    fun `GET callback without ref id redirects with abandoned state`() {
        val checkout = createCheckout()

        mockMvc.get("/api/v1/billing/payping/callback") {
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=ABANDONED")) }
        }

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(PaymentAttemptStatus.PENDING.name, attemptStatus)
    }

    @Test
    fun `wrong authenticated user cannot verify another users return status`() {
        val checkout = createCheckout()
        val otherUserId = createUser()

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(otherUserId)))
            param("code", checkout.providerCode)
            param("refid", "PAYPING_REF_WRONG_USER")
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `same PayPing ref id cannot verify two payment attempts`() {
        val first = createCheckout()
        val second = createCheckout()

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("code", first.providerCode)
            param("refid", "PAYPING_REF_REPLAY")
            param("clientrefid", first.clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("SUCCESS") }
        }

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("code", second.providerCode)
            param("refid", "PAYPING_REF_REPLAY")
            param("clientrefid", second.clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("FAILED") }
        }

        val secondInvoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            second.invoiceId,
        )
        val secondAttemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            second.paymentAttemptId,
        )
        assertEquals(InvoiceStatus.OPEN.name, secondInvoiceStatus)
        assertEquals(PaymentAttemptStatus.FAILED.name, secondAttemptStatus)
    }

    @Test
    fun `provider timeout keeps attempt verify pending without activating subscription`() {
        val checkout = createCheckout()
        billingProvider.enqueueVerificationResult(
            PaymentVerificationResult.Pending("PAYPING_VERIFY_TIMEOUT", "PayPing verification is pending."),
        )

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("code", checkout.providerCode)
            param("refid", "PAYPING_REF_PENDING")
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("PENDING") }
        }

        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?",
            Int::class.java,
            testUserId,
        )
        assertEquals(InvoiceStatus.OPEN.name, invoiceStatus)
        assertEquals(PaymentAttemptStatus.VERIFY_PENDING.name, attemptStatus)
        assertEquals(0, subscriptionCount)
    }

    @Test
    fun `amount mismatch marks verification failed without activating subscription`() {
        val checkout = createCheckout()
        billingProvider.enqueueVerificationResult(
            PaymentVerificationResult.Confirmed(
                PaymentConfirmation(
                    providerRefId = "PAYPING_REF_MISMATCH",
                    amount = Money(BigDecimal("1.00"), "IRR"),
                    verifiedAt = java.time.Instant.now(),
                    cardLast4 = null,
                ),
            ),
        )

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("code", checkout.providerCode)
            param("refid", "PAYPING_REF_MISMATCH")
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("FAILED") }
        }

        val subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?",
            Int::class.java,
            testUserId,
        )
        assertEquals(0, subscriptionCount)
    }

    @Test
    fun `return status can repair failed open attempt with same PayPing ref id`() {
        val checkout = createCheckout()
        jdbcTemplate.update(
            "UPDATE payment_attempts SET status = 'FAILED', provider_ref_id = ? WHERE id = ?",
            "PAYPING_REF_REPAIR",
            checkout.paymentAttemptId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_events (
                payment_attempt_id, provider, event_type, provider_ref_id, raw_payload, safe_summary, created_at
            )
            VALUES (?, 'PAYPING', 'PAYPING_VERIFICATION_FAILED', ?, ?::jsonb, ?::jsonb, now())
            """.trimIndent(),
            checkout.paymentAttemptId,
            "PAYPING_REF_REPAIR",
            """{"outcome":"failed","reason":"PAYPING_VERIFY_REJECTED"}""",
            """{"outcome":"failed","reason":"PAYPING_VERIFY_REJECTED"}""",
        )

        mockMvc.get("/api/v1/billing/payping/return-status") {
            with(authentication(testAuthentication(testUserId)))
            param("code", checkout.providerCode)
            param("refid", "PAYPING_REF_REPAIR")
            param("clientrefid", checkout.clientRefId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("SUCCESS") }
        }

        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
    }

    @Test
    fun `callback with unknown client ref id redirects to support needed`() {
        mockMvc.post("/api/v1/billing/payping/callback") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("paymentCode", "UNKNOWN-CODE")
            param("paymentRefId", "111222333")
            param("clientrefid", "checkout_unknown_${System.nanoTime()}")
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=SUPPORT_NEEDED")) }
        }
    }

    @Test
    fun `repeated callback for verified payment stays successful without duplicate subscription`() {
        val checkout = createCheckout()

        repeat(2) {
            mockMvc.post("/api/v1/billing/payping/callback") {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("paymentCode", checkout.providerCode)
                param("paymentRefId", "654987321")
                param("clientrefid", checkout.clientRefId)
            }.andExpect {
                status { isFound() }
                header { string("Location", containsString("state=SUCCESS")) }
            }
        }

        val subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?",
            Int::class.java,
            testUserId,
        )
        val paidInvoices = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM invoices WHERE plan_id = ? AND status = 'PAID'",
            Int::class.java,
            planId,
        )
        assertEquals(1, subscriptionCount)
        assertEquals(1, paidInvoices)
        // The second callback must short-circuit on the already-verified attempt.
        assertEquals(1, billingProvider.verifyInvocationCount())
    }

    @Test
    fun `late pending and failed transitions cannot regress a verified payment`() {
        val checkout = createCheckout()
        val providerRefId = "PAYPING_REF_TERMINAL_GUARD"

        paymentVerificationService.applyVerifiedPayment(
            checkout.paymentAttemptId,
            providerRefId,
            PaymentConfirmation(
                providerRefId = providerRefId,
                amount = Money(BigDecimal("1990000.00"), "IRR"),
                verifiedAt = java.time.Instant.now(),
                cardLast4 = null,
            ),
        )

        paymentTransactions.markVerificationPending(
            checkout.paymentAttemptId,
            providerRefId,
            "LATE_PROVIDER_TIMEOUT",
        )
        paymentTransactions.markVerificationFailed(
            checkout.paymentAttemptId,
            providerRefId,
            "LATE_PROVIDER_FAILURE",
        )

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
    }

    @Test
    fun `concurrent successful transitions record payment success once`() {
        val checkout = createCheckout()
        val providerRefId = "PAYPING_REF_CONCURRENT_SUCCESS"
        val confirmation = PaymentConfirmation(
            providerRefId = providerRefId,
            amount = Money(BigDecimal("1990000.00"), "IRR"),
            verifiedAt = java.time.Instant.now(),
            cardLast4 = null,
        )
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            List(2) {
                executor.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    paymentVerificationService.applyVerifiedPayment(
                        checkout.paymentAttemptId,
                        providerRefId,
                        confirmation,
                    )
                }
            }.forEach { future -> future.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val successEvents = jdbcTemplate.queryForObject(
            """
            select count(*)
            from payment_events
            where payment_attempt_id = ?
              and event_type = 'PAYPING_VERIFICATION_SUCCEEDED'
            """.trimIndent(),
            Int::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(1, successEvents)
    }

    @Test
    fun `late provider confirmation cannot reopen a terminalized reconciliation attempt`() {
        val checkout = createCheckout()
        val providerRefId = "PAYPING_REF_AFTER_TERMINAL"
        paymentTransactions.markReconciliationTerminal(
            checkout.paymentAttemptId,
            reason = "RECONCILIATION_MAX_AGE_EXCEEDED",
        )

        paymentVerificationService.applyVerifiedPayment(
            checkout.paymentAttemptId,
            providerRefId,
            PaymentConfirmation(
                providerRefId = providerRefId,
                amount = Money(BigDecimal("1990000.00"), "IRR"),
                verifiedAt = java.time.Instant.now(),
                cardLast4 = null,
            ),
        )

        val attemptStatus = jdbcTemplate.queryForObject(
            "select status from payment_attempts where id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val invoiceStatus = jdbcTemplate.queryForObject(
            "select status from invoices where id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        assertEquals(PaymentAttemptStatus.STALE.name, attemptStatus)
        assertEquals(InvoiceStatus.OPEN.name, invoiceStatus)
    }

    @Test
    fun `scheduled reconciliation verifies pending payment and repeated runs stay idempotent`() {
        val checkout = createCheckout()
        jdbcTemplate.update(
            "UPDATE payment_attempts SET status = 'VERIFY_PENDING', provider_ref_id = ? WHERE id = ?",
            "246813579",
            checkout.paymentAttemptId,
        )

        val summary = paymentVerificationService.reconcilePendingAttempts(
            maxAge = java.time.Duration.ofHours(24),
            maxRetries = 5,
            batchSize = 50,
        )
        assertTrue(summary.verified >= 1)

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        val invoiceStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM invoices WHERE id = ?",
            String::class.java,
            checkout.invoiceId,
        )
        val subscriptionStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM user_subscriptions WHERE user_id = ?",
            String::class.java,
            testUserId,
        )
        assertEquals(PaymentAttemptStatus.VERIFIED.name, attemptStatus)
        assertEquals(InvoiceStatus.PAID.name, invoiceStatus)
        assertEquals(SubscriptionStatus.ACTIVE.name, subscriptionStatus)

        val verifiedCallCount = billingProvider.verifyInvocationCount()
        paymentVerificationService.reconcilePendingAttempts(
            maxAge = java.time.Duration.ofHours(24),
            maxRetries = 5,
            batchSize = 50,
        )

        val subscriptionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?",
            Int::class.java,
            testUserId,
        )
        assertEquals(1, subscriptionCount)
        assertEquals(verifiedCallCount, billingProvider.verifyInvocationCount())
    }

    @Test
    fun `scheduled reconciliation respects retry limit for attempts that stay pending`() {
        val checkout = createCheckout()
        jdbcTemplate.update(
            "UPDATE payment_attempts SET status = 'VERIFY_PENDING', provider_ref_id = ? WHERE id = ?",
            "135792468",
            checkout.paymentAttemptId,
        )
        billingProvider.enqueueVerificationResult(
            PaymentVerificationResult.Pending("PAYPING_VERIFY_UNAVAILABLE", "PayPing is unavailable.", 503),
        )

        paymentVerificationService.reconcilePendingAttempts(
            maxAge = java.time.Duration.ofHours(24),
            maxRetries = 1,
            batchSize = 50,
        )
        val callsAfterFirstRun = billingProvider.verifyInvocationCount()
        assertEquals(1, callsAfterFirstRun)

        // Retry budget is exhausted, so the second run terminalizes the automatically reversed attempt.
        paymentVerificationService.reconcilePendingAttempts(
            maxAge = java.time.Duration.ofHours(24),
            maxRetries = 1,
            batchSize = 50,
        )
        assertEquals(callsAfterFirstRun, billingProvider.verifyInvocationCount())

        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(PaymentAttemptStatus.STALE.name, attemptStatus)
        val terminalEvent = jdbcTemplate.queryForObject(
            """
            SELECT safe_summary::text FROM payment_events
            WHERE payment_attempt_id = ? AND event_type = 'PAYPING_RECONCILIATION_TERMINATED'
            ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertTrue(terminalEvent.orEmpty().contains("RECONCILIATION_RETRY_EXHAUSTED"))
        assertTrue(terminalEvent.orEmpty().contains("automatic_reversal_after_verification_window"))
    }

    @Test
    fun `scheduled reconciliation terminalizes attempts after the hard fifteen minute window`() {
        val checkout = createCheckout()
        jdbcTemplate.update(
            "UPDATE payment_attempts SET status = 'VERIFY_PENDING', provider_ref_id = ?, created_at = now() - interval '16 minutes' WHERE id = ?",
            "864209753",
            checkout.paymentAttemptId,
        )

        val summary = paymentVerificationService.reconcilePendingAttempts(
            maxAge = java.time.Duration.ofHours(24),
            maxRetries = 5,
            batchSize = 50,
        )

        assertEquals(0, billingProvider.verifyInvocationCount())
        val attemptStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_attempts WHERE id = ?",
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertEquals(PaymentAttemptStatus.STALE.name, attemptStatus)
        assertEquals(1, summary.failed)
        val terminalEvent = jdbcTemplate.queryForObject(
            """
            SELECT safe_summary::text FROM payment_events
            WHERE payment_attempt_id = ? AND event_type = 'PAYPING_RECONCILIATION_TERMINATED'
            ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            String::class.java,
            checkout.paymentAttemptId,
        )
        assertTrue(terminalEvent.orEmpty().contains("RECONCILIATION_MAX_AGE_EXCEEDED"))
        assertTrue(terminalEvent.orEmpty().contains("automatic_reversal_after_verification_window"))
    }

    @Test
    fun `admin reconciliation trigger requires admin role`() {
        mockMvc.post("/api/v1/billing/payping/admin/reconcile") {
            with(authentication(testAuthentication(testUserId)))
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `billing observability exposes pending counts and oldest verification age`() {
        val checkout = createCheckout()
        jdbcTemplate.update(
            "UPDATE payment_attempts SET status = 'VERIFY_PENDING', updated_at = now() - interval '20 minutes' WHERE id = ?",
            checkout.paymentAttemptId,
        )

        billingObservability.refresh()

        val registry = meterRegistryProvider.ifAvailable ?: error("MeterRegistry is required for observability test")
        val pending = registry.get("gyro.billing.payment.attempts")
            .tag("status_group", "pending")
            .gauge()
            .value()
        val oldestAge = registry.get("gyro.billing.payping.verify_pending.oldest_age_seconds").gauge().value()
        assertTrue(pending >= 1.0)
        assertTrue(oldestAge >= 1_100.0)
    }

    private fun createCheckout(): CheckoutRecord {
        val response = mockMvc.post("/api/v1/billing/checkout") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(testAuthentication(testUserId)))
            content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUCCESS") }
        }.andReturn().response.contentAsString

        val node = objectMapper.readTree(response)
        val paymentAttemptId = UUID.fromString(node.get("paymentAttemptId").asText())
        val clientRefId = jdbcTemplate.queryForObject(
            "SELECT client_ref_id FROM payment_attempts WHERE id = ?",
            String::class.java,
            paymentAttemptId,
        ) ?: error("Expected client_ref_id")
        val providerCode = jdbcTemplate.queryForObject(
            "SELECT provider_code FROM payment_attempts WHERE id = ?",
            String::class.java,
            paymentAttemptId,
        ) ?: error("Expected provider_code")

        return CheckoutRecord(
            invoiceId = UUID.fromString(node.get("invoiceId").asText()),
            paymentAttemptId = paymentAttemptId,
            clientRefId = clientRefId,
            providerCode = providerCode,
        )
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    private fun createUser(): UUID {
        val id = UUID.randomUUID()
        createdUserIds.add(id)
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role,
                email_verification_status, phone_verification_status,
                status, created_at, updated_at
            ) values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "payping-${System.nanoTime()}-${id.toString().take(8)}@example.com",
        )
        return id
    }

    private fun seedPlan(code: String, name: String): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_plans (code, name, free, active, grace_period_days)
            values (?, ?, false, true, 7)
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            name,
        ) ?: error("Expected plan id")
    }

    private fun seedPrice(planId: Long): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            values (?, 30, 1990000.00, 'IRR', true, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            planId,
        ) ?: error("Expected price id")
    }

    private data class CheckoutRecord(
        val invoiceId: UUID,
        val paymentAttemptId: UUID,
        val clientRefId: String,
        val providerCode: String,
    )
}
