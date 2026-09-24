package com.gyro.api.subscription.web

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.billing.FakeBillingProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.*

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class CheckoutControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired billingProvider: FakeBillingProvider,
    @Value("\${app.billing.return-url}") private val returnUrl: String,
) {
    private val fakeBillingProvider = billingProvider
    private var planId: Long = 0L
    private var priceId: Long = 0L
    private var testUserId: UUID = UUID.randomUUID()
    private val extraPlanIds = mutableListOf<Long>()
    private val extraUserIds = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() {
        fakeBillingProvider.reset()
        extraPlanIds.clear()
        extraUserIds.clear()
        testUserId = createUser()
        val uniqueCode = "ADVANCED_${System.nanoTime()}"
        planId = seedPlan(uniqueCode, "Advanced", false)
        priceId = seedPrice(planId, 30, BigDecimal("19.99"), "IRR")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE scope LIKE 'checkout:%'")
        jdbcTemplate.update(
            "DELETE FROM promotion_redemptions WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)",
            planId,
        )
        jdbcTemplate.update("DELETE FROM promotions WHERE applicable_plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM invoices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
        extraPlanIds.forEach { extraPlanId ->
            jdbcTemplate.update("DELETE FROM promotions WHERE applicable_plan_id = ?", extraPlanId)
            jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", extraPlanId)
            jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id = ?", extraPlanId)
            jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id = ?", extraPlanId)
            jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", extraPlanId)
        }
        extraUserIds.forEach { userId ->
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)
        }
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", testUserId)
    }

    @Nested
    inner class `Authentication required` {
        @Test
        fun `unauthenticated request returns 401`() {
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isUnauthorized() }
            }
        }
    }

    @Nested
    inner class `Successful checkout` {
        @Test
        fun `checkout with valid price returns success with invoice and gateway URL`() {
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
                jsonPath("$.invoiceId") { isNotEmpty() }
                jsonPath("$.paymentAttemptId") { isNotEmpty() }
                jsonPath("$.gatewayUrl") { isNotEmpty() }
            }

            assertEquals("https://api.gyrohealth.ir/api/v1/billing/payping/callback", returnUrl)
            assertEquals(returnUrl, fakeBillingProvider.checkouts.last().returnUrl)
        }

        @Test
        fun `checkout with promotion code applies discount`() {
            seedPromotion("SAVE20", "PERCENTAGE_DISCOUNT", BigDecimal("20.00"))

            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "SAVE20",
                    ),
                )
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }
        }
    }

    @Nested
    inner class `Promotion validation` {
        @Test
        fun `valid promotion returns discount preview without creating checkout records`() {
            seedPromotion("SAVE20", "PERCENTAGE_DISCOUNT", BigDecimal("20.00"))

            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to " save20 ",
                    ),
                )
            }.andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(true) }
                jsonPath("$.promotionCode") { value("SAVE20") }
                jsonPath("$.amountBeforeDiscount.amount") { value(19.99) }
                jsonPath("$.amountAfterDiscount.amount") { value(15.99) }
                jsonPath("$.discountAmount.amount") { value(4.00) }
            }

            assertEquals(0, countRows("SELECT COUNT(*) FROM invoices WHERE plan_id = ?", planId))
            assertEquals(
                0,
                countRows(
                    "SELECT COUNT(*) FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)",
                    planId,
                ),
            )
            assertEquals(0, countRows("SELECT COUNT(*) FROM promotion_redemptions WHERE user_id = ?", testUserId))
        }

        @Test
        fun `unknown promotion returns stable promotion reason`() {
            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "UNKNOWN",
                    ),
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("PROMOTION_INVALID") }
                jsonPath("$.reasonCode") { value("promotion_unknown") }
            }
        }

        @Test
        fun `expired promotion returns stable promotion reason`() {
            seedPromotion(
                code = "OLD20",
                type = "PERCENTAGE_DISCOUNT",
                value = BigDecimal("20.00"),
                startsAtSql = "now() - interval '30 days'",
                endsAtSql = "now() - interval '1 day'",
            )

            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "OLD20",
                    ),
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.reasonCode") { value("promotion_expired") }
            }
        }

        @Test
        fun `promotion for another plan returns stable promotion reason`() {
            val otherPlanId = seedPlan("OTHER_${System.nanoTime()}", "Other Advanced", false)
            extraPlanIds.add(otherPlanId)
            seedPromotion(
                code = "OTHER20",
                type = "PERCENTAGE_DISCOUNT",
                value = BigDecimal("20.00"),
                applicablePlanId = otherPlanId,
            )

            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "OTHER20",
                    ),
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.reasonCode") { value("promotion_plan_mismatch") }
            }
        }

        @Test
        fun `max redemption limit returns stable promotion reason`() {
            val promotionId = seedPromotion(
                code = "FULL20",
                type = "PERCENTAGE_DISCOUNT",
                value = BigDecimal("20.00"),
                maxRedemptions = 1,
            )
            val otherUserId = createUser()
            extraUserIds.add(otherUserId)
            seedActivePromotionRedemption(promotionId, otherUserId)

            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "FULL20",
                    ),
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.reasonCode") { value("promotion_limit_reached") }
            }
        }

        @Test
        fun `per user redemption limit returns stable promotion reason`() {
            val promotionId = seedPromotion("USED20", "PERCENTAGE_DISCOUNT", BigDecimal("20.00"))
            seedActivePromotionRedemption(promotionId, testUserId)

            mockMvc.post("/api/v1/billing/promotions/validate") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "priceId" to priceId,
                        "promotionCode" to "USED20",
                    ),
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.reasonCode") { value("promotion_user_limit_reached") }
            }
        }
    }

    @Nested
    inner class `Invalid price` {
        @Test
        fun `unknown price ID fails safely`() {
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to 999999))
            }.andExpect {
                status { isNotFound() }
            }
        }

        @Test
        fun `inactive price fails safely`() {
            val inactivePriceId = seedInactivePrice(planId)

            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to inactivePriceId))
            }.andExpect {
                status { isNotFound() }
            }
        }
    }

    @Nested
    inner class `Idempotency` {
        @Test
        fun `same idempotency key returns cached response without duplicate invoice`() {
            val idempotencyKey = "test-key-${System.nanoTime()}"

            // First request
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }

            // Second request with same key — should return cached response
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }

            // Verify only one invoice was created
            val invoiceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE plan_id = ?",
                Int::class.java,
                planId,
            )
            assertEquals(1, invoiceCount)
        }

        @Test
        fun `different idempotency keys create independent checkouts`() {
            val key1 = "key-alpha-${System.nanoTime()}"
            val key2 = "key-bravo-${System.nanoTime()}"

            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", key1)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }

            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", key2)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }

            // Verify two invoices were created
            val invoiceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE plan_id = ?",
                Int::class.java,
                planId,
            )
            assertEquals(2, invoiceCount)
        }

        @Test
        fun `same idempotency key with different request returns conflict`() {
            val idempotencyKey = "conflict-key-${System.nanoTime()}"
            val otherPriceId = seedPrice(planId, 90, BigDecimal("49.99"), "IRR")

            // First request with priceId
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
            }

            // Second request with same key but different priceId — should return conflict
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to otherPriceId))
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("IDEMPOTENCY_KEY_CONFLICT") }
            }
        }

        @Test
        fun `checkout without idempotency key creates new invoice each time`() {
            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
            }

            mockMvc.post("/api/v1/billing/checkout") {
                contentType = MediaType.APPLICATION_JSON
                with(authentication(testAuthentication(testUserId)))
                content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
            }.andExpect {
                status { isOk() }
            }

            val invoiceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE plan_id = ?",
                Int::class.java,
                planId,
            )
            assertEquals(2, invoiceCount)
        }
    }

    // ── Helpers ──

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

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

    private fun seedPrice(planId: Long, billingPeriodDays: Int, amount: BigDecimal, currency: String): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            values (?, ?, ?, ?, true, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            planId,
            billingPeriodDays,
            amount,
            currency,
        ) ?: error("Expected price id")
    }

    private fun seedInactivePrice(planId: Long): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            values (?, 30, 9.99, 'IRR', false, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            planId,
        ) ?: error("Expected price id")
    }

    private fun seedPromotion(
        code: String,
        type: String,
        value: BigDecimal,
        applicablePlanId: Long = planId,
        startsAtSql: String = "now() - interval '1 day'",
        endsAtSql: String = "now() + interval '30 days'",
        maxRedemptions: Int? = null,
    ): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into promotions (
                code, type, value, applicable_plan_id, starts_at, ends_at,
                max_redemptions, per_user_redemption_limit, active, internal_notes
            )
            values (?, ?, ?, ?, $startsAtSql, $endsAtSql, ?, 1, true, 'test')
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            type,
            value,
            applicablePlanId,
            maxRedemptions,
        ) ?: error("Expected promotion id")
    }

    private fun seedActivePromotionRedemption(promotionId: Long, userId: UUID) {
        val invoiceId = jdbcTemplate.queryForObject(
            """
            insert into invoices (
                user_id, plan_id, period_start, period_end,
                amount_due, currency, amount_after_discount, discount_currency,
                status, manual
            )
            values (?, ?, now() - interval '1 day', now() + interval '29 days', 19.99, 'IRR', 15.99, 'IRR', 'PAID', false)
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
            planId,
        ) ?: error("Expected invoice id")

        jdbcTemplate.update(
            """
            insert into promotion_redemptions (
                user_id, promotion_id, invoice_id, provider,
                redeemed_at, reserved_at, reservation_expires_at,
                status, safe_audit_reference
            )
            values (?, ?, ?, 'PAYPING', now(), now() - interval '1 day', now() + interval '1 day', 'REDEEMED', ?)
            """.trimIndent(),
            userId,
            promotionId,
            invoiceId,
            "test:$promotionId:$invoiceId",
        )
    }

    private fun countRows(sql: String, vararg args: Any): Int =
        jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0
}
