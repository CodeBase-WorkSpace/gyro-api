package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    ],
)
class SubscriptionCatalogSeedIntegrationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    // ── 1. Seed runner creates FREE and ADVANCED plans with correct codes ──

    @Test
    fun `seed creates FREE plan with correct properties`() {
        val plan = jdbcTemplate.queryForMap(
            "SELECT code, name, free, active, grace_period_days FROM subscription_plans WHERE code = 'FREE'",
        )
        assertEquals("FREE", plan["code"])
        assertEquals("Free", plan["name"])
        assertEquals(true, plan["free"])
        assertEquals(true, plan["active"])
        assertEquals(0, plan["grace_period_days"])
    }

    @Test
    fun `seed creates ADVANCED plan with correct properties`() {
        val plan = jdbcTemplate.queryForMap(
            "SELECT code, name, free, active, grace_period_days FROM subscription_plans WHERE code = 'ADVANCED'",
        )
        assertEquals("ADVANCED", plan["code"])
        assertEquals("Advanced", plan["name"])
        assertEquals(false, plan["free"])
        assertEquals(true, plan["active"])
        assertEquals(7, plan["grace_period_days"])
    }

    @Test
    fun `exactly two plans exist after seeding`() {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_plans", Int::class.java,
        )
        assertEquals(2, count)
    }

    // ── 2. Seed runner creates 30/90/365 day prices for ADVANCED ───────

    @Test
    fun `ADVANCED plan has three active prices`() {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.active = true
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(3, count)
    }

    @Test
    fun `ADVANCED prices are for 30, 90, and 365 days`() {
        val periods = jdbcTemplate.queryForList(
            """
            SELECT sp.billing_period_days FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.active = true
            ORDER BY sp.billing_period_days
            """.trimIndent(),
        ).map { it["billing_period_days"] as Int }
        assertEquals(listOf(30, 90, 365), periods)
    }

    @Test
    fun `ADVANCED prices are in IRR currency`() {
        val currencies = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT sp.currency FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.active = true
            """.trimIndent(),
        ).map { it["currency"] as String }
        assertEquals(listOf("IRR"), currencies)
    }

    @Test
    fun `ADVANCED 90-day price has RECOMMENDED badge`() {
        val badge = jdbcTemplate.queryForObject(
            """
            SELECT sp.badge FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 90 AND sp.active = true
            """.trimIndent(),
            String::class.java,
        )
        assertEquals("RECOMMENDED", badge)
    }

    @Test
    fun `ADVANCED 365-day price has BEST_VALUE badge`() {
        val badge = jdbcTemplate.queryForObject(
            """
            SELECT sp.badge FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 365 AND sp.active = true
            """.trimIndent(),
            String::class.java,
        )
        assertEquals("BEST_VALUE", badge)
    }

    @Test
    fun `ADVANCED 30-day price has no badge`() {
        val result = jdbcTemplate.queryForMap(
            """
            SELECT sp.badge FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 30 AND sp.active = true
            """.trimIndent(),
        )
        val badgeValue = result["badge"]
        assertTrue(badgeValue == null || badgeValue == "" || badgeValue == "null")
    }

    // ── 3. Seed runner creates feature mappings correctly ───────────────

    @Test
    fun `six subscription features exist`() {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_features WHERE active = true",
            Int::class.java,
        )
        assertEquals(6, count)
    }

    @Test
    fun `ADVANCED plan has all six features enabled`() {
        val features = jdbcTemplate.queryForList(
            """
            SELECT pf.feature_key, pf.enabled FROM plan_features pf
            JOIN subscription_plans p ON pf.plan_id = p.id
            WHERE p.code = 'ADVANCED'
            ORDER BY pf.feature_key
            """.trimIndent(),
        )
        assertEquals(6, features.size)
        assertTrue(features.all { it["enabled"] == true })
        val keys = features.map { it["feature_key"] as String }.toSet()
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
    fun `FREE plan has all six features disabled`() {
        val features = jdbcTemplate.queryForList(
            """
            SELECT pf.feature_key, pf.enabled FROM plan_features pf
            JOIN subscription_plans p ON pf.plan_id = p.id
            WHERE p.code = 'FREE'
            """.trimIndent(),
        )
        assertEquals(6, features.size)
        assertTrue(features.all { it["enabled"] == false })
    }

    // ── 4. Seed runner creates localizations for fa-IR and en-US ────────

    @Test
    fun `ADVANCED plan has fa-IR localization`() {
        val loc = jdbcTemplate.queryForMap(
            """
            SELECT display_name, short_description FROM plan_localizations pl
            JOIN subscription_plans p ON pl.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND pl.locale = 'fa-IR'
            """.trimIndent(),
        )
        assertNotNull(loc["display_name"])
        assertNotNull(loc["short_description"])
    }

    @Test
    fun `ADVANCED plan has en-US localization`() {
        val loc = jdbcTemplate.queryForMap(
            """
            SELECT display_name, short_description FROM plan_localizations pl
            JOIN subscription_plans p ON pl.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND pl.locale = 'en-US'
            """.trimIndent(),
        )
        assertEquals("Advanced", loc["display_name"])
        assertNotNull(loc["short_description"])
    }

    @Test
    fun `FREE plan has fa-IR and en-US localizations`() {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM plan_localizations pl
            JOIN subscription_plans p ON pl.plan_id = p.id
            WHERE p.code = 'FREE'
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(2, count)
    }

    // ── 5. Seed runner creates provider mappings ────────────────────────

    @Test
    fun `ADVANCED prices have PAYPING PROD provider mappings`() {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM provider_price_mappings ppm
            JOIN subscription_prices sp ON ppm.subscription_price_id = sp.id
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.active = true
              AND ppm.provider = 'PAYPING' AND ppm.environment = 'PROD' AND ppm.active = true
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(3, count)
    }

    // ── 6. Seed SQL idempotency: re-running UPSERTs produces no duplicates ─

    @Test
    fun `V24 plan UPSERT SQL is idempotent`() {
        val planCountBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_plans", Int::class.java,
        )

        // Re-run the plan UPSERT from V24
        jdbcTemplate.execute(
            """
            INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
            VALUES
                ('FREE', 'Free', true, true, 0, now(), now()),
                ('ADVANCED', 'Advanced', false, true, 7, now(), now())
            ON CONFLICT (code) DO UPDATE SET
                name = excluded.name,
                free = excluded.free,
                active = excluded.active,
                grace_period_days = excluded.grace_period_days,
                updated_at = now()
            """.trimIndent(),
        )

        val planCountAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_plans", Int::class.java,
        )
        assertEquals(planCountBefore, planCountAfter)
    }

    @Test
    fun `V24 feature UPSERT SQL is idempotent`() {
        val featureCountBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_features", Int::class.java,
        )

        jdbcTemplate.execute(
            """
            INSERT INTO subscription_features (key, description, active) VALUES
                ('premium_schedules', 'Advanced goal scheduling', true),
                ('advanced_analytics', 'Long-range analytics', true),
                ('data_export', 'CSV/JSON data export', true),
                ('future_meal_planning', 'Meal planning calendar', true),
                ('higher_limits', 'Increased limits', true)
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
        )

        val featureCountAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_features", Int::class.java,
        )
        assertEquals(featureCountBefore, featureCountAfter)
    }

    @Test
    fun `V24 localization UPSERT SQL is idempotent`() {
        val locCountBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM plan_localizations", Int::class.java,
        )

        // Re-run localization inserts from V24
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

        val locCountAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM plan_localizations", Int::class.java,
        )
        assertEquals(locCountBefore, locCountAfter)
    }

    // ── 7. FREE plan has no prices ──────────────────────────────────────

    @Test
    fun `FREE plan has no active prices`() {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'FREE' AND sp.active = true
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(0, count)
    }

    // ── 8. Prices use correct amounts ───────────────────────────────────

    @Test
    fun `ADVANCED 30-day price amount is correct`() {
        val amount = jdbcTemplate.queryForObject(
            """
            SELECT sp.amount FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 30 AND sp.active = true
            """.trimIndent(),
            java.math.BigDecimal::class.java,
        )!!
        assertEquals(0, amount.compareTo(java.math.BigDecimal("1990000.00")))
    }

    @Test
    fun `ADVANCED 90-day price amount is correct`() {
        val amount = jdbcTemplate.queryForObject(
            """
            SELECT sp.amount FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 90 AND sp.active = true
            """.trimIndent(),
            java.math.BigDecimal::class.java,
        )!!
        assertEquals(0, amount.compareTo(java.math.BigDecimal("5490000.00")))
    }

    @Test
    fun `ADVANCED 365-day price amount is correct`() {
        val amount = jdbcTemplate.queryForObject(
            """
            SELECT sp.amount FROM subscription_prices sp
            JOIN subscription_plans p ON sp.plan_id = p.id
            WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 365 AND sp.active = true
            """.trimIndent(),
            java.math.BigDecimal::class.java,
        )!!
        assertEquals(0, amount.compareTo(java.math.BigDecimal("19900000.00")))
    }
}
