package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.ApiErrorCode
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.domain.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class EntitlementControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val entitlementGateService: EntitlementGateService,
) {
    private lateinit var userId: UUID
    private lateinit var adminId: UUID
    private var advancedPlanId: Long = 0

    @BeforeEach
    fun setUp() {
        userId = createUser(role = "USER")
        adminId = createUser(role = "ADMIN")
        advancedPlanId = planId("ADVANCED")
    }

    @Test
    fun `current entitlement requires authentication`() {
        mockMvc.get("/api/v1/billing/me/entitlement").andExpect {
            status { isUnauthorized() }
            jsonPath("$.requestId") { exists() }
        }
    }

    @Test
    fun `current entitlement is scoped to authenticated user`() {
        seedSubscription(userId, SubscriptionStatus.ACTIVE)
        val otherUserId = createUser(role = "USER")

        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(userId, "ROLE_USER")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("ADVANCED") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.source") { value("SUBSCRIPTION") }
            jsonPath("$.features[?(@ == 'premium_schedules')]") { exists() }
        }

        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(otherUserId, "ROLE_USER")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("FREE") }
            jsonPath("$.status") { value("FREE") }
            jsonPath("$.features.length()") { value(0) }
        }
    }

    @Test
    fun `admin entitlement endpoint requires admin role`() {
        mockMvc.get("/api/v1/admin/users/$userId/entitlement") {
            with(authentication(testAuthentication(userId, "ROLE_USER")))
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.get("/api/v1/admin/users/$userId/entitlement") {
            with(authentication(testAuthentication(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("FREE") }
        }
    }

    @Test
    fun `premium gate fails closed for unknown feature keys`() {
        val exception = assertThrows(com.gyro.api.common.error.FeatureDisabledException::class.java) {
            entitlementGateService.requireFeature(userId, "unknown_feature", "/api/v1/test")
        }

        assertEquals(ApiErrorCode.FEATURE_DISABLED, exception.code)
    }

    @Test
    fun `premium gate denies blocked billing with stable code`() {
        seedSubscription(userId, SubscriptionStatus.BILLED_BLOCKED)

        val exception = assertThrows(com.gyro.api.common.error.BillingBlockedException::class.java) {
            entitlementGateService.requireFeature(userId, "premium_schedules", "/api/v1/test")
        }

        assertEquals(ApiErrorCode.BILLED_BLOCKED, exception.code)
    }

    @Test
    fun `premium gate allows active subscription for enabled feature`() {
        seedSubscription(userId, SubscriptionStatus.ACTIVE)

        val entitlement = entitlementGateService.requireFeature(userId, "premium_schedules", "/api/v1/test")

        assertEquals("ADVANCED", entitlement.planKey)
    }

    private fun createUser(role: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, 'hash', ?, 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "entitlement-${System.nanoTime()}-${id.toString().take(8)}@example.com",
            role,
        )
        return id
    }

    private fun planId(code: String): Long {
        return jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = ?",
            Long::class.java,
            code,
        ) ?: error("Expected plan $code")
    }

    private fun seedSubscription(
        userId: UUID,
        status: SubscriptionStatus,
    ) {
        val now = Instant.now()
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end
            )
            values (?, ?, ?, ?::timestamptz, ?::timestamptz, false, ?::timestamptz)
            on conflict (user_id) do update set
                plan_id = excluded.plan_id,
                status = excluded.status,
                period_start = excluded.period_start,
                period_end = excluded.period_end,
                grace_period_end = excluded.grace_period_end
            """.trimIndent(),
            userId,
            advancedPlanId,
            status.name,
            now.minus(1, ChronoUnit.DAYS).toString(),
            now.plus(30, ChronoUnit.DAYS).toString(),
            now.plus(7, ChronoUnit.DAYS).toString(),
        )
    }

    private fun testAuthentication(userId: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority(role)),
        )
    }
}
