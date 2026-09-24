package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.InvoiceService
import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.domain.InvoiceStatus
import com.gyro.api.subscription.domain.SubscriptionStatus
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class GrandfatheredPricingIntegrationTest(
    @Autowired private val lifecycleService: SubscriptionLifecycleService,
    @Autowired private val invoiceService: InvoiceService,
    @Autowired private val userSubscriptionRepository: UserSubscriptionRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private var planId: Long = 0L
    private var oldPriceId: Long = 0L
    private var newPriceId: Long = 0L
    private val createdUserIds = mutableListOf<UUID>()
    private val extraPlanIds = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        val uniqueCode = "ADVANCED_${System.nanoTime()}"
        planId = seedPlan(uniqueCode, "Advanced", false)
        // Use different billing periods so both prices can be active simultaneously
        // (partial unique index uk_plan_period_currency_active enforces one active per plan/period/currency)
        oldPriceId = seedPrice(
            planId,
            30,
            BigDecimal("9.99"),
            "IRR",
            baseAmount = BigDecimal("12.49"),
            discountPercent = BigDecimal("20.00"),
        )
        newPriceId = seedPrice(planId, 90, BigDecimal("19.99"), "IRR")
    }

    @AfterEach
    fun tearDown() {
        // Clean up test-created data in FK-safe order to avoid affecting other test classes.
        fun safeDelete(sql: String, vararg args: Any) {
            try { jdbcTemplate.update(sql, *args) } catch (e: Exception) {
                println("DEBUG tearDown DELETE FAILED: $sql — ${e.message}")
            }
        }
        for (userId in createdUserIds) {
            safeDelete("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id::text FROM user_subscriptions WHERE user_id = ?)", userId)
            safeDelete("DELETE FROM subscription_events WHERE user_id = ?", userId)
            safeDelete("DELETE FROM promotion_redemptions WHERE invoice_id IN (SELECT id FROM invoices WHERE user_id = ?)", userId)
            safeDelete("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE user_id = ?)", userId)
            safeDelete("DELETE FROM invoices WHERE user_id = ?", userId)
            safeDelete("DELETE FROM user_subscriptions WHERE user_id = ?", userId)
            safeDelete("DELETE FROM users WHERE id = ?", userId)
        }
        safeDelete("DELETE FROM provider_price_mappings WHERE subscription_price_id IN (SELECT id FROM subscription_prices WHERE plan_id = ?)", planId)
        safeDelete("DELETE FROM promotions WHERE applicable_plan_id = ?", planId)
        safeDelete("DELETE FROM subscription_prices WHERE plan_id = ?", planId)
        safeDelete("DELETE FROM manual_grants WHERE plan_id = ?", planId)
        safeDelete("DELETE FROM plan_features WHERE plan_id = ?", planId)
        safeDelete("DELETE FROM plan_localizations WHERE plan_id = ?", planId)
        safeDelete("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)", planId)
        safeDelete("DELETE FROM invoices WHERE plan_id = ?", planId)
        safeDelete("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id::text FROM user_subscriptions WHERE plan_id = ?)", planId)
        safeDelete("DELETE FROM subscription_events WHERE user_id IN (SELECT user_id FROM user_subscriptions WHERE plan_id = ?)", planId)
        safeDelete("DELETE FROM user_subscriptions WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
        // Also clean up any extra plans created within individual tests
        for (ep in extraPlanIds) {
            jdbcTemplate.update("DELETE FROM provider_price_mappings WHERE subscription_price_id IN (SELECT id FROM subscription_prices WHERE plan_id = ?)", ep)
            jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", ep)
            jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id = ?", ep)
            jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id = ?", ep)
            jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", ep)
        }
        extraPlanIds.clear()
        createdUserIds.clear()
    }

    // ── 1. Subscription snapshots the purchased price ──────────────────

    @Nested
    inner class `Subscription snapshots the purchased price` {

        @Test
        fun `first purchase copies lockedPriceId and lockedPrice from active SubscriptionPrice`() {
            val userId = createUser()

            val invoice = invoiceService.createInvoice(userId, oldPriceId)
            val paid = invoiceService.markPaid(invoice.id!!)
            val sub = lifecycleService.applyPaidInvoice(paid)

            assertEquals(oldPriceId, sub.lockedPriceId)
            assertNotNull(sub.lockedPrice)
            assertEquals(BigDecimal("9.99"), sub.lockedPrice!!.amount)
            assertEquals("IRR", sub.lockedPrice!!.currency)
        }

        @Test
        fun `lockedPrice does not change when catalog price is updated later`() {
            val userId = createUser()

            val invoice = invoiceService.createInvoice(userId, oldPriceId)
            val paid = invoiceService.markPaid(invoice.id!!)
            val sub = lifecycleService.applyPaidInvoice(paid)

            // Deactivate old price, activate new price (simulating admin catalog update)
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            val reloaded = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, reloaded.lockedPriceId)
            assertEquals(BigDecimal("9.99"), reloaded.lockedPrice!!.amount)
        }
    }

    // ── 2. Renewal uses locked pricing ─────────────────────────────────

    @Nested
    inner class `Renewal uses locked pricing` {

        @Test
        fun `renewal invoice uses locked price instead of current catalog price`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Create subscription with old price locked
            val sub = seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // Admin updates catalog to higher price
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // Create and pay a renewal invoice
            val renewalInvoice = invoiceService.createInvoice(userId, newPriceId)
            val paid = invoiceService.markPaid(renewalInvoice.id!!)

            // Verify the invoice was created with the NEW catalog price (InvoiceService reads from catalog)
            assertEquals(BigDecimal("19.99"), renewalInvoice.amountDue.amount)

            val renewed = lifecycleService.applyPaidInvoice(paid)

            // The subscription should still have the old locked price
            assertEquals(oldPriceId, renewed.lockedPriceId)
            assertEquals(BigDecimal("9.99"), renewed.lockedPrice!!.amount)
            assertTrue(renewed.periodEnd!!.isAfter(periodEnd))
        }

        @Test
        fun `multiple consecutive renewals preserve locked price`() {
            val userId = createUser()
            val now = Instant.now()
            var periodStart = now.minus(15, ChronoUnit.DAYS)
            var periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Create subscription with old price locked
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // Admin updates catalog to higher price
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // First renewal
            val invoice1 = invoiceService.createInvoice(userId, newPriceId)
            val paid1 = invoiceService.markPaid(invoice1.id!!)
            lifecycleService.applyPaidInvoice(paid1)

            val afterFirst = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, afterFirst.lockedPriceId)
            assertEquals(BigDecimal("9.99"), afterFirst.lockedPrice!!.amount)

            // Second renewal
            val invoice2 = invoiceService.createInvoice(userId, newPriceId)
            val paid2 = invoiceService.markPaid(invoice2.id!!)
            lifecycleService.applyPaidInvoice(paid2)

            val afterSecond = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, afterSecond.lockedPriceId)
            assertEquals(BigDecimal("9.99"), afterSecond.lockedPrice!!.amount)

            // Third renewal
            val invoice3 = invoiceService.createInvoice(userId, newPriceId)
            val paid3 = invoiceService.markPaid(invoice3.id!!)
            lifecycleService.applyPaidInvoice(paid3)

            val afterThird = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, afterThird.lockedPriceId)
            assertEquals(BigDecimal("9.99"), afterThird.lockedPrice!!.amount)
        }
    }

    // ── 3. Existing subscribers are grandfathered ───────────────────────

    @Nested
    inner class `Existing subscribers are grandfathered` {

        @Test
        fun `subscriber A renews at old price while subscriber B pays new price`() {
            val now = Instant.now()

            // Given: Subscriber A purchases at old price
            val userA = createUser()
            val invoiceA = invoiceService.createInvoice(userA, oldPriceId)
            val paidA = invoiceService.markPaid(invoiceA.id!!)
            lifecycleService.applyPaidInvoice(paidA)

            val subA = userSubscriptionRepository.findByUserId(userA).get()
            assertEquals(oldPriceId, subA.lockedPriceId)
            assertEquals(BigDecimal("9.99"), subA.lockedPrice!!.amount)

            // When: Admin updates catalog to new price
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // And: Subscriber B purchases at new price
            val userB = createUser()
            val invoiceB = invoiceService.createInvoice(userB, newPriceId)
            val paidB = invoiceService.markPaid(invoiceB.id!!)
            lifecycleService.applyPaidInvoice(paidB)

            val subB = userSubscriptionRepository.findByUserId(userB).get()
            assertEquals(newPriceId, subB.lockedPriceId)
            assertEquals(BigDecimal("19.99"), subB.lockedPrice!!.amount)

            // And: Subscriber A renews
            val subAActive = userSubscriptionRepository.findByUserId(userA).get()
            val periodLength = 30L
            val renewalStart = subAActive.periodEnd!!
            val renewalEnd = renewalStart.plus(periodLength, ChronoUnit.DAYS)
            val renewalInvoiceA = invoiceService.createInvoice(userA, newPriceId)
            val paidRenewalA = invoiceService.markPaid(renewalInvoiceA.id!!)
            lifecycleService.applyPaidInvoice(paidRenewalA)

            val subARenewed = userSubscriptionRepository.findByUserId(userA).get()

            // Then: Subscriber A still has old locked price
            assertEquals(oldPriceId, subARenewed.lockedPriceId)
            assertEquals(BigDecimal("9.99"), subARenewed.lockedPrice!!.amount)

            // And: Subscriber B has new locked price
            val subBReloaded = userSubscriptionRepository.findByUserId(userB).get()
            assertEquals(newPriceId, subBReloaded.lockedPriceId)
            assertEquals(BigDecimal("19.99"), subBReloaded.lockedPrice!!.amount)

            // And: Both subscriptions are active
            assertEquals(SubscriptionStatus.ACTIVE, subARenewed.status)
            assertEquals(SubscriptionStatus.ACTIVE, subBReloaded.status)
        }
    }

    // ── 4. Admin migration to new pricing ──────────────────────────────

    @Nested
    inner class `Admin migration to new pricing` {

        @Test
        fun `clearing locked price causes next renewal to use current catalog price`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Given: Subscription with old price locked
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: Admin clears lockedPriceId and lockedPrice
            jdbcTemplate.update(
                "UPDATE user_subscriptions SET locked_price_id = NULL, locked_price_amount = NULL, locked_price_currency = NULL WHERE user_id = ?",
                userId,
            )

            // And: Admin updates catalog to new price
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // And: Next renewal is created with new price
            val renewalInvoice = invoiceService.createInvoice(userId, newPriceId)
            val paid = invoiceService.markPaid(renewalInvoice.id!!)
            lifecycleService.applyPaidInvoice(paid)

            val renewed = userSubscriptionRepository.findByUserId(userId).get()

            // Then: Subscription now uses the new price
            assertEquals(newPriceId, renewed.lockedPriceId)
            assertEquals(BigDecimal("19.99"), renewed.lockedPrice!!.amount)
            assertTrue(renewed.periodEnd!!.isAfter(periodEnd))
        }

        @Test
        fun `admin can migrate subscription to a different plan`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Given: Subscription on ADVANCED plan with old price
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: Admin clears lock and switches to a different plan's price
            val otherPlanId = seedPlan("ENTERPRISE_${System.nanoTime()}", "Enterprise", false)
            extraPlanIds.add(otherPlanId)
            val otherPriceId = seedPrice(otherPlanId, 30, BigDecimal("49.99"), "IRR")

            jdbcTemplate.update(
                "UPDATE user_subscriptions SET locked_price_id = NULL, locked_price_amount = NULL, locked_price_currency = NULL WHERE user_id = ?",
                userId,
            )

            // And: Admin changes plan on the subscription
            jdbcTemplate.update(
                "UPDATE user_subscriptions SET plan_id = ? WHERE user_id = ?",
                otherPlanId,
                userId,
            )

            // And: User renews with the new plan's price
            val renewalInvoice = invoiceService.createInvoice(userId, otherPriceId)
            val paid = invoiceService.markPaid(renewalInvoice.id!!)
            lifecycleService.applyPaidInvoice(paid)

            val renewed = userSubscriptionRepository.findByUserId(userId).get()

            // Then: Subscription is on new plan with new price locked
            assertEquals(otherPlanId, renewed.planId)
            assertEquals(otherPriceId, renewed.lockedPriceId)
            assertEquals(BigDecimal("49.99"), renewed.lockedPrice!!.amount)
        }
    }

    // ── 5. Promotion does not modify the price lock ────────────────────

    @Nested
    inner class `Promotion does not modify the price lock` {

        @Test
        fun `promotion code affects invoice amount but not subscription locked price`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Given: Subscription with a locked price
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: Invoice created with a promotion code
            seedPromotion("SAVE50", BigDecimal("5.00"))
            val invoice = invoiceService.createInvoice(userId, oldPriceId, promotionCode = "SAVE50")
            assertEquals("SAVE50", invoice.promotionCode)
            assertEquals(BigDecimal("9.99"), invoice.amountDue.amount)
            assertEquals(BigDecimal("4.99"), invoice.amountAfterDiscount.amount)

            val paid = invoiceService.markPaid(invoice.id!!)
            lifecycleService.applyPaidInvoice(paid)

            val renewed = userSubscriptionRepository.findByUserId(userId).get()

            // Then: Locked price is unchanged by promotion
            assertEquals(oldPriceId, renewed.lockedPriceId)
            assertEquals(BigDecimal("9.99"), renewed.lockedPrice!!.amount)
        }

        @Test
        fun `renewal without promotion returns to locked price`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Given: Subscription with a locked price
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: First renewal with promotion
            seedPromotion("SAVE50", BigDecimal("5.00"))
            val invoiceWithPromo = invoiceService.createInvoice(userId, oldPriceId, promotionCode = "SAVE50")
            val paid1 = invoiceService.markPaid(invoiceWithPromo.id!!)
            lifecycleService.applyPaidInvoice(paid1)

            val afterPromo = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, afterPromo.lockedPriceId)
            assertEquals(BigDecimal("9.99"), afterPromo.lockedPrice!!.amount)

            // When: Second renewal without promotion
            val invoiceWithoutPromo = invoiceService.createInvoice(userId, oldPriceId)
            assertNull(invoiceWithoutPromo.promotionCode)
            val paid2 = invoiceService.markPaid(invoiceWithoutPromo.id!!)
            lifecycleService.applyPaidInvoice(paid2)

            val afterNoPromo = userSubscriptionRepository.findByUserId(userId).get()

            // Then: Locked price still the same
            assertEquals(oldPriceId, afterNoPromo.lockedPriceId)
            assertEquals(BigDecimal("9.99"), afterNoPromo.lockedPrice!!.amount)
        }
    }

    // ── 6. Invoice amount snapshot preservation ────────────────────────

    @Nested
    inner class `Invoice amount snapshot preservation` {

        @Test
        fun `paid invoice retains its original amount regardless of catalog changes`() {
            val userId = createUser()

            // Given: Invoice created at old price
            val invoice = invoiceService.createInvoice(userId, oldPriceId)
            assertEquals(BigDecimal("9.99"), invoice.amountDue.amount)
            assertEquals("IRR", invoice.amountDue.currency)

            // When: Catalog price changes before payment
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // Then: Invoice still records old price
            val paid = invoiceService.markPaid(invoice.id!!)
            assertEquals(InvoiceStatus.PAID, paid.status)
            assertEquals(BigDecimal("9.99"), paid.amountDue.amount)
            assertEquals("IRR", paid.amountDue.currency)
        }
    }

    // ── 7. Edge cases ──────────────────────────────────────────────────

    @Nested
    inner class `Edge cases` {

        @Test
        fun `expired subscription reactivation preserves original locked price`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(60, ChronoUnit.DAYS)
            val periodEnd = now.minus(1, ChronoUnit.DAYS)

            // Given: Expired subscription with locked price
            seedSubscription(
                userId, planId, SubscriptionStatus.EXPIRED, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: Catalog price has changed
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // And: User reactivates by paying
            val invoice = invoiceService.createInvoice(userId, newPriceId)
            val paid = invoiceService.markPaid(invoice.id!!)
            val reactivated = lifecycleService.applyPaidInvoice(paid)

            // Then: Locked price is preserved from the original subscription
            assertEquals(oldPriceId, reactivated.lockedPriceId)
            assertEquals(BigDecimal("9.99"), reactivated.lockedPrice!!.amount)
            assertEquals(SubscriptionStatus.ACTIVE, reactivated.status)
        }

        @Test
        fun `new subscription after catalog update and lock clear locks the new price`() {
            val userId = createUser()

            // Given: Catalog starts at old price
            val invoice1 = invoiceService.createInvoice(userId, oldPriceId)
            val paid1 = invoiceService.markPaid(invoice1.id!!)
            lifecycleService.applyPaidInvoice(paid1)

            val sub1 = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, sub1.lockedPriceId)

            // When: Admin deactivates old price, activates new
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // And: Admin clears lock and expires the subscription
            val now = Instant.now()
            jdbcTemplate.update(
                """UPDATE user_subscriptions
                   SET status = 'EXPIRED', period_end = ?::timestamptz,
                       locked_price_id = NULL, locked_price_amount = NULL, locked_price_currency = NULL
                   WHERE user_id = ?""",
                now.minus(1, ChronoUnit.DAYS).toString(),
                userId,
            )

            // And: User purchases again at the new price
            val invoice2 = invoiceService.createInvoice(userId, newPriceId)
            val paid2 = invoiceService.markPaid(invoice2.id!!)
            val sub2 = lifecycleService.applyPaidInvoice(paid2)

            // Then: New subscription locks the new price
            assertEquals(newPriceId, sub2.lockedPriceId)
            assertEquals(BigDecimal("19.99"), sub2.lockedPrice!!.amount)
        }

        @Test
        fun `catalog mutation does not affect existing subscription until lock is cleared`() {
            val userId = createUser()
            val now = Instant.now()
            val periodStart = now.minus(15, ChronoUnit.DAYS)
            val periodEnd = now.plus(15, ChronoUnit.DAYS)

            // Given: Subscription with old price locked
            seedSubscription(
                userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd,
                lockedPriceId = oldPriceId,
                lockedPriceAmount = BigDecimal("9.99"),
                lockedPriceCurrency = "IRR",
            )

            // When: Admin deactivates old price and activates new price
            jdbcTemplate.update("UPDATE subscription_prices SET active = false WHERE id = ?", oldPriceId)
            jdbcTemplate.update("UPDATE subscription_prices SET active = true WHERE id = ?", newPriceId)

            // Then: Subscription is unaffected
            val sub = userSubscriptionRepository.findByUserId(userId).get()
            assertEquals(oldPriceId, sub.lockedPriceId)
            assertEquals(BigDecimal("9.99"), sub.lockedPrice!!.amount)
            assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        }

        @Test
        fun `invoice without subscriptionPriceId creates subscription without lock`() {
            val userId = createUser()

            // Given: An invoice with null subscriptionPriceId (e.g. created before V26 migration)
            val invoice = invoiceService.createInvoice(userId, oldPriceId)
            // Simulate a pre-V26 invoice by clearing the subscriptionPriceId
            jdbcTemplate.update("UPDATE invoices SET subscription_price_id = NULL WHERE id = ?", invoice.id)
            val paid = invoiceService.markPaid(invoice.id!!)

            // When: Lifecycle processes the invoice
            val sub = lifecycleService.applyPaidInvoice(paid)

            // Then: Subscription is created but without a price lock (no grandfathering)
            assertEquals(SubscriptionStatus.ACTIVE, sub.status)
            assertNull(sub.lockedPriceId)
            assertNull(sub.lockedPrice)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

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
        createdUserIds.add(id)
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

    private fun seedPromotion(code: String, value: BigDecimal): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into promotions (
                code, type, value, applicable_plan_id, starts_at, ends_at,
                per_user_redemption_limit, active
            )
            values (?, 'FIXED_DISCOUNT', ?, ?, now() - interval '1 day', now() + interval '30 days', 10, true)
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            value,
            planId,
        ) ?: error("Expected promotion id")
    }

    private fun seedSubscription(
        userId: UUID,
        planId: Long,
        status: SubscriptionStatus,
        periodStart: Instant,
        periodEnd: Instant,
        lockedPriceId: Long? = null,
        lockedPriceAmount: BigDecimal? = null,
        lockedPriceCurrency: String? = null,
        cancelAtPeriodEnd: Boolean = false,
        gracePeriodEnd: Instant? = null,
        graceReason: String? = null,
    ): com.gyro.api.subscription.domain.UserSubscription {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end, grace_reason,
                locked_price_id, locked_price_amount, locked_price_currency
            ) values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?::timestamptz, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            planId,
            status.name,
            periodStart.toString(),
            periodEnd.toString(),
            cancelAtPeriodEnd,
            gracePeriodEnd?.toString(),
            graceReason,
            lockedPriceId,
            lockedPriceAmount,
            lockedPriceCurrency,
        )
        return userSubscriptionRepository.findByUserId(userId).get()
    }
}
