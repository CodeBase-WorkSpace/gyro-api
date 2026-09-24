package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class SubscriptionCatalogControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `public plan catalog returns free and advanced without authentication`() {
        mockMvc.get("/api/v1/billing/plans") {
            param("locale", "fa-IR")
        }.andExpect {
            status { isOk() }
            jsonPath("$.plans.length()") { value(2) }
            jsonPath("$.plans[0].code") { value("FREE") }
            jsonPath("$.plans[0].displayName") { value("رایگان") }
            jsonPath("$.plans[0].prices.length()") { value(0) }
            jsonPath("$.plans[1].code") { value("ADVANCED") }
            jsonPath("$.plans[1].displayName") { value("پیشرفته") }
            jsonPath("$.plans[1].prices.length()") { value(3) }
        }
    }

    @Test
    fun `advanced plan returns sorted active prices with expected badges`() {
        mockMvc.get("/api/v1/billing/plans") {
            param("locale", "en-US")
        }.andExpect {
            status { isOk() }
            jsonPath("$.plans[1].code") { value("ADVANCED") }
            jsonPath("$.plans[1].prices[0].billingPeriodDays") { value(30) }
            jsonPath("$.plans[1].prices[0].baseAmount") { value(1990000.00) }
            jsonPath("$.plans[1].prices[0].discountPercent") { value(0.00) }
            jsonPath("$.plans[1].prices[0].amount") { value(1990000.00) }
            jsonPath("$.plans[1].prices[0].currency") { value("IRR") }
            jsonPath("$.plans[1].prices[0].badge") { doesNotExist() }
            jsonPath("$.plans[1].prices[1].billingPeriodDays") { value(90) }
            jsonPath("$.plans[1].prices[1].badge") { value("RECOMMENDED") }
            jsonPath("$.plans[1].prices[2].billingPeriodDays") { value(365) }
            jsonPath("$.plans[1].prices[2].badge") { value("BEST_VALUE") }
        }
    }

    @Test
    fun `public catalog exposes server owned base discount and payable amount`() {
        val planId = jdbcTemplate.queryForObject(
            "SELECT id FROM subscription_plans WHERE code = 'ADVANCED'",
            Long::class.java,
        )!!
        jdbcTemplate.update(
            "UPDATE subscription_prices SET active = false WHERE plan_id = ? AND billing_period_days = 90 AND active",
            planId,
        )
        val discountedPriceId = jdbcTemplate.queryForObject(
            "INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, badge, active) VALUES (?, 90, 6000000.00, 8.50, 5490000.00, 'IRR', 'RECOMMENDED', true) RETURNING id",
            Long::class.java,
            planId,
        )!!

        try {
            mockMvc.get("/api/v1/billing/plans").andExpect {
                status { isOk() }
                jsonPath("$.plans[1].prices[1].baseAmount") { value(6000000.00) }
                jsonPath("$.plans[1].prices[1].discountPercent") { value(8.50) }
                jsonPath("$.plans[1].prices[1].amount") { value(5490000.00) }
            }
        } finally {
            jdbcTemplate.update("DELETE FROM subscription_prices WHERE id = ?", discountedPriceId)
            jdbcTemplate.update(
                "UPDATE subscription_prices SET active = true WHERE plan_id = ? AND billing_period_days = 90 AND id <> ?",
                planId,
                discountedPriceId,
            )
        }
    }

    @Test
    fun `catalog localizes to English and falls back to Persian for unsupported locale`() {
        mockMvc.get("/api/v1/billing/plans") {
            param("locale", "en-US")
        }.andExpect {
            status { isOk() }
            jsonPath("$.plans[0].locale") { value("en-US") }
            jsonPath("$.plans[0].displayName") { value("Free") }
            jsonPath("$.plans[1].locale") { value("en-US") }
            jsonPath("$.plans[1].displayName") { value("Advanced") }
        }

        mockMvc.get("/api/v1/billing/plans") {
            param("locale", "fr-FR")
        }.andExpect {
            status { isOk() }
            jsonPath("$.plans[0].locale") { value("fa-IR") }
            jsonPath("$.plans[0].displayName") { value("رایگان") }
            jsonPath("$.plans[1].locale") { value("fa-IR") }
            jsonPath("$.plans[1].displayName") { value("پیشرفته") }
        }
    }

    @Test
    fun `catalog excludes inactive plans prices and feature definitions`() {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
                VALUES ('INACTIVE_TEST', 'Inactive Test', false, false, 0, now(), now())
                ON CONFLICT (code) DO UPDATE SET active = false, updated_at = now()
                """.trimIndent(),
            )
            jdbcTemplate.update(
                """
                UPDATE subscription_prices
                SET active = false
                WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
                  AND billing_period_days = 30
                """.trimIndent(),
            )
            jdbcTemplate.update(
                "UPDATE subscription_features SET active = false WHERE key = 'data_export'",
            )

            mockMvc.get("/api/v1/billing/plans").andExpect {
                status { isOk() }
                jsonPath("$.plans.length()") { value(2) }
                jsonPath("$.plans[?(@.code == 'INACTIVE_TEST')]") { isEmpty() }
                jsonPath("$.plans[1].prices.length()") { value(2) }
                jsonPath("$.plans[1].prices[?(@.billingPeriodDays == 30)]") { isEmpty() }
                jsonPath("$.plans[1].features[?(@.key == 'data_export')]") { isEmpty() }
            }
        } finally {
            jdbcTemplate.update("DELETE FROM subscription_plans WHERE code = 'INACTIVE_TEST'")
            jdbcTemplate.update(
                """
                UPDATE subscription_prices
                SET active = true
                WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
                  AND billing_period_days = 30
                """.trimIndent(),
            )
            jdbcTemplate.update("UPDATE subscription_features SET active = true WHERE key = 'data_export'")
        }
    }
}
