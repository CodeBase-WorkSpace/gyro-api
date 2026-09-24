package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.CachedEntitlementService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@Import(
    TestcontainersConfiguration::class,
    GoalControllerPremiumScheduleIntegrationTest.FixedClockConfiguration::class,
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-schedules.premium-goal-schedules-enabled=true",
    ]
)
class GoalControllerPremiumScheduleIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val cachedEntitlementService: CachedEntitlementService,
) {

    @Test
    fun `put goals requires a calculator base before an advanced schedule`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-base-required-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "ACTIVE")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].code").value("CALCULATOR_BASE_REQUIRED"))
    }

    @Test
    fun `put goals requires an active subscription for premium schedules`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-required-${System.nanoTime()}@example.com")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"))
    }

    @Test
    fun `put goals persists premium schedules for active subscriptions`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-active-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "ACTIVE")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.schedule.type").value("WEEKDAY_WEEKEND"))
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
    }

    @Test
    fun `put goals allows grace period subscriptions to keep premium schedules`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-grace-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "GRACE_PERIOD")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.schedule.type").value("WEEKDAY_WEEKEND"))
    }

    @Test
    fun `put goals rejects expired subscriptions for premium schedules`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-expired-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "EXPIRED")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"))
    }

    @Test
    fun `put goals rejects canceled subscriptions for premium schedules`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-canceled-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "CANCELED")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"))
    }

    @Test
    fun `put goals rejects billing blocked subscriptions for premium schedules`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-blocked-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "BILLED_BLOCKED")
        saveCalculatorBase(userId)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("BILLED_BLOCKED"))
    }

    @Test
    fun `lapsed users cannot clear calculator provenance while an advanced schedule is preserved`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-hidden-clear-${System.nanoTime()}@example.com")
        seedPremiumSubscription(userId, status = "ACTIVE")
        saveCalculatorBase(userId)
        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savePremiumGoalJson())
        ).andExpect(status().isOk)

        setSubscriptionStatus(userId, "EXPIRED", FIXED_INSTANT.minusSeconds(3_600))

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(clearCalculatorWithFlatScheduleJson())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].code").value("CALCULATOR_BASE_REQUIRED"))

        assertEquals(
            "WEEKDAY_WEEKEND|-400.00|FORMULA_WIZARD",
            jdbcTemplate.queryForObject(
                """
                select ps.schedule_type || '|' || np.daily_energy_delta || '|' || np.daily_energy_delta_source
                from nutrition_plans np
                join plan_schedules ps on ps.nutrition_plan_id = np.id
                where np.user_id = ?
                """.trimIndent(),
                String::class.java,
                userId,
            ),
        )

        setSubscriptionStatus(userId, "ACTIVE", FIXED_INSTANT.plusSeconds(30L * 86_400))
        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.schedule.type").value("WEEKDAY_WEEKEND"))
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
    }

    private fun seedUser(
        id: UUID,
        email: String,
    ) {
        jdbcTemplate.update(
            """
            insert into users (
                id,
                email,
                password_hash,
                role,
                email_verification_status,
                phone_verification_status,
                status,
                created_at,
                updated_at
            )
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
        )
    }

    private fun seedPremiumSubscription(userId: UUID, status: String) {
        val planId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("Expected ADVANCED subscription plan id")

        // Ensure subscription_features and plan_features exist for premium_schedules
        jdbcTemplate.update(
            """
            insert into subscription_features (key, description, active)
            values ('premium_schedules', 'Advanced goal scheduling', true)
            on conflict (key) do nothing
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            insert into plan_features (plan_id, feature_key, enabled)
            values (?, 'premium_schedules', true)
            on conflict (plan_id, feature_key) do update set enabled = true
            """.trimIndent(),
            planId,
        )

        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end
            )
            values (
                ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?::timestamptz
            )
            """.trimIndent(),
            userId,
            planId,
            status,
            FIXED_INSTANT.minusSeconds(86_400).toString(),
            if (status == "EXPIRED" || status == "CANCELED") {
                FIXED_INSTANT.minusSeconds(3_600).toString()
            } else {
                FIXED_INSTANT.plusSeconds(30L * 86_400).toString()
            },
            status == "CANCELED",
            if (status == "GRACE_PERIOD") {
                FIXED_INSTANT.plusSeconds(7L * 86_400).toString()
            } else {
                null
            },
        )
    }

    private fun setSubscriptionStatus(
        userId: UUID,
        status: String,
        periodEnd: Instant,
    ) {
        jdbcTemplate.update(
            "update user_subscriptions set status = ?, period_end = ?::timestamptz where user_id = ?",
            status,
            periodEnd.toString(),
            userId,
        )
        cachedEntitlementService.invalidate(userId)
    }

    private fun saveCalculatorBase(userId: UUID) {
        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveCalculatorBaseGoalJson())
        ).andExpect(status().isOk)
    }

    private fun saveCalculatorBaseGoalJson(): String {
        val startDate = LocalDate.of(2026, 6, 27)
        val targetDate = startDate.plusDays(90)
        return """
            {
              "goal": {
                "type": "LOSE_WEIGHT",
                "targetWeight": { "value": 76.500, "unit": "KG" },
                "targetDate": "$targetDate"
              },
              "activePlan": {
                "startDate": "$startDate",
                "baseTargets": {
                  "calories": 2200.00,
                  "protein": 140.000,
                  "carbs": 220.000,
                  "fat": 70.000,
                  "fiber": 28.000
                },
                "schedule": { "type": "FLAT" },
                "calculator": {
                  "formula": { "name": "mifflin-st-jeor", "version": "1" },
                  "maintenanceCalories": 2600.00,
                  "targetCalories": 2200.00,
                  "activityFactor": 1.550,
                  "dailyEnergyDelta": -400.00,
                  "weeklyWeightChangeKg": -0.363,
                  "timeline": {
                    "estimatedWeeksMin": 10,
                    "estimatedWeeksMax": 16,
                    "estimatedTargetDate": "$targetDate"
                  },
                  "warningCodes": []
                }
              },
              "acceptedWarningCodes": [],
              "blockingWarningCodes": []
            }
        """.trimIndent()
    }

    private fun savePremiumGoalJson(): String {
        val startDate = LocalDate.of(2026, 6, 27)
        val targetDate = startDate.plusDays(90)
        return """
            {
              "goal": {
                "type": "LOSE_WEIGHT",
                "targetWeight": { "value": 76.500, "unit": "KG" },
                "targetDate": "$targetDate"
              },
              "activePlan": {
                "startDate": "$startDate",
                "baseTargets": {
                  "calories": 2200.00,
                  "protein": 140.000,
                  "carbs": 220.000,
                  "fat": 70.000,
                  "fiber": 28.000
                },
                "schedule": {
                  "type": "WEEKDAY_WEEKEND",
                  "weekdayTargets": {
                    "SATURDAY": {
                      "calories": 2300.00,
                      "protein": 145.000,
                      "carbs": 230.000,
                      "fat": 74.000,
                      "fiber": 30.000
                    }
                  }
                },
                "calculatorUpdateMode": "PRESERVE"
              },
              "acceptedWarningCodes": [],
              "blockingWarningCodes": []
            }
        """.trimIndent()
    }

    private fun clearCalculatorWithFlatScheduleJson(): String {
        val startDate = LocalDate.of(2026, 6, 27)
        val targetDate = startDate.plusDays(90)
        return """
            {
              "goal": {
                "type": "LOSE_WEIGHT",
                "targetWeight": { "value": 76.500, "unit": "KG" },
                "targetDate": "$targetDate"
              },
              "activePlan": {
                "startDate": "$startDate",
                "baseTargets": {
                  "calories": 2200.00,
                  "protein": 140.000,
                  "carbs": 220.000,
                  "fat": 70.000,
                  "fiber": 28.000
                },
                "schedule": { "type": "FLAT" },
                "calculatorUpdateMode": "CLEAR"
              },
              "acceptedWarningCodes": [],
              "blockingWarningCodes": []
            }
        """.trimIndent()
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        @Primary
        fun fixedClock(): Clock {
            return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
        }
    }

    companion object {
        private val FIXED_INSTANT: Instant = Instant.parse("2026-06-27T01:30:00Z")
    }
}
