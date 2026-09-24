package com.gyro.api.daily_score

import com.gyro.api.daily_score.application.DailyScoreEngine
import com.gyro.api.daily_score.application.DailyScoreInput
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.daily_score.application.DailyScoreNutritionTotals
import com.gyro.api.daily_score.application.DailyScoreTarget
import com.gyro.api.goal.domain.GoalType
import kotlin.test.Test
import kotlin.test.assertEquals
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class DailyScoreEngineTest {
    private val engine = DailyScoreEngine()

    @Test
    fun `goal adherence uses balanced nutrition weights`() {
        val result = engine.calculate(
            baseInput().copy(
                totals = DailyScoreNutritionTotals(
                    calories = BigDecimal("2000.00"),
                    protein = BigDecimal("80.000"),
                    carbs = BigDecimal("200.000"),
                    fat = BigDecimal("70.000"),
                ),
                target = DailyScoreTarget(
                    goalId = UUID.randomUUID(),
                    goalType = GoalType.MAINTAIN_WEIGHT,
                    calories = BigDecimal("2000.00"),
                    protein = BigDecimal("100.000"),
                    carbs = BigDecimal("200.000"),
                    fat = BigDecimal("70.000"),
                ),
            )
        )

        assertEquals(DailyScoreMode.GOAL_ADHERENCE, result.mode)
        assertEquals(97, result.score)
        assertEquals(100, result.breakdown.calorieScore)
        assertEquals(89, result.breakdown.proteinScore)
        assertEquals(100, result.breakdown.carbohydrateScore)
        assertEquals(100, result.breakdown.fatScore)
    }

    @Test
    fun `consistency rewards meal coverage without raw entry count inflation`() {
        val oneEntryPerMeal = engine.calculate(
            baseInput().copy(
                logged = true,
                loggedMealCount = 3,
                loggedMealTypes = setOf("BREAKFAST", "LUNCH", "DINNER"),
            )
        )
        val manySnackEntries = engine.calculate(
            baseInput().copy(
                logged = true,
                loggedMealCount = 9,
                loggedMealTypes = setOf("SNACK"),
            )
        )

        assertEquals(DailyScoreMode.CONSISTENCY, oneEntryPerMeal.mode)
        assertEquals(90, oneEntryPerMeal.score)
        assertEquals(10, manySnackEntries.score)
    }

    @Test
    fun `scores clamp to zero and one hundred`() {
        val perfect = engine.calculate(
            baseInput().copy(
                totals = DailyScoreNutritionTotals(
                    calories = BigDecimal("2000.00"),
                    protein = BigDecimal("150.000"),
                    carbs = BigDecimal("200.000"),
                    fat = BigDecimal("70.000"),
                ),
                target = DailyScoreTarget(
                    goalId = UUID.randomUUID(),
                    goalType = GoalType.GAIN_WEIGHT,
                    calories = BigDecimal("2000.00"),
                    protein = BigDecimal("100.000"),
                    carbs = BigDecimal("200.000"),
                    fat = BigDecimal("70.000"),
                ),
            )
        )
        val empty = engine.calculate(baseInput())

        assertEquals(100, perfect.score)
        assertEquals(0, empty.score)
    }

    @Test
    fun `protein earns full credit at ninety percent and scales below it`() {
        val target = DailyScoreTarget(UUID.randomUUID(), GoalType.MAINTAIN_WEIGHT, BigDecimal("2000"), BigDecimal("100"), BigDecimal("200"), BigDecimal("70"))
        val exact = engine.calculate(baseInput().copy(totals = DailyScoreNutritionTotals(BigDecimal("2000"), BigDecimal("90"), BigDecimal("200"), BigDecimal("70")), target = target))
        val below = engine.calculate(baseInput().copy(totals = DailyScoreNutritionTotals(BigDecimal("2000"), BigDecimal("89.9"), BigDecimal("200"), BigDecimal("70")), target = target))
        val above = engine.calculate(baseInput().copy(totals = DailyScoreNutritionTotals(BigDecimal("2000"), BigDecimal("150"), BigDecimal("200"), BigDecimal("70")), target = target))

        assertEquals(100, exact.breakdown.proteinScore)
        assertEquals(100, above.breakdown.proteinScore)
        assertEquals(99, below.breakdown.proteinScore)
        assertEquals("2026-07-23", exact.breakdown.formulaVersion)
    }

    private fun baseInput(): DailyScoreInput {
        return DailyScoreInput(
            date = LocalDate.parse("2026-06-18"),
            logged = false,
            loggedMealCount = 0,
            loggedMealTypes = emptySet(),
            totals = DailyScoreNutritionTotals(
                calories = BigDecimal.ZERO,
                protein = BigDecimal.ZERO,
                carbs = BigDecimal.ZERO,
                fat = BigDecimal.ZERO,
            ),
            target = null,
        )
    }
}
