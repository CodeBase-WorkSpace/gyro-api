package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.SavePlanScheduleCommand
import com.gyro.api.goal.application.recalibration.RecalibrationDataLoader
import com.gyro.api.goal.application.recalibration.RecalibrationEvidenceBoundarySource
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.infrastructure.PlanTargetRegimeBoundaryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-schedules.premium-goal-schedules-enabled=true",
    ],
)
class RecalibrationBoundaryIntegrationTest(
    @Autowired private val boundaryRepository: PlanTargetRegimeBoundaryRepository,
    @Autowired private val recalibrationDataLoader: RecalibrationDataLoader,
    @Autowired private val planScheduleService: PlanScheduleService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `same day boundaries are idempotent and the latest active boundary excludes earlier evidence`() {
        val userId = UUID.randomUUID()
        val planId = seedPlan(userId)
        val today = TODAY
        val firstBoundary = today.minusDays(10)
        val latestBoundary = today.minusDays(4)

        boundaryRepository.insertScheduleBoundaryIfAbsent(userId, planId, firstBoundary)
        boundaryRepository.insertScheduleBoundaryIfAbsent(userId, planId, firstBoundary)
        boundaryRepository.insertScheduleBoundaryIfAbsent(userId, planId, latestBoundary)

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "select count(*) from plan_target_regime_boundaries where nutrition_plan_id = ?",
                Int::class.java,
                planId,
            ),
        )

        val candidates = requireNotNull(recalibrationDataLoader.loadForProducer(userId))
        assertEquals(latestBoundary, candidates.boundary.date)
        assertEquals(RecalibrationEvidenceBoundarySource.SCHEDULE_CHANGE, candidates.boundary.source)
        assertEquals(latestBoundary, candidates.windows.first().windowStart)
    }

    @Test
    fun `future schedule boundary is ignored until its effective date`() {
        val userId = UUID.randomUUID()
        val planId = seedPlan(userId)
        val today = TODAY
        val futureBoundary = today.plusDays(3)

        boundaryRepository.insertScheduleBoundaryIfAbsent(userId, planId, futureBoundary)

        val candidates = requireNotNull(recalibrationDataLoader.loadForProducer(userId))
        assertEquals(today.minusDays(14), candidates.boundary.date)
        assertEquals(RecalibrationEvidenceBoundarySource.PLAN_START, candidates.boundary.source)
    }

    @Test
    fun `material active schedule edit creates todays boundary and excludes prior evidence`() {
        val userId = UUID.randomUUID()
        val planId = seedPlan(userId)
        seedPremiumScheduleEntitlement(userId)
        val activeFrom = TODAY.minusDays(7)
        saveSchedule(userId, planId, activeFrom, "12600.00")

        saveSchedule(userId, planId, activeFrom, "14000.00")
        seedWeight(userId, TODAY.minusDays(1), "91.000")
        seedWeight(userId, TODAY, "90.000")
        seedDiaryEntry(userId, TODAY.minusDays(1))

        assertEquals(TODAY, boundaryDate(planId))
        val candidate = requireNotNull(recalibrationDataLoader.loadForProducer(userId)).windows.first()
        assertEquals(TODAY, candidate.windowStart)
        assertEquals(listOf(TODAY), candidate.weights.map { it.date })
        assertEquals(0, candidate.loggedDays)
    }

    @Test
    fun `sub threshold active schedule edit creates no boundary`() {
        val userId = UUID.randomUUID()
        val planId = seedPlan(userId)
        seedPremiumScheduleEntitlement(userId)
        val activeFrom = TODAY.minusDays(7)
        saveSchedule(userId, planId, activeFrom, "12600.00")

        saveSchedule(userId, planId, activeFrom, "12690.00")

        assertEquals(0, boundaryCount(planId))
    }

    private fun saveSchedule(userId: UUID, planId: UUID, activeFrom: LocalDate, weeklyBudget: String) {
        planScheduleService.savePlanSchedule(
            userId,
            SavePlanScheduleCommand(
                nutritionPlanId = planId,
                scheduleType = GoalScheduleType.WEEKDAY_WEEKEND,
                activeFrom = activeFrom,
                weeklyCalorieBudget = java.math.BigDecimal(weeklyBudget),
                macroAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
            ),
        )
    }

    private fun boundaryCount(planId: UUID): Int = jdbcTemplate.queryForObject(
        "select count(*) from plan_target_regime_boundaries where nutrition_plan_id = ?",
        Int::class.java,
        planId,
    ) ?: 0

    private fun boundaryDate(planId: UUID): LocalDate = requireNotNull(jdbcTemplate.queryForObject(
        "select effective_from from plan_target_regime_boundaries where nutrition_plan_id = ?",
        LocalDate::class.java,
        planId,
    ))

    private fun seedWeight(userId: UUID, date: LocalDate, weight: String) {
        jdbcTemplate.update(
            "insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source) values (?, ?, ?, ?, 'KG', 'MANUAL')",
            userId, date, java.math.BigDecimal(weight), java.math.BigDecimal(weight),
        )
    }

    private fun seedDiaryEntry(userId: UUID, date: LocalDate) {
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at) values (?, ?, ?, 'Asia/Tehran', now(), now())",
            dayId, userId, date,
        )
        jdbcTemplate.update(
            """insert into diary_entries (id, diary_day_id, user_id, diary_date, meal_type, source_type, source_metadata, display_name_snapshot, serving_quantity_snapshot, serving_unit_code_snapshot, serving_unit_name_snapshot, calories_snapshot, protein_snapshot, carbs_snapshot, fat_snapshot, fiber_snapshot, sugar_snapshot, sodium_snapshot, sort_order, created_at, updated_at)
            values (?, ?, ?, ?, 'BREAKFAST', 'MANUAL', '{}', 'test', 1, 'SERVING', 'serving', 1800, 0, 0, 0, 0, 0, 0, 0, now(), now())""",
            UUID.randomUUID(), dayId, userId, date,
        )
    }

    private fun seedPlan(userId: UUID): UUID {
        jdbcTemplate.update(
            """
            insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at)
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "recalibration-boundary-${System.nanoTime()}@example.com",
        )
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source)
                values (?, ?, 'Asia/Tehran', 1800, 140, 200, 60, -500, 'FORMULA_WIZARD')
                returning id
                """.trimIndent(),
                UUID::class.java,
                userId,
                TODAY.minusDays(14),
            ),
        )
    }

    private fun seedPremiumScheduleEntitlement(userId: UUID) {
        val subscriptionPlanId = requireNotNull(jdbcTemplate.queryForObject(
            "insert into subscription_plans (code, name, free) values (?, 'Premium', false) returning id",
            Long::class.java,
            "recalibration-boundary-${System.nanoTime()}",
        ))
        jdbcTemplate.update("insert into subscription_features (key, description, active) values ('premium_schedules', 'Advanced schedules', true) on conflict (key) do nothing")
        jdbcTemplate.update("insert into plan_features (plan_id, feature_key, enabled) values (?, 'premium_schedules', true) on conflict (plan_id, feature_key) do update set enabled = true", subscriptionPlanId)
        jdbcTemplate.update("insert into user_subscriptions (user_id, plan_id, status, period_start, period_end) values (?, ?, 'ACTIVE', now() - interval '1 day', now() + interval '30 days')", userId, subscriptionPlanId)
    }

    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 1)
    }
}

@TestConfiguration(proxyBeanMethods = false)
private class FixedClockConfiguration {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC)
}
