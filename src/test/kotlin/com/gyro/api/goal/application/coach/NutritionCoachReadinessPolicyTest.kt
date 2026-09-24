package com.gyro.api.goal.application.coach

import com.gyro.api.goal.application.recalibration.RecalibrationData
import com.gyro.api.goal.application.recalibration.RecalibrationWeightPoint
import com.gyro.api.goal.domain.NutritionPlanEntity
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NutritionCoachReadinessPolicyTest {
    private val today = LocalDate.parse("2026-07-23")

    @Test
    fun `day seven enforces and displays four food days`() = assertFoodGate(7, 4)

    @Test
    fun `day eight enforces and displays four food days`() = assertFoodGate(8, 4)

    @Test
    fun `day nine enforces and displays five food days`() = assertFoodGate(9, 5)

    @Test
    fun `day twelve enforces and displays six food days`() = assertFoodGate(12, 6)

    @Test
    fun `day thirteen enforces and displays seven food days`() = assertFoodGate(13, 7)

    @Test
    fun `today weigh in already present waits for another distinct day`() {
        val collecting = requireNotNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = 4, weightDaysAgo = listOf(7, 0)),
                completedPlanDays = 7,
            ),
        )

        assertEquals(true, collecting.weighedInToday)
        assertEquals(
            NutritionCoachNextUsefulAction.WAIT_FOR_ANOTHER_WEIGHT_DAY,
            collecting.nextUsefulAction,
        )
    }

    @Test
    fun `missing distinct day uses today when today is not represented`() {
        val collecting = requireNotNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = 4, weightDaysAgo = listOf(7, 1)),
                completedPlanDays = 7,
            ),
        )

        assertEquals(false, collecting.weighedInToday)
        assertEquals(NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY, collecting.nextUsefulAction)
    }

    @Test
    fun `today weigh in already present asks to extend a short span on future days`() {
        val collecting = requireNotNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = 4, weightDaysAgo = listOf(3, 1, 0)),
                completedPlanDays = 7,
            ),
        )

        assertEquals(NutritionCoachReadinessReason.INSUFFICIENT_WEIGHT_SPAN, collecting.readinessReason)
        assertEquals(true, collecting.weighedInToday)
        assertEquals(NutritionCoachNextUsefulAction.EXTEND_WEIGHT_SPAN, collecting.nextUsefulAction)
    }

    @Test
    fun `short span uses today when today can extend it`() {
        val collecting = requireNotNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = 4, weightDaysAgo = listOf(6, 3, 1)),
                completedPlanDays = 7,
            ),
        )

        assertEquals(false, collecting.weighedInToday)
        assertEquals(NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY, collecting.nextUsefulAction)
    }

    private fun assertFoodGate(completedPlanDays: Int, required: Int) {
        val blocked = requireNotNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = required - 1, weightDaysAgo = listOf(7, 3, 0)),
                completedPlanDays = completedPlanDays,
            ),
        )
        assertEquals(NutritionCoachReadinessReason.INSUFFICIENT_FOOD_EVIDENCE, blocked.readinessReason)
        assertEquals(required, blocked.foodEvidenceDaysRequired)
        assertNull(
            NutritionCoachReadinessPolicy.evaluate(
                data(loggedDays = required, weightDaysAgo = listOf(7, 3, 0)),
                completedPlanDays = completedPlanDays,
            ),
        )
    }

    private fun data(loggedDays: Int, weightDaysAgo: List<Int>) = RecalibrationData(
        plan = NutritionPlanEntity(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            startDate = today.minusDays(14),
            timezone = "Asia/Tehran",
            calories = BigDecimal("2000"),
            protein = BigDecimal("100"),
            carbs = BigDecimal("200"),
            fat = BigDecimal("70"),
            dailyEnergyDelta = BigDecimal("-500"),
        ),
        today = today,
        windowStart = today.minusDays(14),
        intakeThrough = today.minusDays(1),
        weightThrough = today,
        weights = weightDaysAgo.map { daysAgo ->
            RecalibrationWeightPoint(today.minusDays(daysAgo.toLong()), BigDecimal("80"))
        },
        loggedDays = loggedDays,
        averageLoggedCalories = BigDecimal("1800"),
        averageHistoricalTargetCalories = BigDecimal("2000"),
    )
}
