package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.*
import com.gyro.api.subscription.billing.*
import com.gyro.api.subscription.domain.InvoiceStatus
import com.gyro.api.subscription.domain.PaymentAttemptStatus
import com.gyro.api.subscription.domain.SubscriptionStatus
import com.gyro.api.subscription.infrastructure.InvoiceRepository
import com.gyro.api.subscription.infrastructure.PaymentAttemptRepository
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@TestConfiguration(proxyBeanMethods = false)
class CheckoutTransactionTestConfiguration {
    @Bean
    @Primary
    fun checkoutTransactionAwareBillingProvider() = CheckoutTransactionAwareBillingProvider()
}

class CheckoutTransactionAwareBillingProvider : BillingProvider {
    private val delegate = FakeBillingProvider()
    private val checkoutResults = ConcurrentLinkedQueue<CheckoutResult>()
    private val checkoutTransactionStates = CopyOnWriteArrayList<Boolean>()
    private val nextCheckoutBlock = AtomicReference<CheckoutBlock?>()

    override fun createCheckout(request: CheckoutRequest): CheckoutResult {
        checkoutTransactionStates += TransactionSynchronizationManager.isActualTransactionActive()
        nextCheckoutBlock.getAndSet(null)?.let { block ->
            block.entered.countDown()
            check(block.release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release checkout provider" }
        }
        val defaultResult = delegate.createCheckout(request)
        return checkoutResults.poll() ?: defaultResult
    }

    override fun verifyPayment(request: VerifyPaymentRequest): PaymentVerificationResult =
        delegate.verifyPayment(request)

    override fun listUnverifiedPayments(): List<UnverifiedPayment> = delegate.listUnverifiedPayments()

    fun enqueueCheckoutResult(result: CheckoutResult) {
        checkoutResults += result
    }

    fun checkoutInvocationCount(): Int = delegate.checkoutInvocationCount()

    fun transactionStates(): List<Boolean> = checkoutTransactionStates.toList()

    fun blockNextCheckout(): CheckoutBlock = CheckoutBlock().also { block ->
        check(nextCheckoutBlock.compareAndSet(null, block)) { "A checkout block is already registered" }
    }

    fun reset() {
        delegate.reset()
        checkoutResults.clear()
        checkoutTransactionStates.clear()
        nextCheckoutBlock.set(null)
    }

    class CheckoutBlock(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    )
}

@Import(TestcontainersConfiguration::class, CheckoutTransactionTestConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class CheckoutIdempotencyIntegrationTest(
    @Autowired private val checkoutService: CheckoutService,
    @Autowired private val invoiceService: InvoiceService,
    @Autowired private val invoiceRepository: InvoiceRepository,
    @Autowired private val paymentAttemptRepository: PaymentAttemptRepository,
    @Autowired private val promotionRedemptionRepository: PromotionRedemptionRepository,
    @Autowired private val userSubscriptionRepository: UserSubscriptionRepository,
    @Autowired private val promotionService: PromotionService,
    @Autowired private val paymentVerificationService: PaymentVerificationService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val billingProvider: CheckoutTransactionAwareBillingProvider,
) {
    private var planId: Long = 0L
    private var priceId: Long = 0L

    @BeforeEach
    fun setUp() {
        billingProvider.reset()
        val uniqueCode = "ADVANCED_${System.nanoTime()}"
        planId = seedPlan(uniqueCode, "Advanced", false)
        priceId = seedPrice(planId, 30, BigDecimal("19.99"), "IRR")
    }

    @AfterEach
    fun tearDown() {
        // Clean up test-created data to avoid affecting other test classes
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE scope LIKE 'checkout:%'")
        jdbcTemplate.update("DELETE FROM affiliate_commissions WHERE affiliate_id IN (SELECT id FROM affiliates WHERE promotion_id IN (SELECT id FROM promotions WHERE applicable_plan_id = ?))", planId)
        jdbcTemplate.update("DELETE FROM affiliates WHERE promotion_id IN (SELECT id FROM promotions WHERE applicable_plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM promotion_redemptions WHERE promotion_id IN (SELECT id FROM promotions WHERE applicable_plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM promotions WHERE applicable_plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id::text FROM user_subscriptions WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM subscription_events WHERE user_id IN (SELECT user_id FROM user_subscriptions WHERE plan_id = ?)", planId)
        jdbcTemplate.update(
            "DELETE FROM payment_events WHERE payment_attempt_id IN (SELECT pa.id FROM payment_attempts pa JOIN invoices i ON i.id = pa.invoice_id WHERE i.plan_id = ?)",
            planId
        )
        jdbcTemplate.update("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM invoices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM user_subscriptions WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
    }

    // ── 1. Duplicate idempotency key within TTL ────────────────────────

    @Nested
    inner class `Duplicate idempotency key within TTL` {

        @Test
        fun `repeating checkout with same key returns existing invoice and payment attempt`() {
            val userId = createUser()

            // Given: First checkout with an idempotency key
            val first = checkoutService.checkout(userId, priceId, idempotencyKey = "checkout-key-12345678").asSuccess()

            // When: Same request is repeated with the same key
            val second = checkoutService.checkout(userId, priceId, idempotencyKey = "checkout-key-12345678").asSuccess()

            // Then: Same invoice and payment attempt are returned
            assertEquals(first.invoiceId, second.invoiceId)
            assertEquals(first.paymentAttemptId, second.paymentAttemptId)
            assertEquals(first.gatewayUrl, second.gatewayUrl)
            assertEquals(first.providerCode, second.providerCode)
            assertEquals(first.amount, second.amount)

            // And: No additional records were created
            val invoices = invoiceRepository.findByUserIdAndStatus(
                userId,
                com.gyro.api.subscription.domain.InvoiceStatus.OPEN,
            )
            assertEquals(1, invoices.size, "Only one invoice should exist")

            val attempts = paymentAttemptRepository.findByInvoiceId(first.invoiceId)
            assertEquals(1, attempts.size, "Only one payment attempt should exist")

            // And: BillingProvider was invoked only once
            assertEquals(1, billingProvider.checkoutInvocationCount())
        }

        @Test
        fun `concurrent first requests with the same key invoke the provider once`() {
            val userId = createUser()
            val key = "concurrent-checkout-key-12345678"
            val providerBlock = billingProvider.blockNextCheckout()
            val secondStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val first = executor.submit<CheckoutResponse> {
                    checkoutService.checkout(userId, priceId, idempotencyKey = key)
                }
                assertTrue(providerBlock.entered.await(5, TimeUnit.SECONDS))
                val second = executor.submit<CheckoutResponse> {
                    secondStarted.countDown()
                    checkoutService.checkout(userId, priceId, idempotencyKey = key)
                }
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                providerBlock.release.countDown()

                val firstResponse = first.get(15, TimeUnit.SECONDS).asSuccess()
                val secondResponse = second.get(15, TimeUnit.SECONDS).asSuccess()
                assertEquals(firstResponse, secondResponse)
                assertEquals(1, billingProvider.checkoutInvocationCount())
            } finally {
                providerBlock.release.countDown()
                executor.shutdownNow()
            }
        }
    }

    // ── 2. Expired idempotency key ─────────────────────────────────────

    @Nested
    inner class `Expired idempotency key` {

        @Test
        fun `checkout after TTL expires creates new invoice and payment attempt`() {
            val userId = createUser()

            // Given: First checkout
            val first = checkoutService.checkout(userId, priceId, idempotencyKey = "expired-key-12345678").asSuccess()

            // When: The idempotency record expires (delete it to simulate TTL expiry)
            jdbcTemplate.update(
                "DELETE FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?",
                "checkout:$userId",
                "expired-key-12345678",
            )

            // And: Same checkout is attempted again
            val second = checkoutService.checkout(userId, priceId, idempotencyKey = "expired-key-12345678").asSuccess()

            // Then: New invoice and payment attempt are created
            assertNotEquals(first.invoiceId, second.invoiceId, "Different invoices")
            assertNotEquals(first.paymentAttemptId, second.paymentAttemptId, "Different payment attempts")
            assertNotEquals(first.gatewayUrl, second.gatewayUrl, "Different gateway URLs")
            assertNotEquals(first.providerCode, second.providerCode, "Different provider codes")

            // And: BillingProvider was invoked twice
            assertEquals(2, billingProvider.checkoutInvocationCount())
        }
    }

    // ── 3. Missing idempotency key ─────────────────────────────────────

    @Nested
    inner class `Missing idempotency key` {

        @Test
        fun `two checkouts without idempotency key create independent invoices`() {
            val userId = createUser()

            // Given: Two checkout requests without idempotency key
            val first = checkoutService.checkout(userId, priceId).asSuccess()
            val second = checkoutService.checkout(userId, priceId).asSuccess()

            // Then: Each creates its own invoice and payment attempt
            assertNotEquals(first.invoiceId, second.invoiceId, "Different invoices")
            assertNotEquals(first.paymentAttemptId, second.paymentAttemptId, "Different payment attempts")

            // And: BillingProvider was invoked twice
            assertEquals(2, billingProvider.checkoutInvocationCount())
        }
    }

    // ── 4. Different users never share idempotency state ───────────────

    @Nested
    inner class `User isolation` {

        @Test
        fun `same idempotency key for different users creates independent checkouts`() {
            val userA = createUser()
            val userB = createUser()

            // Given: User A checks out with a key
            val checkoutA = checkoutService.checkout(userA, priceId, idempotencyKey = "shared-key-12345678").asSuccess()

            // When: User B checks out with the same key
            val checkoutB = checkoutService.checkout(userB, priceId, idempotencyKey = "shared-key-12345678").asSuccess()

            // Then: Different invoices and payment attempts
            assertNotEquals(checkoutA.invoiceId, checkoutB.invoiceId)
            assertNotEquals(checkoutA.paymentAttemptId, checkoutB.paymentAttemptId)

            // And: Both checkouts succeeded
            assertNotNull(checkoutA.gatewayUrl)
            assertNotNull(checkoutB.gatewayUrl)

            // And: BillingProvider was invoked twice
            assertEquals(2, billingProvider.checkoutInvocationCount())
        }
    }

    // ── 5. Different idempotency keys create independent checkouts ─────

    @Nested
    inner class `Different idempotency keys` {

        @Test
        fun `different keys for same user create independent checkouts`() {
            val userId = createUser()

            // Given: Two checkouts with different keys
            val first = checkoutService.checkout(userId, priceId, idempotencyKey = "key-alpha-12345678").asSuccess()
            val second = checkoutService.checkout(userId, priceId, idempotencyKey = "key-bravo-12345678").asSuccess()

            // Then: Different invoices and payment attempts
            assertNotEquals(first.invoiceId, second.invoiceId)
            assertNotEquals(first.paymentAttemptId, second.paymentAttemptId)
            assertNotEquals(first.gatewayUrl, second.gatewayUrl)

            // And: BillingProvider was invoked twice
            assertEquals(2, billingProvider.checkoutInvocationCount())
        }
    }

    // ── 6. Checkout creates correct records ─────────────────────────────

    @Nested
    inner class `Checkout record creation` {

        @Test
        fun `provider checkout executes outside a database transaction`() {
            val userId = createUser()

            checkoutService.checkout(userId, priceId, idempotencyKey = "transaction-boundary-12345678").asSuccess()

            assertEquals(listOf(false), billingProvider.transactionStates())
        }

        @Test
        fun `checkout creates invoice with correct amounts and period`() {
            val userId = createUser()

            val result = checkoutService.checkout(userId, priceId, idempotencyKey = "record-test-12345678").asSuccess()

            // Then: Invoice is created with correct data
            val invoice = invoiceRepository.findById(result.invoiceId).get()
            assertEquals(userId, invoice.userId)
            assertEquals(planId, invoice.planId)
            assertEquals(BigDecimal("19.99"), invoice.amountDue.amount)
            assertEquals("IRR", invoice.amountDue.currency)
            assertEquals(com.gyro.api.subscription.domain.InvoiceStatus.OPEN, invoice.status)
            assertEquals(priceId, invoice.subscriptionPriceId)

            // And: Payment attempt is created with correct data
            val attempt = paymentAttemptRepository.findByInvoiceId(result.invoiceId).first()
            assertEquals(result.invoiceId, attempt.invoiceId)
            assertEquals(com.gyro.api.subscription.domain.PaymentProvider.PAYPING, attempt.provider)
            assertEquals(com.gyro.api.subscription.domain.PaymentAttemptStatus.PENDING, attempt.status)
            assertEquals(BigDecimal("19.99"), attempt.amount.amount)
        }

        @Test
        fun `checkout with promotion code stores it on invoice`() {
            val userId = createUser()
            seedPromotion("SAVE20", "PERCENTAGE_DISCOUNT", BigDecimal("20.00"))

            val result = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-test-12345678",
                promotionCode = "SAVE20",
            ).asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            assertEquals("SAVE20", invoice.promotionCode)
            assertEquals(BigDecimal("15.99"), invoice.amountAfterDiscount.amount)
            assertEquals(BigDecimal("15.99"), result.amount.amount)
            assertEquals(1, countPromotionRows("SAVE20", "RESERVED"))
        }

        @Test
        fun `promotion applies on top of server derived catalog price`() {
            val userId = createUser()
            val discountedPriceId = seedPrice(
                planId,
                90,
                BigDecimal("80.00"),
                "IRR",
                baseAmount = BigDecimal("100.00"),
                discountPercent = BigDecimal("20.00"),
            )
            seedPromotion("STACK10", "PERCENTAGE_DISCOUNT", BigDecimal("10.00"))

            val result = checkoutService.checkout(
                userId,
                discountedPriceId,
                idempotencyKey = "catalog-promo-stack-12345678",
                promotionCode = "STACK10",
            ).asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            val attempt = paymentAttemptRepository.findById(result.paymentAttemptId).get()
            assertEquals(BigDecimal("80.00"), invoice.amountDue.amount)
            assertEquals(BigDecimal("72.00"), invoice.amountAfterDiscount.amount)
            assertEquals(BigDecimal("72.00"), attempt.amount.amount)
            assertEquals(BigDecimal("72.00"), result.amount.amount)
        }

        @Test
        fun `checkout normalizes promotion code before storing it on invoice`() {
            val userId = createUser()
            seedPromotion("TRIMMED", "FIXED_DISCOUNT", BigDecimal("5.00"))

            val result = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-normalize-12345678",
                promotionCode = " trimmed ",
            ).asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            assertEquals("TRIMMED", invoice.promotionCode)
            assertEquals(BigDecimal("14.99"), invoice.amountAfterDiscount.amount)
        }

        @Test
        fun `fixed promotion cannot create a zero-value provider checkout`() {
            val userId = createUser()
            seedPromotion("NOFREECHECKOUT", "FIXED_DISCOUNT", BigDecimal("19.99"))

            val error = assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(
                    userId,
                    priceId,
                    idempotencyKey = "promo-no-free-checkout-12345678",
                    promotionCode = "NOFREECHECKOUT",
                )
            }

            assertEquals("promotion_free_checkout_unsupported", error.reasonCode)
            assertEquals(0, paymentAttemptRepository.findAll().count { it.amount.amount == BigDecimal.ZERO })
        }

        @Test
        fun `free-days promotion extends invoice period without changing amount`() {
            val userId = createUser()
            seedPromotion("FREEDAYS", "FREE_DAYS", BigDecimal("7.00"))

            val result = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-free-days-12345678",
                promotionCode = "FREEDAYS",
            ).asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            assertEquals(BigDecimal("19.99"), invoice.amountAfterDiscount.amount)
            assertEquals(37, ChronoUnit.DAYS.between(invoice.periodStart, invoice.periodEnd))
        }

        @Test
        fun `repeating checkout with same idempotency key does not duplicate redemption`() {
            val userId = createUser()
            val promotionId = seedPromotion("REPLAY", "PERCENTAGE_DISCOUNT", BigDecimal("10.00"))

            val first = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-replay-12345678",
                promotionCode = "REPLAY",
            ).asSuccess()
            val second = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-replay-12345678",
                promotionCode = "REPLAY",
            ).asSuccess()

            assertEquals(first.invoiceId, second.invoiceId)
            assertEquals(1, countPromotionRows(promotionId, "RESERVED"))
        }

        @Test
        fun `same promotion and idempotency key for different users creates independent reservations`() {
            val userA = createUser()
            val userB = createUser()
            val promotionId = seedPromotion(
                "SHAREDKEY",
                "PERCENTAGE_DISCOUNT",
                BigDecimal("10.00"),
                maxRedemptions = 2,
                perUserLimit = 1,
            )

            val checkoutA = checkoutService.checkout(
                userA,
                priceId,
                idempotencyKey = "shared-promo-key-12345678",
                promotionCode = "SHAREDKEY",
            ).asSuccess()
            val checkoutB = checkoutService.checkout(
                userB,
                priceId,
                idempotencyKey = "shared-promo-key-12345678",
                promotionCode = "SHAREDKEY",
            ).asSuccess()

            assertNotEquals(checkoutA.invoiceId, checkoutB.invoiceId)
            assertEquals(2, countPromotionRows(promotionId, "RESERVED"))
            assertEquals(1, countPromotionRowsForUser(userA, promotionId, "RESERVED"))
            assertEquals(1, countPromotionRowsForUser(userB, promotionId, "RESERVED"))
        }

        @Test
        fun `paid invoice consumes promotion exactly once`() {
            val userId = createUser()
            val promotionId = seedPromotion("PAIDONCE", "PERCENTAGE_DISCOUNT", BigDecimal("10.00"))
            val checkout = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-paid-once-12345678",
                promotionCode = "PAIDONCE",
            ).asSuccess()

            val firstPaid = invoiceService.markPaid(checkout.invoiceId)
            val secondPaid = invoiceService.markPaid(checkout.invoiceId)

            assertEquals(firstPaid.id, secondPaid.id)
            assertEquals(0, countPromotionRows(promotionId, "RESERVED"))
            assertEquals(1, countPromotionRows(promotionId, "REDEEMED"))
        }

        @Test
        fun `affiliate discount and commission are snapshotted and created exactly once`() {
            val userId = createUser()
            val promotionId = seedPromotion("AFFILIATE15", "PERCENTAGE_DISCOUNT", BigDecimal("15.00"))
            val affiliateId = seedAffiliate(promotionId, BigDecimal("10.00"))
            val checkout = checkoutService.checkout(userId, priceId, "affiliate-paid-12345678", "AFFILIATE15").asSuccess()

            assertEquals(BigDecimal("16.99"), checkout.amount.amount)
            assertEquals(0L, countAffiliateCommissions(affiliateId))

            // Admin edits after checkout must not rewrite the reservation's financial terms.
            jdbcTemplate.update("update affiliates set commission_percentage = 20 where id = ?", affiliateId)
            jdbcTemplate.update("update promotions set value = 25 where id = ?", promotionId)
            invoiceService.markPaid(checkout.invoiceId)
            invoiceService.markPaid(checkout.invoiceId)

            val row = jdbcTemplate.queryForMap("select * from affiliate_commissions where affiliate_id = ?", affiliateId)
            assertEquals(1L, countAffiliateCommissions(affiliateId))
            assertEquals(BigDecimal("15.00"), row["discount_percentage_snapshot"])
            assertEquals(BigDecimal("10.00"), row["commission_percentage_snapshot"])
            assertEquals(BigDecimal("1.70"), row["earning_amount"])
        }

        @Test
        fun `affiliate rejects existing paying customer self referral and inactive affiliate`() {
            val existing = createUser()
            val owner = createUser()
            val promotionId = seedPromotion("AFFELIGIBILITY", "PERCENTAGE_DISCOUNT", BigDecimal("15.00"))
            val affiliateId = seedAffiliate(promotionId, BigDecimal("10.00"), owner)
            jdbcTemplate.update("insert into invoices (id, user_id, plan_id, period_start, period_end, amount_due, currency, amount_after_discount, discount_currency, status, manual, subscription_price_id) values (gen_random_uuid(), ?, ?, now(), now() + interval '30 days', 100, 'IRR', 100, 'IRR', 'PAID', false, ?)", existing, planId, priceId)

            val existingError = assertThrows(com.gyro.api.common.error.PromotionException::class.java) { promotionService.validateForCheckout("AFFELIGIBILITY", existing, planId, priceId, com.gyro.api.subscription.domain.Money(BigDecimal("100"), "IRR"), Instant.now().plus(30, ChronoUnit.DAYS)) }
            assertEquals("affiliate_existing_paying_customer", existingError.reasonCode)
            val selfError = assertThrows(com.gyro.api.common.error.PromotionException::class.java) { promotionService.validateForCheckout("AFFELIGIBILITY", owner, planId, priceId, com.gyro.api.subscription.domain.Money(BigDecimal("100"), "IRR"), Instant.now().plus(30, ChronoUnit.DAYS)) }
            assertEquals("affiliate_self_referral", selfError.reasonCode)
            jdbcTemplate.update("update affiliates set status = 'INACTIVE' where id = ?", affiliateId)
            val inactiveError = assertThrows(com.gyro.api.common.error.PromotionException::class.java) { promotionService.validateForCheckout("AFFELIGIBILITY", createUser(), planId, priceId, com.gyro.api.subscription.domain.Money(BigDecimal("100"), "IRR"), Instant.now().plus(30, ChronoUnit.DAYS)) }
            assertEquals("affiliate_inactive", inactiveError.reasonCode)
        }

        @Test
        fun `concurrent first affiliate payments both verify and create one acquisition commission`() {
            val userId = createUser()
            val promotionA = seedPromotion("AFFCONCURRENTA", "PERCENTAGE_DISCOUNT", BigDecimal("15.00"))
            val promotionB = seedPromotion("AFFCONCURRENTB", "PERCENTAGE_DISCOUNT", BigDecimal("15.00"))
            seedAffiliate(promotionA, BigDecimal("10.00"))
            seedAffiliate(promotionB, BigDecimal("10.00"))
            val checkoutA =
                checkoutService.checkout(userId, priceId, "affiliate-concurrent-a-12345678", "AFFCONCURRENTA")
                    .asSuccess()
            val checkoutB =
                checkoutService.checkout(userId, priceId, "affiliate-concurrent-b-12345678", "AFFCONCURRENTB")
                    .asSuccess()
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val futures = listOf(checkoutA, checkoutB).mapIndexed { index, checkout ->
                    executor.submit {
                        barrier.await(5, TimeUnit.SECONDS)
                        paymentVerificationService.applyVerifiedPayment(
                            checkout.paymentAttemptId,
                            "affiliate-concurrent-ref-$index",
                            PaymentConfirmation(
                                providerRefId = "affiliate-concurrent-ref-$index",
                                amount = checkout.amount,
                                verifiedAt = Instant.now(),
                                cardLast4 = null,
                            ),
                        )
                    }
                }
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertEquals(InvoiceStatus.PAID, invoiceRepository.findById(checkoutA.invoiceId).orElseThrow().status)
            assertEquals(InvoiceStatus.PAID, invoiceRepository.findById(checkoutB.invoiceId).orElseThrow().status)
            assertEquals(
                PaymentAttemptStatus.VERIFIED,
                paymentAttemptRepository.findById(checkoutA.paymentAttemptId).orElseThrow().status
            )
            assertEquals(
                PaymentAttemptStatus.VERIFIED,
                paymentAttemptRepository.findById(checkoutB.paymentAttemptId).orElseThrow().status
            )
            assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                    "select count(*) from affiliate_commissions where referred_user_id = ?",
                    Long::class.java,
                    userId
                )
            )
        }

        @Test
        fun `abandoned open invoice reservation expires and allows promo reuse`() {
            val userId = createUser()
            val promotionId = seedPromotion("EXPIREOPEN", "FIXED_DISCOUNT", BigDecimal("1.00"))

            checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-open-a-12345678",
                promotionCode = "EXPIREOPEN",
            ).asSuccess()

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(userId, priceId, idempotencyKey = "promo-open-b-12345678", promotionCode = "EXPIREOPEN")
            }

            jdbcTemplate.update(
                "update promotion_redemptions set reservation_expires_at = now() - interval '1 minute' where promotion_id = ?",
                promotionId,
            )

            val retry = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-open-c-12345678",
                promotionCode = "EXPIREOPEN",
            ).asSuccess()

            assertNotNull(retry.invoiceId)
            assertEquals(2, countPromotionRows(promotionId, "RESERVED"))
        }

        @Test
        fun `expired reservation cannot be redeemed by paying old invoice`() {
            val userId = createUser()
            val promotionId = seedPromotion("PAYEXPIRED", "FIXED_DISCOUNT", BigDecimal("1.00"))

            val checkout = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-pay-expired-12345678",
                promotionCode = "PAYEXPIRED",
            ).asSuccess()

            jdbcTemplate.update(
                "update promotion_redemptions set reservation_expires_at = now() - interval '1 minute' where promotion_id = ?",
                promotionId,
            )

            val ex = assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                invoiceService.markPaid(checkout.invoiceId)
            }

            assertEquals("promotion_reservation_expired", ex.reasonCode)
            assertEquals(1, countPromotionRows(promotionId, "RESERVED"))
            assertEquals(0, countPromotionRows(promotionId, "REDEEMED"))
        }

        @Test
        fun `expired promotion code is rejected`() {
            val userId = createUser()
            seedPromotion("EXPIRED", "PERCENTAGE_DISCOUNT", BigDecimal("10.00"), startsOffsetDays = -10, endsOffsetDays = -1)

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(
                    userId,
                    priceId,
                    idempotencyKey = "promo-expired-12345678",
                    promotionCode = "EXPIRED",
                )
            }
        }

        @Test
        fun `plan mismatch promotion code is rejected`() {
            val userId = createUser()
            val otherPlanId = seedPlan("OTHER_${System.nanoTime()}", "Other", false)
            seedPromotion("WRONGPLAN", "PERCENTAGE_DISCOUNT", BigDecimal("10.00"), applicablePlanId = otherPlanId)

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(
                    userId,
                    priceId,
                    idempotencyKey = "promo-plan-mismatch-12345678",
                    promotionCode = "WRONGPLAN",
                )
            }

            jdbcTemplate.update("DELETE FROM promotions WHERE applicable_plan_id = ?", otherPlanId)
            jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", otherPlanId)
        }

        @Test
        fun `per-user promotion limit is enforced`() {
            val userId = createUser()
            seedPromotion("ONCE", "FIXED_DISCOUNT", BigDecimal("1.00"))

            checkoutService.checkout(userId, priceId, idempotencyKey = "promo-once-a-12345678", promotionCode = "ONCE").asSuccess()

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(userId, priceId, idempotencyKey = "promo-once-b-12345678", promotionCode = "ONCE")
            }
        }

        @Test
        fun `max promotion redemption limit is enforced`() {
            val userA = createUser()
            val userB = createUser()
            seedPromotion("GLOBALONCE", "FIXED_DISCOUNT", BigDecimal("1.00"), maxRedemptions = 1)

            checkoutService.checkout(userA, priceId, idempotencyKey = "promo-global-a-12345678", promotionCode = "GLOBALONCE").asSuccess()

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(userB, priceId, idempotencyKey = "promo-global-b-12345678", promotionCode = "GLOBALONCE")
            }
        }

        @Test
        fun `100 percent percentage promotion is rejected`() {
            val userId = createUser()
            seedPromotion("FREE100", "PERCENTAGE_DISCOUNT", BigDecimal("100.00"))

            assertThrows(com.gyro.api.common.error.PromotionException::class.java) {
                checkoutService.checkout(userId, priceId, idempotencyKey = "promo-free-100-12345678", promotionCode = "FREE100")
            }
        }

        @Test
        fun `database rejects non-normalized promotion code`() {
            assertThrows(DataIntegrityViolationException::class.java) {
                seedPromotion("lowercase", "FIXED_DISCOUNT", BigDecimal("1.00"))
            }
        }
    }

    // ── 7. Checkout with active subscription extends period ─────────────

    @Nested
    inner class `Checkout with active subscription` {

        @Test
        fun `checkout for active subscriber starts period from current period end`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

            val result = checkoutService.checkout(userId, priceId, idempotencyKey = "renewal-test-12345678").asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            // Period should start from current period end (renewal), not from now
            assertEquals(periodEnd, invoice.periodStart)
        }
    }

    // ── 8. Amount consistency ──────────────────────────────────────────

    @Nested
    inner class `Amount consistency` {

        @Test
        fun `invoice amount matches payment attempt amount`() {
            val userId = createUser()

            val result = checkoutService.checkout(userId, priceId, idempotencyKey = "amount-test-12345678").asSuccess()

            val invoice = invoiceRepository.findById(result.invoiceId).get()
            val attempt = paymentAttemptRepository.findByInvoiceId(result.invoiceId).first()

            // Invoice and payment attempt must agree on amount
            assertEquals(invoice.amountDue.amount, attempt.amount.amount)
            assertEquals(invoice.amountDue.currency, attempt.amount.currency)
            assertEquals(result.amount.amount, invoice.amountDue.amount)
        }
    }

    // ── 9. Provider failure rollback ───────────────────────────────────

    @Nested
    inner class `Provider failure rollback` {

        @Test
        fun `provider failure marks payment attempt as CREATE_FAILED and returns empty response`() {
            val userId = createUser()
            val key = "fail-test-${System.nanoTime()}"
            billingProvider.enqueueCheckoutResult(CheckoutResult.Failure("PROVIDER_TIMEOUT", "Connection timed out"))

            // When: Checkout fails at provider level
            val result = checkoutService.checkout(userId, priceId, idempotencyKey = key)

            // Then: Response is a typed failure (not a successful checkout)
            val failed = result as? CheckoutResponse.Failed
                ?: throw AssertionError("Expected CheckoutResponse.Failed but was $result")
            assertEquals("Connection timed out", failed.reason)

            // Then: Invoice was still created (billing record preserved for reconciliation)
            val invoices = invoiceRepository.findByUserIdAndStatus(
                userId,
                com.gyro.api.subscription.domain.InvoiceStatus.OPEN,
            )
            assertEquals(1, invoices.size)

            // Then: Payment attempt is marked as CREATE_FAILED
            val attempt = paymentAttemptRepository.findByInvoiceId(invoices.first().id!!).first()
            assertEquals(PaymentAttemptStatus.CREATE_FAILED, attempt.status)

            // And: The durable failure is replayed without invoking the provider again.
            val replay = checkoutService.checkout(userId, priceId, idempotencyKey = key)
            assertEquals(result, replay)
            assertEquals(1, billingProvider.checkoutInvocationCount())
            assertEquals(listOf(false), billingProvider.transactionStates())
        }

        @Test
        fun `provider failure releases promotion reservation and allows retry with new key`() {
            val userId = createUser()
            val promotionId = seedPromotion("FAILRETRY", "FIXED_DISCOUNT", BigDecimal("1.00"))
            billingProvider.enqueueCheckoutResult(CheckoutResult.Failure("PROVIDER_TIMEOUT", "Connection timed out"))

            val failed = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-fail-a-12345678",
                promotionCode = "FAILRETRY",
            )

            assertTrue(failed is CheckoutResponse.Failed)
            assertEquals(1, countPromotionRows(promotionId, "RELEASED"))

            val retry = checkoutService.checkout(
                userId,
                priceId,
                idempotencyKey = "promo-fail-b-12345678",
                promotionCode = "FAILRETRY",
            ).asSuccess()

            assertNotNull(retry.invoiceId)
            assertEquals(1, countPromotionRows(promotionId, "RESERVED"))
            assertEquals(1, countPromotionRows(promotionId, "RELEASED"))
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun CheckoutResponse.asSuccess(): CheckoutResponse.Success =
        this as? CheckoutResponse.Success
            ?: throw AssertionError("Expected CheckoutResponse.Success but was $this")

    private fun createUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role,
                email_verification_status, phone_verification_status,
                status, created_at, updated_at
            ) values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "user-${System.nanoTime()}-${id.toString().take(8)}@example.com",
        )
        return id
    }

    private fun seedPlan(code: String, name: String, free: Boolean): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_plans (code, name, free, active, grace_period_days)
            values (?, ?, ?, true, 7)
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            name,
            free,
        ) ?: error("Expected plan id")
    }

    private fun seedPrice(
        planId: Long,
        billingPeriodDays: Int,
        amount: BigDecimal,
        currency: String,
        baseAmount: BigDecimal = amount,
        discountPercent: BigDecimal = BigDecimal.ZERO,
    ): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, active, valid_from)
            values (?, ?, ?, ?, ?, ?, true, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            planId,
            billingPeriodDays,
            baseAmount,
            discountPercent,
            amount,
            currency,
        ) ?: error("Expected price id")
    }

    private fun seedPromotion(
        code: String,
        type: String,
        value: BigDecimal,
        applicablePlanId: Long? = planId,
        maxRedemptions: Int? = null,
        perUserLimit: Int = 1,
        startsOffsetDays: Long = -1,
        endsOffsetDays: Long? = 30,
    ): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into promotions (
                code, type, value, applicable_plan_id, starts_at, ends_at,
                max_redemptions, per_user_redemption_limit, active, internal_notes
            )
            values (?, ?, ?, ?, now() + (? || ' days')::interval, case when ? is null then null else now() + (? || ' days')::interval end, ?, ?, true, 'private test note')
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            type,
            value,
            applicablePlanId,
            startsOffsetDays,
            endsOffsetDays,
            endsOffsetDays,
            maxRedemptions,
            perUserLimit,
        ) ?: error("Expected promotion id")
    }

    private fun seedAffiliate(promotionId: Long, commission: BigDecimal, linkedUserId: UUID? = null): UUID =
        jdbcTemplate.queryForObject("insert into affiliates (display_name, promotion_id, linked_user_id, commission_percentage) values ('Test affiliate', ?, ?, ?) returning id", UUID::class.java, promotionId, linkedUserId, commission)!!

    private fun countAffiliateCommissions(affiliateId: UUID): Long = jdbcTemplate.queryForObject("select count(*) from affiliate_commissions where affiliate_id = ?", Long::class.java, affiliateId) ?: 0

    private fun promotionId(code: String): Long =
        jdbcTemplate.queryForObject("select id from promotions where code = ?", Long::class.java, code)
            ?: error("Expected promotion id")

    private fun countPromotionRows(code: String, status: String): Long =
        countPromotionRows(promotionId(code), status)

    private fun countPromotionRows(promotionId: Long, status: String): Long =
        jdbcTemplate.queryForObject(
            "select count(*) from promotion_redemptions where promotion_id = ? and status = ?",
            Long::class.java,
            promotionId,
            status,
        ) ?: 0

    private fun countPromotionRowsForUser(userId: UUID, promotionId: Long, status: String): Long =
        jdbcTemplate.queryForObject(
            "select count(*) from promotion_redemptions where user_id = ? and promotion_id = ? and status = ?",
            Long::class.java,
            userId,
            promotionId,
            status,
        ) ?: 0

    private fun seedSubscription(
        userId: UUID,
        planId: Long,
        status: SubscriptionStatus,
        periodStart: Instant,
        periodEnd: Instant,
    ): com.gyro.api.subscription.domain.UserSubscription {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end
            ) values (?, ?, ?, ?::timestamptz, ?::timestamptz, false)
            """.trimIndent(),
            userId,
            planId,
            status.name,
            periodStart.toString(),
            periodEnd.toString(),
        )
        return userSubscriptionRepository.findByUserId(userId).get()
    }
}
