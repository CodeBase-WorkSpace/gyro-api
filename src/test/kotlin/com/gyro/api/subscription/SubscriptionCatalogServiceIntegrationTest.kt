package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.subscription.application.InvoiceService
import com.gyro.api.subscription.application.SubscriptionCatalogService
import com.gyro.api.subscription.domain.PaymentProvider
import com.gyro.api.subscription.domain.ProviderEnvironment
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class SubscriptionCatalogServiceIntegrationTest(
    @Autowired private val catalogService: SubscriptionCatalogService,
    @Autowired private val invoiceService: InvoiceService,
    @Autowired private val planRepository: SubscriptionPlanRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private var advancedPlanId: Long = 0L
    private var freePlanId: Long = 0L

    @BeforeEach
    fun setUp() {
        advancedPlanId = planRepository.findByCode("ADVANCED")!!.id!!
        freePlanId = planRepository.findByCode("FREE")!!.id!!
    }

    // ── findPlanByCode ─────────────────────────────────────────────────

    @Test
    fun `findPlanByCode returns ADVANCED plan`() {
        val plan = catalogService.findPlanByCode("ADVANCED")
        assertEquals("ADVANCED", plan.code)
        assertFalse(plan.free)
        assertTrue(plan.active)
    }

    @Test
    fun `findPlanByCode returns FREE plan`() {
        val plan = catalogService.findPlanByCode("FREE")
        assertEquals("FREE", plan.code)
        assertTrue(plan.free)
    }

    @Test
    fun `findPlanByCode throws for unknown code`() {
        assertThrows(ResourceNotFoundException::class.java) {
            catalogService.findPlanByCode("NONEXISTENT")
        }
    }

    // ── findActivePrices ───────────────────────────────────────────────

    @Test
    fun `findActivePrices returns three prices for ADVANCED sorted by period`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        assertEquals(3, prices.size)
        val periods = prices.map { it.billingPeriodDays }
        assertEquals(listOf(30, 90, 365), periods)
    }

    @Test
    fun `findActivePrices returns empty for FREE plan`() {
        val prices = catalogService.findActivePrices(freePlanId)
        assertTrue(prices.isEmpty())
    }

    @Test
    fun `findActivePrices returns only active prices`() {
        // Deactivate one price, verify it's excluded
        val prices = catalogService.findActivePrices(advancedPlanId)
        val priceToDeactivate = prices.first()
        jdbcTemplate.update(
            "UPDATE subscription_prices SET active = false WHERE id = ?",
            priceToDeactivate.id,
        )

        val remaining = catalogService.findActivePrices(advancedPlanId)
        assertTrue(remaining.none { it.id == priceToDeactivate.id })

        // Restore for other tests
        jdbcTemplate.update(
            "UPDATE subscription_prices SET active = true WHERE id = ?",
            priceToDeactivate.id,
        )
    }

    @Test
    fun `90-day price has RECOMMENDED badge`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        val ninetyDay = prices.first { it.billingPeriodDays == 90 }
        assertEquals("RECOMMENDED", ninetyDay.badge)
    }

    @Test
    fun `365-day price has BEST_VALUE badge`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        val yearly = prices.first { it.billingPeriodDays == 365 }
        assertEquals("BEST_VALUE", yearly.badge)
    }

    @Test
    fun `30-day price has no badge`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        val monthly = prices.first { it.billingPeriodDays == 30 }
        assertTrue(monthly.badge == null || monthly.badge == "")
    }

    // ── findEnabledFeatures ────────────────────────────────────────────

    @Test
    fun `findEnabledFeatures returns six features for ADVANCED`() {
        val features = catalogService.findEnabledFeatures(advancedPlanId)
        assertEquals(6, features.size)
        assertTrue(features.all { it.enabled })
        val keys = features.map { it.featureKey }.toSet()
        assertEquals(
            setOf(
                "premium_schedules",
                "advanced_analytics",
                "data_export",
                "future_meal_planning",
                "higher_limits",
                "goal_recalibration",
            ),
            keys,
        )
    }

    @Test
    fun `findEnabledFeatures returns empty for FREE plan`() {
        val features = catalogService.findEnabledFeatures(freePlanId)
        assertTrue(features.isEmpty())
    }

    // ── findLocalization ───────────────────────────────────────────────

    @Test
    fun `findLocalization returns en-US for ADVANCED`() {
        val loc = catalogService.findLocalization(advancedPlanId, "en-US")
        assertEquals("en-US", loc.locale)
        assertEquals("Advanced", loc.displayName)
        assertNotNull(loc.shortDescription)
    }

    @Test
    fun `findLocalization returns fa-IR for ADVANCED`() {
        val loc = catalogService.findLocalization(advancedPlanId, "fa-IR")
        assertEquals("fa-IR", loc.locale)
        assertNotNull(loc.displayName)
    }

    @Test
    fun `findLocalization falls back to fa-IR when locale is missing`() {
        // fr-FR doesn't exist, should fall back to fa-IR
        val loc = catalogService.findLocalization(advancedPlanId, "fr-FR")
        assertEquals("fa-IR", loc.locale)
    }

    @Test
    fun `findLocalization throws when no localization exists`() {
        // Create a plan with no localizations
        val planId = jdbcTemplate.queryForObject(
            """
            INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
            VALUES ('NO_LOC', 'No Loc', false, true, 0, now(), now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
        )!!

        assertThrows(ResourceNotFoundException::class.java) {
            catalogService.findLocalization(planId, "en-US")
        }

        // Cleanup
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
    }

    // ── findProviderMapping ────────────────────────────────────────────

    @Test
    fun `findProviderMapping returns PAYPING PROD mapping for ADVANCED prices`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        for (price in prices) {
            val mapping = catalogService.findProviderMapping(
                price.id!!, PaymentProvider.PAYPING, ProviderEnvironment.PROD,
            )
            assertNotNull(mapping.id)
            assertEquals(PaymentProvider.PAYPING, mapping.provider)
            assertEquals(ProviderEnvironment.PROD, mapping.environment)
            assertTrue(mapping.active)
        }
    }

    @Test
    fun `findProviderMapping throws for nonexistent mapping`() {
        val prices = catalogService.findActivePrices(advancedPlanId)
        assertThrows(ResourceNotFoundException::class.java) {
            catalogService.findProviderMapping(
                prices.first().id!!, PaymentProvider.PAYPING, ProviderEnvironment.DEV,
            )
        }
    }

    // ── Invoice creation from catalog (checkout validation) ─────────────

    @Test
    fun `invoice creation uses price from database`() {
        val userId = createUser()
        val prices = catalogService.findActivePrices(advancedPlanId)
        val price = prices.first { it.billingPeriodDays == 30 }

        val invoice = invoiceService.createInvoice(userId, price.id!!)

        assertEquals(price.price.amount, invoice.amountDue.amount)
        assertEquals(price.price.currency, invoice.amountDue.currency)
        assertEquals(price.price.amount, invoice.amountAfterDiscount.amount)
        assertEquals(advancedPlanId, invoice.planId)
    }

    @Test
    fun `invoice creation rejects inactive price`() {
        val userId = createUser()

        // Create and deactivate a price
        val priceId = jdbcTemplate.queryForObject(
            """
            INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            VALUES (?, 30, 1000.00, 'IRR', false, now())
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            advancedPlanId,
        )!!

        assertThrows(ResourceNotFoundException::class.java) {
            invoiceService.createInvoice(userId, priceId)
        }

        // Cleanup
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE id = ?", priceId)
    }

    @Test
    fun `invoice creation rejects nonexistent price`() {
        val userId = createUser()
        assertThrows(ResourceNotFoundException::class.java) {
            invoiceService.createInvoice(userId, 999999L)
        }
    }

    @Test
    fun `invoice period length matches price billing period`() {
        val userId = createUser()
        val prices = catalogService.findActivePrices(advancedPlanId)
        val price = prices.first { it.billingPeriodDays == 90 }

        val invoice = invoiceService.createInvoice(userId, price.id!!)

        val periodDays = java.time.temporal.ChronoUnit.DAYS.between(
            invoice.periodStart, invoice.periodEnd,
        )
        assertEquals(90L, periodDays)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, role,
                email_verification_status, phone_verification_status,
                status, created_at, updated_at
            ) VALUES (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "catalog-test-${System.nanoTime()}@example.com",
        )
        return id
    }
}
