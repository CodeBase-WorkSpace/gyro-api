package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.SavePlanScheduleCommand
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.subscription.application.CachedEntitlementService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
        "app.goal-schedules.premium-goal-schedules-enabled=true",
    ],
)
class GoalScheduleDegradeIntegrationTest(
    @Autowired private val planScheduleService: PlanScheduleService,
    @Autowired private val nutritionPlanRepository: NutritionPlanRepository,
    @Autowired private val cachedEntitlementService: CachedEntitlementService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private lateinit var userId: UUID
    private lateinit var planId: UUID
    private var advancedPlanId: Long = 0

    private val scheduleStart: LocalDate = LocalDate.of(2026, 6, 1)

    @BeforeEach
    fun setUp() {
        userId = UUID.randomUUID()
        seedUser(userId)
        planId = seedGoal(userId)
        advancedPlanId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("Expected seeded ADVANCED plan")

        seedSubscription(status = "ACTIVE", periodEndSql = "now() + interval '30 days'")
        cachedEntitlementService.invalidate(userId)
        planScheduleService.savePlanSchedule(
            userId = userId,
            command = SavePlanScheduleCommand(
                nutritionPlanId = planId,
                scheduleType = GoalScheduleType.ZIGZAG,
                activeFrom = scheduleStart,
                weeklyCalorieBudget = BigDecimal("14000.00"),
                weekdayTargets = mapOf("SATURDAY" to mapOf("calories" to 2600)),
                macroAdjustmentMode = MacroTargetAdjustmentMode.SCALE_WITH_CALORIES,
            ),
        )
    }

    @Test
    fun `entitled users resolve the real schedule`() {
        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        val saturday = LocalDate.of(2026, 7, 18) // a Saturday

        val target = planScheduleService.resolveDailyTarget(userId, plan, saturday)

        assertEquals(DailyTargetSource.WEEKDAY_RULE, target.planScheduleSummary.source)
        assertEquals(0, BigDecimal("2600").compareTo(target.targets.calories))
    }

    @Test
    fun `grace users keep schedule resolution and edit access`() {
        jdbcTemplate.update(
            "update user_subscriptions set status = 'GRACE_PERIOD', period_end = now() - interval '1 day', grace_period_end = now() + interval '3 days', grace_reason = 'RENEWAL_OVERDUE' where user_id = ?",
            userId,
        )
        cachedEntitlementService.invalidate(userId)
        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        val saturday = LocalDate.of(2026, 7, 18)

        assertEquals(
            DailyTargetSource.WEEKDAY_RULE,
            planScheduleService.resolveDailyTarget(userId, plan, saturday).planScheduleSummary.source,
        )
        val saved = planScheduleService.savePlanSchedule(
            userId,
            SavePlanScheduleCommand(
                nutritionPlanId = planId,
                scheduleType = GoalScheduleType.ZIGZAG,
                activeFrom = scheduleStart,
                weeklyCalorieBudget = BigDecimal("14700.00"),
                weekdayTargets = mapOf("SATURDAY" to mapOf("calories" to 2700)),
                macroAdjustmentMode = MacroTargetAdjustmentMode.SCALE_WITH_CALORIES,
            ),
        )
        assertEquals("14700.00", saved.weeklyCalorieBudget?.toPlainString())
    }

    @Test
    fun `lapsed users collapse to the weekly average from the lapse date only`() {
        expireSubscription(periodEndSql = "now() - interval '10 days'")
        val plan = nutritionPlanRepository.findById(planId).orElseThrow()

        val afterLapse = planScheduleService.resolveDailyTarget(
            userId, plan, LocalDate.now().plusDays(1),
        )
        assertEquals(DailyTargetSource.DEGRADED_AVERAGE, afterLapse.planScheduleSummary.source)
        // 14000 / 7 = 2000; macros scale by 2000/2200.
        assertEquals(0, BigDecimal("2000.00").compareTo(afterLapse.targets.calories))
        assertEquals(0, BigDecimal("127.273").compareTo(afterLapse.targets.protein))

        val beforeLapse = planScheduleService.resolveDailyTarget(
            userId, plan, LocalDate.of(2026, 6, 20), // a Saturday before the lapse
        )
        assertEquals(DailyTargetSource.WEEKDAY_RULE, beforeLapse.planScheduleSummary.source)
        assertEquals(0, BigDecimal("2600").compareTo(beforeLapse.targets.calories))
    }

    @Test
    fun `saving base edits while lapsed preserves the stored premium schedule`() {
        expireSubscription(periodEndSql = "now() - interval '10 days'")

        val kept = planScheduleService.savePlanSchedule(
            userId = userId,
            command = SavePlanScheduleCommand(
                nutritionPlanId = planId,
                scheduleType = GoalScheduleType.FLAT,
                activeFrom = scheduleStart,
                macroAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
            ),
        )

        assertEquals(GoalScheduleType.ZIGZAG, kept.scheduleType)
        assertEquals("14000.00", kept.weeklyCalorieBudget?.toPlainString())
    }

    @Test
    fun `resubscribing reactivates the exact schedule`() {
        expireSubscription(periodEndSql = "now() - interval '10 days'")
        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        val tomorrow = LocalDate.now().plusDays(1)
        assertEquals(
            DailyTargetSource.DEGRADED_AVERAGE,
            planScheduleService.resolveDailyTarget(userId, plan, tomorrow).planScheduleSummary.source,
        )

        seedSubscription(status = "ACTIVE", periodEndSql = "now() + interval '30 days'")
        cachedEntitlementService.invalidate(userId)

        val restored = planScheduleService.resolveDailyTarget(userId, plan, tomorrow)
        assertEquals(GoalScheduleType.ZIGZAG, restored.planScheduleSummary.type)
        assert(restored.planScheduleSummary.source != DailyTargetSource.DEGRADED_AVERAGE)
    }

    @Test
    fun `missing entitlement dates use persisted schedule boundary without rewriting all history`() {
        jdbcTemplate.update(
            "update user_subscriptions set status = 'EXPIRED', period_end = null, grace_period_end = null where user_id = ?",
            userId,
        )
        jdbcTemplate.update(
            "update plan_schedules set updated_at = now() where user_id = ?",
            userId,
        )
        cachedEntitlementService.invalidate(userId)
        val plan = nutritionPlanRepository.findById(planId).orElseThrow()

        assert(
            planScheduleService.resolveDailyTarget(userId, plan, LocalDate.now().minusDays(1))
                .planScheduleSummary.source != DailyTargetSource.DEGRADED_AVERAGE,
        )
        assertEquals(
            DailyTargetSource.DEGRADED_AVERAGE,
            planScheduleService.resolveDailyTarget(userId, plan, LocalDate.now())
                .planScheduleSummary.source,
        )
    }

    private fun seedUser(id: UUID) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "degrade-${System.nanoTime()}@example.com",
        )
    }

    private fun seedGoal(userId: UUID): UUID {
        return jdbcTemplate.queryForObject(
            """
            insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat)
            values (?, ?, 'Asia/Tehran', 2200, 140, 220, 70)
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
            scheduleStart,
        ) ?: error("Expected inserted nutrition plan id")
    }

    private fun seedSubscription(status: String, periodEndSql: String) {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end, cancel_at_period_end
            )
            values (?, ?, ?, now() - interval '40 days', $periodEndSql, false)
            on conflict (user_id) do update set
                status = excluded.status,
                period_end = excluded.period_end
            """.trimIndent(),
            userId,
            advancedPlanId,
            status,
        )
        cachedEntitlementService.invalidate(userId)
    }

    private fun expireSubscription(periodEndSql: String) {
        jdbcTemplate.update(
            "update user_subscriptions set status = 'EXPIRED', period_end = $periodEndSql where user_id = ?",
            userId,
        )
        cachedEntitlementService.invalidate(userId)
    }
}
