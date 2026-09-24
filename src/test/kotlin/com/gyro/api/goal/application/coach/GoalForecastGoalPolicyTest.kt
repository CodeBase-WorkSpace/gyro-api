package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.GoalForecastCandidateOutcome
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.weight.domain.WeightUnit
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalForecastGoalPolicyTest {
    private val today: LocalDate = LocalDate.parse("2026-10-06")
    private val planStart: LocalDate = LocalDate.parse("2026-09-01")
    private val targetDate: LocalDate = LocalDate.parse("2027-01-01")

    @Test
    fun `a calculator-backed loss goal resolves to canonical kilograms`() {
        val goal = compatible(plan())

        assertEquals(GoalForecastDirection.LOSS, goal.direction)
        assertEquals(BigDecimal("90.000"), goal.startWeightKg)
        assertEquals(BigDecimal("80.000"), goal.targetWeightKg)
        assertEquals(planStart, goal.planStart)
        assertEquals(targetDate, goal.originalTargetDate)
    }

    @Test
    fun `a calculator-backed gain goal resolves upward`() {
        val goal = compatible(
            plan(
                calculatorGoalType = GoalType.GAIN_WEIGHT,
                calculatorCurrentWeightKg = "60.000",
                calculatorTargetWeightKg = "68.000",
            ),
        )

        assertEquals(GoalForecastDirection.GAIN, goal.direction)
        assertEquals(BigDecimal("68.000"), goal.targetWeightKg)
    }

    @Test
    fun `the calculator baseline is preferred over any weigh-in`() {
        var readStartPeriod = false
        val resolution = GoalForecastGoalPolicy.resolve(plan(), today) {
            readStartPeriod = true
            BigDecimal("95.000")
        }

        assertEquals(BigDecimal("90.000"), compatibleGoal(resolution).startWeightKg)
        assertFalse(readStartPeriod, "a calculator plan must not pay for the fallback read")
    }

    @Test
    fun `a manual plan falls back to the start-period weigh-in`() {
        val goal = compatible(
            plan(
                calculatorGoalType = null,
                calculatorCurrentWeightKg = null,
                calculatorTargetWeightKg = null,
                targetWeight = "80.000",
                targetWeightUnit = WeightUnit.KG,
            ),
            startPeriodWeightKg = BigDecimal("92.400"),
        )

        assertEquals(BigDecimal("92.400"), goal.startWeightKg)
        assertEquals(BigDecimal("80.000"), goal.targetWeightKg)
        assertEquals(GoalForecastDirection.LOSS, goal.direction)
    }

    @Test
    fun `the baseline window is inclusive on both ends and spans eight dates`() {
        val window = GoalForecastGoalPolicy.startWeightWindow(planStart)

        assertEquals(planStart, window.start)
        assertEquals(planStart.plusDays(7), window.endInclusive)
        // The plan start date itself, the sixth day, and the seventh day all qualify.
        assertTrue(planStart in window)
        assertTrue(planStart.plusDays(6) in window)
        assertTrue(planStart.plusDays(7) in window)
        // The eighth day after the start does not.
        assertFalse(planStart.plusDays(8) in window)
        assertFalse(planStart.minusDays(1) in window)
    }

    @Test
    fun `a manual plan without a start-period weigh-in is not forecastable`() {
        assertEquals(
            GoalForecastCandidateOutcome.NO_START_WEIGHT,
            incompatible(
                plan(
                    calculatorGoalType = null,
                    calculatorCurrentWeightKg = null,
                    calculatorTargetWeightKg = null,
                    targetWeight = "80.000",
                ),
                startPeriodWeightKg = null,
            ),
        )
    }

    @Test
    fun `a pounds goal is read in kilograms without normalizing the saved plan`() {
        val plan = plan(
            calculatorGoalType = null,
            calculatorCurrentWeightKg = null,
            calculatorTargetWeightKg = null,
            targetWeight = "176.000",
            targetWeightUnit = WeightUnit.LB,
        )

        val goal = compatible(plan, startPeriodWeightKg = BigDecimal("90.718"))

        assertEquals(BigDecimal("79.832"), goal.targetWeightKg)
        // The saved goal keeps its own unit and value.
        assertEquals(BigDecimal("176.000"), plan.targetWeight)
        assertEquals(WeightUnit.LB, plan.targetWeightUnit)
    }

    @Test
    fun `a half-written calculator snapshot falls through to the weigh-in baseline`() {
        val goal = compatible(
            plan(calculatorTargetWeightKg = null, targetWeight = "80.000"),
            startPeriodWeightKg = BigDecimal("91.000"),
        )

        assertEquals(BigDecimal("91.000"), goal.startWeightKg)
    }

    @Test
    fun `a maintenance goal is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.MAINTENANCE_GOAL,
            incompatible(plan(calculatorGoalType = GoalType.MAINTAIN_WEIGHT)),
        )
    }

    @Test
    fun `a missing target weight or date is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.MISSING_TARGET,
            incompatible(plan(targetDate = null)),
        )
        assertEquals(
            GoalForecastCandidateOutcome.MISSING_TARGET,
            incompatible(
                plan(
                    calculatorTargetWeightKg = null,
                    targetWeight = null,
                    calculatorCurrentWeightKg = null,
                ),
                startPeriodWeightKg = BigDecimal("90.000"),
            ),
        )
    }

    @Test
    fun `a target date on or before the plan start is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.MISSING_TARGET,
            incompatible(plan(targetDate = planStart)),
        )
        assertEquals(
            GoalForecastCandidateOutcome.MISSING_TARGET,
            incompatible(plan(targetDate = planStart.minusDays(1))),
        )
    }

    @Test
    fun `a plan that has not started yet is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.NO_ACTIVE_GOAL,
            incompatible(plan(startDate = today.plusDays(1))),
        )
    }

    @Test
    fun `a goal type that contradicts its own weights is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.DIRECTION_CONFLICT,
            incompatible(
                plan(
                    calculatorGoalType = GoalType.GAIN_WEIGHT,
                    calculatorCurrentWeightKg = "90.000",
                    calculatorTargetWeightKg = "80.000",
                ),
            ),
        )
    }

    @Test
    fun `less than one kilogram of planned change is excluded`() {
        assertEquals(
            GoalForecastCandidateOutcome.PLANNED_CHANGE_TOO_SMALL,
            incompatible(plan(calculatorTargetWeightKg = "89.100")),
        )
        assertTrue(
            GoalForecastGoalPolicy.resolve(plan(calculatorTargetWeightKg = "89.000"), today) { null }
                is GoalForecastGoalResolution.Compatible,
        )
    }

    @Test
    fun `editing the saved goal changes the resolved milestones basis`() {
        val original = compatible(plan())
        val edited = compatible(plan(calculatorTargetWeightKg = "82.000", targetDate = LocalDate.parse("2027-03-01")))

        assertEquals(BigDecimal("80.000"), original.targetWeightKg)
        assertEquals(BigDecimal("82.000"), edited.targetWeightKg)
        assertEquals(LocalDate.parse("2027-03-01"), edited.originalTargetDate)
    }

    private fun compatible(
        plan: NutritionPlanEntity,
        startPeriodWeightKg: BigDecimal? = null,
    ): GoalForecastGoal =
        compatibleGoal(GoalForecastGoalPolicy.resolve(plan, today) { startPeriodWeightKg })

    private fun compatibleGoal(resolution: GoalForecastGoalResolution): GoalForecastGoal =
        (resolution as? GoalForecastGoalResolution.Compatible)?.goal
            ?: error("expected a compatible goal, was $resolution")

    private fun incompatible(
        plan: NutritionPlanEntity,
        startPeriodWeightKg: BigDecimal? = null,
    ): GoalForecastCandidateOutcome =
        (GoalForecastGoalPolicy.resolve(plan, today) { startPeriodWeightKg }
            as? GoalForecastGoalResolution.Incompatible)
            ?.outcome
            ?: error("expected an incompatible goal")

    private fun plan(
        startDate: LocalDate = planStart,
        targetDate: LocalDate? = this.targetDate,
        calculatorGoalType: GoalType? = GoalType.LOSE_WEIGHT,
        calculatorCurrentWeightKg: String? = "90.000",
        calculatorTargetWeightKg: String? = "80.000",
        targetWeight: String? = null,
        targetWeightUnit: WeightUnit? = null,
    ) = NutritionPlanEntity(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        startDate = startDate,
        timezone = "Asia/Tehran",
        calories = BigDecimal("2000"),
        protein = BigDecimal("150"),
        carbs = BigDecimal("200"),
        fat = BigDecimal("70"),
        targetWeight = targetWeight?.let(::BigDecimal),
        targetWeightUnit = targetWeightUnit,
        targetDate = targetDate,
        calculatorGoalType = calculatorGoalType,
        calculatorCurrentWeightKg = calculatorCurrentWeightKg?.let(::BigDecimal),
        calculatorTargetWeightKg = calculatorTargetWeightKg?.let(::BigDecimal),
    )
}
