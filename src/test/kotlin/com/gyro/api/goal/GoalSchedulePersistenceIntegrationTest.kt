package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.FeatureDisabledException
import com.gyro.api.common.error.SubscriptionExpiredException
import com.gyro.api.common.error.SubscriptionRequiredException
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.SavePlanScheduleCommand
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-schedules.premium-goal-schedules-enabled=false",
    ]
)
class GoalScheduleFeatureDisabledIntegrationTest(
    @Autowired private val planScheduleService: PlanScheduleService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `premium schedule persistence is blocked while feature flag is off`() {
        val userId = UUID.randomUUID()
        val goalId = seedUserAndGoal(userId)

        assertThrows(FeatureDisabledException::class.java) {
            planScheduleService.savePlanSchedule(
                userId = userId,
                command = SavePlanScheduleCommand(
                    nutritionPlanId = goalId,
                    scheduleType = GoalScheduleType.WEEKDAY_WEEKEND,
                    activeFrom = LocalDate.of(2026, 6, 25),
                    weeklyCalorieBudget = BigDecimal("15400.00"),
                    weekdayTargets = mapOf("SATURDAY" to mapOf("calories" to 2400)),
                    macroAdjustmentMode = MacroTargetAdjustmentMode.FIXED_PROTEIN_FLEXIBLE_CARBS_FAT,
                ),
            )
        }
    }

    private fun seedUserAndGoal(userId: UUID): UUID {
        seedUser(jdbcTemplate, userId, "schedule-disabled-${System.nanoTime()}@example.com")
        return seedGoal(jdbcTemplate, userId)
    }
}

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-schedules.premium-goal-schedules-enabled=true",
    ]
)
class GoalSchedulePersistenceIntegrationTest(
    @Autowired private val planScheduleService: PlanScheduleService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `flat schedule persists without subscription entitlement`() {
        val userId = UUID.randomUUID()
        seedUser(jdbcTemplate, userId, "schedule-flat-${System.nanoTime()}@example.com")
        val goalId = seedGoal(jdbcTemplate, userId)

        val schedule = planScheduleService.savePlanSchedule(
            userId = userId,
            command = SavePlanScheduleCommand(
                nutritionPlanId = goalId,
                scheduleType = GoalScheduleType.FLAT,
                activeFrom = LocalDate.of(2026, 6, 25),
                macroAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
                formulaName = "manual",
                formulaVersion = "1",
                scheduleSnapshot = mapOf("source" to "manual-flat"),
            ),
        )

        assertNotNull(schedule.id)
        assertEquals(GoalScheduleType.FLAT, schedule.scheduleType)
        assertNull(schedule.weeklyCalorieBudget)

        val activeSchedule = planScheduleService.findPlanSchedule(
            userId = userId,
            nutritionPlanId = goalId,
        )
        assertEquals(schedule.id, activeSchedule?.id)
    }

    @Test
    fun `premium schedule persistence requires subscription entitlement`() {
        val userId = UUID.randomUUID()
        seedUser(jdbcTemplate, userId, "schedule-required-${System.nanoTime()}@example.com")
        val goalId = seedGoal(jdbcTemplate, userId)

        assertThrows(SubscriptionRequiredException::class.java) {
            savePremiumSchedule(userId, goalId)
        }
    }

    @Test
    fun `premium schedule persistence rejects expired subscription entitlement`() {
        val userId = UUID.randomUUID()
        seedUser(jdbcTemplate, userId, "schedule-expired-${System.nanoTime()}@example.com")
        val goalId = seedGoal(jdbcTemplate, userId)
        seedPremiumSubscription(
            jdbcTemplate = jdbcTemplate,
            userId = userId,
            status = "EXPIRED",
            expiresAtSql = "now() - interval '1 day'",
        )

        assertThrows(SubscriptionExpiredException::class.java) {
            savePremiumSchedule(userId, goalId)
        }
    }

    @Test
    fun `premium schedule persists with active non-free subscription entitlement`() {
        val userId = UUID.randomUUID()
        seedUser(jdbcTemplate, userId, "schedule-premium-${System.nanoTime()}@example.com")
        val goalId = seedGoal(jdbcTemplate, userId)
        seedPremiumSubscription(
            jdbcTemplate = jdbcTemplate,
            userId = userId,
            status = "ACTIVE",
            expiresAtSql = "now() + interval '30 days'",
        )

        val schedule = savePremiumSchedule(userId, goalId)

        assertNotNull(schedule.id)
        assertEquals(GoalScheduleType.WEEKDAY_WEEKEND, schedule.scheduleType)
        assertEquals(MacroTargetAdjustmentMode.FIXED_PROTEIN_FLEXIBLE_CARBS_FAT, schedule.macroAdjustmentMode)
        assertEquals("15400.00", schedule.weeklyCalorieBudget?.toPlainString())

        val persisted = jdbcTemplate.queryForMap(
            """
            select schedule_type, macro_adjustment_mode, weekday_targets::text, schedule_snapshot::text
            from plan_schedules
            where id = ?
            """.trimIndent(),
            schedule.id,
        )
        assertEquals("WEEKDAY_WEEKEND", persisted["schedule_type"])
        assertEquals("FIXED_PROTEIN_FLEXIBLE_CARBS_FAT", persisted["macro_adjustment_mode"])
        assert((persisted["weekday_targets"] as String).contains("SATURDAY"))
        assert((persisted["schedule_snapshot"] as String).contains("formula"))
    }

    private fun savePremiumSchedule(
        userId: UUID,
        goalId: UUID,
    ) = planScheduleService.savePlanSchedule(
        userId = userId,
        command = SavePlanScheduleCommand(
            nutritionPlanId = goalId,
            scheduleType = GoalScheduleType.WEEKDAY_WEEKEND,
            activeFrom = LocalDate.of(2026, 6, 25),
            activeTo = LocalDate.of(2026, 9, 25),
            weeklyCalorieBudget = BigDecimal("15400.00"),
            weekdayTargets = mapOf(
                "SATURDAY" to mapOf("calories" to 2400),
                "SUNDAY" to mapOf("calories" to 2000),
            ),
            macroAdjustmentMode = MacroTargetAdjustmentMode.FIXED_PROTEIN_FLEXIBLE_CARBS_FAT,
            formulaName = "schedule-planner",
            formulaVersion = "1",
            scheduleSnapshot = mapOf("formula" to "schedule-planner", "version" to 1),
        ),
    )
}

private fun seedUser(
    jdbcTemplate: JdbcTemplate,
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

private fun seedGoal(
    jdbcTemplate: JdbcTemplate,
    userId: UUID,
): UUID {
    return jdbcTemplate.queryForObject(
        """
        insert into nutrition_plans (
            user_id,
            start_date,
            timezone,
            calories,
            protein,
            carbs,
            fat
        )
        values (?, '2026-06-25', 'Asia/Tehran', 2200, 140, 220, 70)
        returning id
        """.trimIndent(),
        UUID::class.java,
        userId,
    ) ?: error("Expected inserted nutrition plan id")
}

private fun seedPremiumSubscription(
    jdbcTemplate: JdbcTemplate,
    userId: UUID,
    status: String,
    expiresAtSql: String,
) {
    val planId = jdbcTemplate.queryForObject(
        """
        insert into subscription_plans (code, name, free)
        values (?, 'Premium', false)
        returning id
        """.trimIndent(),
        Long::class.java,
        "premium-${System.nanoTime()}",
    ) ?: error("Expected inserted subscription plan id")

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
        insert into user_subscriptions (user_id, plan_id, status, period_start, period_end)
        values (?, ?, ?, now() - interval '1 day', $expiresAtSql)
        """.trimIndent(),
        userId,
        planId,
        status,
    )
}
