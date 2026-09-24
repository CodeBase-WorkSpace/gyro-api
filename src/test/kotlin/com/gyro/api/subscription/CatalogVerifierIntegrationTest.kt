package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.CatalogVerifier
import com.gyro.api.subscription.infrastructure.*
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.subscription.catalog-verify-enabled=false",
    ],
)
class CatalogVerifierIntegrationTest(
    @Autowired private val planRepository: SubscriptionPlanRepository,
    @Autowired private val priceRepository: SubscriptionPriceRepository,
    @Autowired private val featureRepository: PlanFeatureRepository,
    @Autowired private val localizationRepository: PlanLocalizationRepository,
    @Autowired private val subscriptionFeatureRepository: SubscriptionFeatureRepository,
    @Autowired private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var verifier: CatalogVerifier

    @BeforeEach
    fun setUp() {
        verifier = CatalogVerifier(
            planRepository, priceRepository, featureRepository,
            localizationRepository, subscriptionFeatureRepository,
            meterRegistryProvider,
            true,
            "test",
        )
        // Restore clean state before each test
        restoreCatalog()
    }

    // ── Valid catalog passes ───────────────────────────────────────────

    @Test
    fun `valid catalog passes verification`() {
        assertDoesNotThrow { verifier.verify() }
    }

    // ── 14. Catalog verifier fails if plan has no active price ──────────

    @Test
    fun `verifier fails if non-FREE plan has no active prices`() {
        // Deactivate all ADVANCED prices
        jdbcTemplate.update(
            """
            UPDATE subscription_prices SET active = false
            WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
            """.trimIndent(),
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("no active prices"))
    }

    // ── 15. Catalog verifier fails if ADVANCED missing feature mappings ──

    @Test
    fun `verifier fails if ADVANCED missing required feature keys`() {
        // Disable one required feature for ADVANCED
        jdbcTemplate.update(
            """
            UPDATE plan_features SET enabled = false
            WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
              AND feature_key = 'advanced_analytics'
            """.trimIndent(),
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("ADVANCED plan is missing active required feature key: advanced_analytics"))
    }

    @Test
    fun `verifier fails if required ADVANCED feature definition is inactive`() {
        jdbcTemplate.update(
            "UPDATE subscription_features SET active = false WHERE key = 'premium_schedules'",
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("Required feature key is inactive or missing: premium_schedules"))
        assertTrue(ex.message!!.contains("ADVANCED plan is missing active required feature key: premium_schedules"))
    }

    // ── Verifier does not false-positive on valid catalog ───────────────

    @Test
    fun `verifier does not report errors for valid catalog`() {
        assertDoesNotThrow { verifier.verify() }
    }

    // ── 17. Catalog verifier fails if localization missing ──────────────

    @Test
    fun `verifier fails if plan missing fa-IR localization`() {
        // Delete fa-IR localization for ADVANCED
        jdbcTemplate.update(
            """
            DELETE FROM plan_localizations
            WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
              AND locale = 'fa-IR'
            """.trimIndent(),
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("missing fa-IR localization"))
    }

    // ── Verifier fails if active feature not mapped ─────────────────────

    @Test
    fun `verifier fails if active feature not referenced by any plan`() {
        // Insert an orphaned feature
        jdbcTemplate.update(
            """
            INSERT INTO subscription_features (key, description, active)
            VALUES ('orphan_feature', 'Not mapped to any plan', true)
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("not mapped to any plan"))
    }

    // ── Verifier fails with inactive plan having active prices ───────────

    @Test
    fun `verifier fails if inactive plan has active prices`() {
        // Deactivate the FREE plan but leave its prices (it has none, so insert one)
        jdbcTemplate.update(
            "UPDATE subscription_plans SET active = false WHERE code = 'FREE'",
        )
        jdbcTemplate.update(
            """
            INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            SELECT id, 0, 0.00, 'IRR', true, now()
            FROM subscription_plans WHERE code = 'FREE'
            """.trimIndent(),
        )

        val ex = assertThrows(IllegalStateException::class.java) { verifier.verify() }
        assertTrue(ex.message!!.contains("Inactive plan") && ex.message!!.contains("active prices"))
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun restoreCatalog() {
        // Re-run the V24 seed logic to restore clean state
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
            VALUES
                ('FREE', 'Free', true, true, 0, now(), now()),
                ('ADVANCED', 'Advanced', false, true, 7, now(), now())
            ON CONFLICT (code) DO UPDATE SET
                name = excluded.name, free = excluded.free, active = excluded.active,
                grace_period_days = excluded.grace_period_days, updated_at = now()
            """.trimIndent(),
        )

        // Restore feature mappings
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_features (key, description, active)
            VALUES
                ('premium_schedules', 'Create and manage recurring meal schedules', true),
                ('advanced_analytics', 'Advanced nutrition analytics and progress insights', true),
                ('data_export', 'Export nutrition and meal history data', true),
                ('future_meal_planning', 'Plan meals beyond the current day', true),
                ('higher_limits', 'Higher usage limits for premium workflows', true)
            ON CONFLICT (key) DO UPDATE SET
                description = EXCLUDED.description,
                active = EXCLUDED.active
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO plan_features (plan_id, feature_key, enabled)
            SELECT p.id, f.key, p.code = 'ADVANCED'
            FROM subscription_plans p, subscription_features f
            WHERE f.key IN ('premium_schedules', 'advanced_analytics', 'data_export', 'future_meal_planning', 'higher_limits')
            ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = EXCLUDED.enabled
            """.trimIndent(),
        )

        // Restore localizations
        jdbcTemplate.execute(
            """
            INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
            SELECT p.id, 'fa-IR', 'رایگان', 'ردیابی روزانه کالری و درشت‌مغذی‌ها'
            FROM subscription_plans p WHERE p.code = 'FREE'
            ON CONFLICT (plan_id, locale) DO NOTHING
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
            SELECT p.id, 'en-US', 'Free', 'Daily calorie and macro tracking'
            FROM subscription_plans p WHERE p.code = 'FREE'
            ON CONFLICT (plan_id, locale) DO NOTHING
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
            SELECT p.id, 'fa-IR', 'پیشرفته', 'برنامه‌ریزی، تحلیل و خروجی داده'
            FROM subscription_plans p WHERE p.code = 'ADVANCED'
            ON CONFLICT (plan_id, locale) DO NOTHING
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
            SELECT p.id, 'en-US', 'Advanced', 'Planning, analytics, and data export'
            FROM subscription_plans p WHERE p.code = 'ADVANCED'
            ON CONFLICT (plan_id, locale) DO NOTHING
            """.trimIndent(),
        )

        // Restore prices (delete provider mappings first, then all prices, re-seed original 3)
        jdbcTemplate.update(
            """
            DELETE FROM provider_price_mappings
            WHERE subscription_price_id IN (
                SELECT id FROM subscription_prices
                WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED'))
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            DELETE FROM subscription_prices
            WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED'))
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active, valid_from)
            SELECT p.id, 30, 1990000.00, 'IRR', null, true, now()
            FROM subscription_plans p WHERE p.code = 'ADVANCED'
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active, valid_from)
            SELECT p.id, 90, 5490000.00, 'IRR', 'RECOMMENDED', true, now()
            FROM subscription_plans p WHERE p.code = 'ADVANCED'
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active, valid_from)
            SELECT p.id, 365, 19900000.00, 'IRR', 'BEST_VALUE', true, now()
            FROM subscription_plans p WHERE p.code = 'ADVANCED'
            """.trimIndent(),
        )

        // Delete any orphaned features
        jdbcTemplate.update(
            "DELETE FROM subscription_features WHERE key = 'orphan_feature'",
        )
    }
}
