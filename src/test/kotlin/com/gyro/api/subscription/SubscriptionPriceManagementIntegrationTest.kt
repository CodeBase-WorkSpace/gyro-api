package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.PromotionException
import com.gyro.api.common.error.SubscriptionPriceManagementException
import com.gyro.api.subscription.application.CreateSubscriptionPriceCommand
import com.gyro.api.subscription.application.PromotionService
import com.gyro.api.subscription.application.SubscriptionPriceManagementService
import com.gyro.api.subscription.domain.Money
import com.gyro.api.subscription.web.AdminSubscriptionPriceResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(properties = ["app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.rate-limit.enabled=false"])
class SubscriptionPriceManagementIntegrationTest(
    @Autowired private val service: SubscriptionPriceManagementService,
    @Autowired private val promotionService: PromotionService,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private lateinit var actorId: UUID
    private var paidPlanId = 0L
    private var freePlanId = 0L
    private var activePriceId = 0L

    @BeforeEach
    fun setUp() {
        actorId = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at) VALUES (?, ?, '{noop}Password123', 'ADMIN', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())", actorId, "price-admin-${System.nanoTime()}@example.com")
        paidPlanId = insertPlan("PRICE_PAID_${System.nanoTime()}", false)
        freePlanId = insertPlan("PRICE_FREE_${System.nanoTime()}", true)
        activePriceId = insertPrice(paidPlanId, 30, "100000.00", true)
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM promotion_redemptions WHERE promotion_id IN (SELECT id FROM promotions WHERE applicable_plan_id IN (?, ?))", paidPlanId, freePlanId)
        jdbc.update("DELETE FROM promotions WHERE applicable_plan_id IN (?, ?)", paidPlanId, freePlanId)
        jdbc.update("DELETE FROM account_audit_events WHERE actor_user_id = ? OR target_user_id = ?", actorId, actorId)
        jdbc.update("DELETE FROM subscription_prices WHERE plan_id IN (?, ?)", paidPlanId, freePlanId)
        jdbc.update("DELETE FROM subscription_plans WHERE id IN (?, ?)", paidPlanId, freePlanId)
        jdbc.update("DELETE FROM users WHERE id = ?", actorId)
    }

    @Test
    fun `immediate replacement atomically retires expected predecessor`() {
        val result = service.create(command(paidPlanId, 30, "150000.00", Instant.now(), activePriceId, "20.00"), actorId)
        assertEquals(activePriceId, result.retired?.id)
        assertFalse(result.retired!!.active)
        assertTrue(result.created.active)
        assertEquals(BigDecimal("150000.00"), result.created.baseAmount)
        assertEquals(BigDecimal("20.00"), result.created.discountPercent)
        assertEquals(BigDecimal("120000.00"), result.created.price.amount)
        val historyItem = AdminSubscriptionPriceResponse.from(result.created)
        assertEquals(BigDecimal("150000.00"), historyItem.baseAmount)
        assertEquals(BigDecimal("20.00"), historyItem.discountPercent)
        assertEquals(BigDecimal("120000.00"), historyItem.amount)
        assertEquals(1, count("SELECT count(*) FROM subscription_prices WHERE plan_id = ? AND billing_period_days = 30 AND active", paidPlanId))
        assertEquals(1, count("SELECT count(*) FROM account_audit_events WHERE actor_user_id = ? AND event_type = 'ADMIN_SUBSCRIPTION_PRICE_CHANGED'", actorId))
    }

    @Test
    fun `schedule persists expected predecessor and activates when it is unchanged`() {
        val scheduled = service.create(command(paidPlanId, 30, "200000.00", Instant.now().plusSeconds(3600), activePriceId, "35.00"), actorId).created
        assertEquals(activePriceId, scheduled.expectedPredecessorId)
        assertEquals(BigDecimal("130000.00"), scheduled.price.amount)
        assertEquals(BigDecimal("35.00"), scheduled.discountPercent)
        jdbc.update("UPDATE subscription_prices SET valid_from = now() - interval '1 minute' WHERE id = ?", scheduled.id)
        service.activateScheduledPrices()
        assertTrue(jdbc.queryForObject("SELECT active FROM subscription_prices WHERE id = ?", Boolean::class.java, scheduled.id!!)!!)
        assertFalse(jdbc.queryForObject("SELECT active FROM subscription_prices WHERE id = ?", Boolean::class.java, activePriceId)!!)
    }

    @Test
    fun `scheduled activation stops when predecessor changed outside the workflow`() {
        val scheduled = service.create(command(paidPlanId, 30, "130000.00", Instant.now().plusSeconds(3600), activePriceId), actorId).created
        jdbc.update("UPDATE subscription_prices SET active = false, valid_until = now() WHERE id = ?", activePriceId)
        val newerPriceId = insertPrice(paidPlanId, 30, "140000.00", true)
        jdbc.update("UPDATE subscription_prices SET valid_from = now() - interval '1 minute' WHERE id = ?", scheduled.id)

        service.activateScheduledPrices()

        assertTrue(jdbc.queryForObject("SELECT active FROM subscription_prices WHERE id = ?", Boolean::class.java, newerPriceId)!!)
        assertFalse(jdbc.queryForObject("SELECT active FROM subscription_prices WHERE id = ?", Boolean::class.java, scheduled.id!!)!!)
        assertNotNull(jdbc.queryForObject("SELECT activation_conflicted_at FROM subscription_prices WHERE id = ?", Instant::class.java, scheduled.id))
    }

    @Test
    fun `immediate replacement is rejected while a schedule awaits reconciliation`() {
        service.create(command(paidPlanId, 30, "130000.00", Instant.now().plusSeconds(3600), activePriceId), actorId)
        assertThrows(SubscriptionPriceManagementException::class.java) {
            service.create(command(paidPlanId, 30, "140000.00", Instant.now(), activePriceId), actorId)
        }
        assertEquals(activePriceId, jdbc.queryForObject("SELECT id FROM subscription_prices WHERE plan_id = ? AND billing_period_days = 30 AND active", Long::class.java, paidPlanId))
    }

    @Test
    fun `concurrent immediate replacements leave one active price`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf("120000.00", "130000.00").map { amount -> Callable { runCatching { service.create(command(paidPlanId, 30, amount, Instant.now(), activePriceId), actorId) } } })
                .map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is SubscriptionPriceManagementException })
            assertEquals(1, count("SELECT count(*) FROM subscription_prices WHERE plan_id = ? AND billing_period_days = 30 AND active", paidPlanId))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `free and paid plan price semantics are enforced`() {
        val free = service.create(command(freePlanId, 0, "0.00", Instant.now(), null), actorId).created
        assertEquals(BigDecimal("0.00"), free.price.amount)
        assertThrows(SubscriptionPriceManagementException::class.java) { service.create(command(freePlanId, -30, "1.00", Instant.now(), null), actorId) }
        assertThrows(SubscriptionPriceManagementException::class.java) { service.create(command(paidPlanId, 0, "0.00", Instant.now(), null), actorId) }
        assertThrows(SubscriptionPriceManagementException::class.java) { service.create(command(paidPlanId, 30, "100000.00", Instant.now(), activePriceId, "100.00"), actorId) }
    }

    @Test
    fun `failed insert rolls back predecessor retirement`() {
        assertThrows(Exception::class.java) { service.create(command(paidPlanId, 30, "999999999999999999.00", Instant.now(), activePriceId), actorId) }
        assertTrue(jdbc.queryForObject("SELECT active FROM subscription_prices WHERE id = ?", Boolean::class.java, activePriceId)!!)
    }

    @Test
    fun `database rejects a final amount that does not match base and discount`() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, active) VALUES (?, 90, 100000.00, 20.00, 90000.00, 'IRR', true)",
                paidPlanId,
            )
        }
    }

    @Test
    fun `price targeted promotion applies only to its exact duration`() {
        val quarterlyId = insertPrice(paidPlanId, 90, "250000.00", true)
        jdbc.update("INSERT INTO promotions (code, type, value, applicable_plan_id, applicable_subscription_price_id, starts_at, active) VALUES ('MONTHLY_ONLY_${actorId.toString().take(6).uppercase()}', 'PERCENTAGE_DISCOUNT', 10, ?, ?, now() - interval '1 minute', true)", paidPlanId, activePriceId)
        val code = jdbc.queryForObject("SELECT code FROM promotions WHERE applicable_subscription_price_id = ?", String::class.java, activePriceId)!!
        assertDoesNotThrow { promotionService.validateForCheckout(code, actorId, paidPlanId, activePriceId, Money(BigDecimal("100000.00"), "IRR"), Instant.now().plusSeconds(86400)) }
        val error = assertThrows(PromotionException::class.java) { promotionService.validateForCheckout(code, actorId, paidPlanId, quarterlyId, Money(BigDecimal("250000.00"), "IRR"), Instant.now().plusSeconds(86400)) }
        assertEquals("promotion_price_mismatch", error.reasonCode)
    }

    private fun command(planId: Long, days: Int, baseAmount: String, validFrom: Instant, expected: Long?, discountPercent: String = "0.00") = CreateSubscriptionPriceCommand(planId, days, BigDecimal(baseAmount), BigDecimal(discountPercent), "IRR", null, validFrom, null, expected)
    private fun insertPlan(code: String, free: Boolean) = jdbc.queryForObject("INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at) VALUES (?, ?, ?, true, 0, now(), now()) RETURNING id", Long::class.java, code, code, free)!!
    private fun insertPrice(planId: Long, days: Int, amount: String, active: Boolean) = jdbc.queryForObject("INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, active) VALUES (?, ?, ?, 0.00, ?, 'IRR', ?) RETURNING id", Long::class.java, planId, days, BigDecimal(amount), BigDecimal(amount), active)!!
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
}
