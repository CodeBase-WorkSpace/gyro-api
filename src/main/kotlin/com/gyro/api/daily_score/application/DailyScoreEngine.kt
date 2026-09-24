package com.gyro.api.daily_score.application

import com.gyro.api.goal.domain.NutritionSuccessPolicy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

@Component
class DailyScoreEngine {
    fun calculate(input: DailyScoreInput): DailyScoreResult {
        val target = input.target
        return if (target != null) {
            goalAdherence(input = input, target = target)
        } else {
            consistency(input)
        }
    }

    private fun goalAdherence(input: DailyScoreInput, target: DailyScoreTarget): DailyScoreResult {
        val calorieScore = rangeScore(
            actual = input.totals.calories,
            target = target.calories,
            fullCreditTolerance = BigDecimal("0.05"),
            zeroCreditTolerance = BigDecimal("0.50"),
        )
        val proteinScore = floorScore(
            actual = input.totals.protein,
            target = target.protein,
        )
        val carbScore = rangeScore(
            actual = input.totals.carbs,
            target = target.carbs,
            fullCreditTolerance = BigDecimal("0.15"),
            zeroCreditTolerance = BigDecimal("0.75"),
        )
        val fatScore = rangeScore(
            actual = input.totals.fat,
            target = target.fat,
            fullCreditTolerance = BigDecimal("0.15"),
            zeroCreditTolerance = BigDecimal("0.75"),
        )
        val score = (
            BigDecimal(calorieScore).multiply(CALORIE_WEIGHT) +
                BigDecimal(proteinScore).multiply(PROTEIN_WEIGHT) +
                BigDecimal(carbScore).multiply(CARBOHYDRATE_WEIGHT) +
                BigDecimal(fatScore).multiply(FAT_WEIGHT)
            ).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 100)

        return DailyScoreResult(
            score = score,
            mode = DailyScoreMode.GOAL_ADHERENCE,
            goalId = target.goalId,
            goalType = target.goalType,
            breakdown = input.breakdown(
                calorieScore = calorieScore,
                proteinScore = proteinScore,
                carbohydrateScore = carbScore,
                fatScore = fatScore,
                loggingCompletenessScore = consistencyScore(input),
                target = target,
            ),
        )
    }

    private fun consistency(input: DailyScoreInput): DailyScoreResult {
        val score = consistencyScore(input)
        return DailyScoreResult(
            score = score,
            mode = DailyScoreMode.CONSISTENCY,
            goalId = null,
            goalType = null,
            breakdown = input.breakdown(
                calorieScore = null,
                proteinScore = null,
                carbohydrateScore = null,
                fatScore = null,
                loggingCompletenessScore = score,
                target = null,
            ),
        )
    }

    private fun consistencyScore(input: DailyScoreInput): Int {
        if (!input.logged || input.loggedMealCount <= 0) {
            return 0
        }
        val majorScore = MAJOR_MEALS.count { it in input.loggedMealTypes } * 30
        val snackBonus = if (input.loggedMealTypes.any { it == "SNACK" || it == "CUSTOM" }) 10 else 0
        return (majorScore + snackBonus).coerceIn(0, 100)
    }

    private fun rangeScore(
        actual: BigDecimal,
        target: BigDecimal,
        fullCreditTolerance: BigDecimal,
        zeroCreditTolerance: BigDecimal,
    ): Int {
        if (target.signum() <= 0) {
            return 100
        }
        val ratio = actual.subtract(target).abs().divide(target, 6, RoundingMode.HALF_UP)
        if (ratio <= fullCreditTolerance) {
            return 100
        }
        if (ratio >= zeroCreditTolerance) {
            return 0
        }
        val penaltyRatio = ratio.subtract(fullCreditTolerance)
            .divide(zeroCreditTolerance.subtract(fullCreditTolerance), 6, RoundingMode.HALF_UP)
        return BigDecimal("100")
            .subtract(penaltyRatio.multiply(BigDecimal("100")))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun floorScore(actual: BigDecimal, target: BigDecimal): Int {
        if (target.signum() <= 0) {
            return 100
        }
        val successTarget = target.multiply(NutritionSuccessPolicy.PROTEIN_TARGET_RATIO)
        if (actual >= successTarget) {
            return 100
        }
        // A below-threshold value must never round into full credit.
        return actual.divide(successTarget, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 99)
    }

    private fun DailyScoreInput.breakdown(
        calorieScore: Int?,
        proteinScore: Int?,
        carbohydrateScore: Int?,
        fatScore: Int?,
        loggingCompletenessScore: Int,
        target: DailyScoreTarget?,
    ): DailyScoreBreakdown {
        val sortedMealTypes = loggedMealTypes.sorted()
        return DailyScoreBreakdown(
            formulaName = FORMULA_NAME,
            formulaVersion = FORMULA_VERSION,
            componentWeights = COMPONENT_WEIGHTS,
            calorieScore = calorieScore,
            proteinScore = proteinScore,
            fatScore = fatScore,
            carbohydrateScore = carbohydrateScore,
            loggingCompletenessScore = loggingCompletenessScore,
            loggedMealCount = loggedMealCount,
            loggedMealTypes = sortedMealTypes,
            missingMajorMeals = MAJOR_MEALS.filter { it !in loggedMealTypes },
            totalCalories = totals.calories.setScale(2, RoundingMode.HALF_UP),
            targetCalories = target?.calories?.setScale(2, RoundingMode.HALF_UP),
            calorieDelta = target?.let { totals.calories.subtract(it.calories).setScale(2, RoundingMode.HALF_UP) },
            totalProtein = totals.protein.setScale(3, RoundingMode.HALF_UP),
            targetProtein = target?.protein?.setScale(3, RoundingMode.HALF_UP),
            proteinDelta = target?.let { totals.protein.subtract(it.protein).setScale(3, RoundingMode.HALF_UP) },
            totalFat = totals.fat.setScale(3, RoundingMode.HALF_UP),
            targetFat = target?.fat?.setScale(3, RoundingMode.HALF_UP),
            fatDelta = target?.let { totals.fat.subtract(it.fat).setScale(3, RoundingMode.HALF_UP) },
            totalCarbohydrates = totals.carbs.setScale(3, RoundingMode.HALF_UP),
            targetCarbohydrates = target?.carbs?.setScale(3, RoundingMode.HALF_UP),
            carbohydrateDelta = target?.let { totals.carbs.subtract(it.carbs).setScale(3, RoundingMode.HALF_UP) },
        )
    }

    data class DailyScoreResult(
        val score: Int,
        val mode: DailyScoreMode,
        val goalId: java.util.UUID?,
        val goalType: com.gyro.api.goal.domain.GoalType?,
        val breakdown: DailyScoreBreakdown,
    )

    companion object {
        const val FORMULA_NAME = "nutrition_daily_score"
        const val FORMULA_VERSION = "2026-07-23"

        val CALORIE_WEIGHT: BigDecimal = BigDecimal("0.40")
        val PROTEIN_WEIGHT: BigDecimal = BigDecimal("0.30")
        val CARBOHYDRATE_WEIGHT: BigDecimal = BigDecimal("0.15")
        val FAT_WEIGHT: BigDecimal = BigDecimal("0.15")
        val COMPONENT_WEIGHTS: Map<String, BigDecimal> = mapOf(
            "calories" to CALORIE_WEIGHT,
            "protein" to PROTEIN_WEIGHT,
            "carbohydrates" to CARBOHYDRATE_WEIGHT,
            "fat" to FAT_WEIGHT,
        )
        val MAJOR_MEALS: Set<String> = setOf("BREAKFAST", "LUNCH", "DINNER")
    }
}
