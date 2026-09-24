package com.gyro.api.goal.application.calculator

import com.gyro.api.common.decimal.NutritionDecimal
import com.gyro.api.common.error.InvalidNutritionGoalException
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Period
import kotlin.math.ceil
import kotlin.math.floor

interface EnergyExpenditureFormula {
    val descriptor: FormulaDescriptor

    fun restingMetabolicRate(input: NormalizedGoalPreviewInput): BigDecimal
}

@Service
class MifflinStJeorFormula : EnergyExpenditureFormula {
    override val descriptor = FormulaDescriptor(
        name = "MIFFLIN_ST_JEOR",
        version = "1",
    )

    override fun restingMetabolicRate(input: NormalizedGoalPreviewInput): BigDecimal {
        val sexAdjustment = when (input.sex) {
            GoalCalculatorSex.MALE -> BigDecimal("5")
            GoalCalculatorSex.FEMALE -> BigDecimal("-161")
        }
        return input.currentWeightKg.multiply(BigDecimal("10"))
            .add(input.heightCm.multiply(BigDecimal("6.25")))
            .subtract(BigDecimal(input.ageYears).multiply(BigDecimal("5")))
            .add(sexAdjustment)
            .setScale(2, RoundingMode.HALF_UP)
    }
}

@Service
class GoalProfileInputNormalizer {
    fun normalize(
        input: GoalPreviewInput,
        calculationDate: LocalDate,
    ): NormalizedGoalPreviewInput {
        val age = Period.between(input.birthDate, calculationDate).years
        if (age < 0) {
            throw InvalidNutritionGoalException("birthDate cannot be in the future.")
        }
        val heightCm = NutritionDecimal.weight(input.heightCm)
        val currentWeightKg = NutritionDecimal.weight(input.currentWeightKg)
        val targetWeightKg = input.targetWeightKg?.let(NutritionDecimal::weight)
        validateMeasurementBounds(heightCm, currentWeightKg, targetWeightKg)
        validateGoalDirection(input.goalType, currentWeightKg, targetWeightKg)

        return NormalizedGoalPreviewInput(
            sex = input.sex,
            birthDate = input.birthDate,
            ageYears = age,
            heightCm = heightCm,
            currentWeightKg = currentWeightKg,
            targetWeightKg = targetWeightKg,
            dailyMovementLevel = input.dailyMovementLevel,
            workoutFrequency = input.workoutFrequency,
            goalType = input.goalType,
            speed = input.speed,
            calculationDate = calculationDate,
        )
    }

    private fun validateGoalDirection(
        goalType: GoalType,
        currentWeightKg: BigDecimal,
        targetWeightKg: BigDecimal?,
    ) {
        when (goalType) {
            GoalType.LOSE_WEIGHT -> if (targetWeightKg != null && targetWeightKg >= currentWeightKg) {
                throw InvalidNutritionGoalException("targetWeightKg must be below currentWeightKg for LOSE_WEIGHT.")
            }

            GoalType.GAIN_WEIGHT -> if (targetWeightKg != null && targetWeightKg <= currentWeightKg) {
                throw InvalidNutritionGoalException("targetWeightKg must be above currentWeightKg for GAIN_WEIGHT.")
            }

            GoalType.MAINTAIN_WEIGHT -> return
        }
    }

    private fun validateMeasurementBounds(
        heightCm: BigDecimal,
        currentWeightKg: BigDecimal,
        targetWeightKg: BigDecimal?,
    ) {
        if (heightCm < MIN_HEIGHT_CM || heightCm > MAX_HEIGHT_CM) {
            throw InvalidNutritionGoalException("heightCm must be between 50 and 300.")
        }
        if (currentWeightKg < MIN_WEIGHT_KG || currentWeightKg > MAX_WEIGHT_KG) {
            throw InvalidNutritionGoalException("currentWeightKg must be between 20 and 500.")
        }
        if (targetWeightKg != null && (targetWeightKg < MIN_WEIGHT_KG || targetWeightKg > MAX_WEIGHT_KG)) {
            throw InvalidNutritionGoalException("targetWeightKg must be between 20 and 500.")
        }
    }

    companion object {
        private val MIN_HEIGHT_CM = BigDecimal("50.000")
        private val MAX_HEIGHT_CM = BigDecimal("300.000")
        private val MIN_WEIGHT_KG = BigDecimal("20.000")
        private val MAX_WEIGHT_KG = BigDecimal("500.000")
    }
}

@Service
class RmrCalculator(
    private val formula: EnergyExpenditureFormula,
) {
    fun calculate(input: NormalizedGoalPreviewInput): BigDecimal {
        return formula.restingMetabolicRate(input)
    }

    fun formulaDescriptor(): FormulaDescriptor {
        return formula.descriptor
    }
}

@Service
class ActivityCalculator {
    fun activityFactor(input: NormalizedGoalPreviewInput): BigDecimal {
        return movementFactor(input.dailyMovementLevel)
            .add(workoutAdjustment(input.workoutFrequency))
            .coerceIn(MIN_ACTIVITY_FACTOR, MAX_ACTIVITY_FACTOR)
            .setScale(3, RoundingMode.HALF_UP)
    }

    private fun movementFactor(level: DailyMovementLevel): BigDecimal {
        return when (level) {
            DailyMovementLevel.SEDENTARY -> BigDecimal("1.200")
            DailyMovementLevel.LIGHT -> BigDecimal("1.350")
            DailyMovementLevel.MODERATE -> BigDecimal("1.500")
            DailyMovementLevel.ACTIVE -> BigDecimal("1.650")
            DailyMovementLevel.VERY_ACTIVE -> BigDecimal("1.800")
        }
    }

    private fun workoutAdjustment(frequency: WorkoutFrequency): BigDecimal {
        return when (frequency) {
            WorkoutFrequency.ZERO_DAYS -> BigDecimal("0.000")
            WorkoutFrequency.ONE_TO_TWO_DAYS -> BigDecimal("0.050")
            WorkoutFrequency.THREE_TO_FOUR_DAYS -> BigDecimal("0.100")
            WorkoutFrequency.FIVE_TO_SIX_DAYS -> BigDecimal("0.150")
            WorkoutFrequency.DAILY -> BigDecimal("0.200")
        }
    }

    private fun BigDecimal.coerceIn(
        min: BigDecimal,
        max: BigDecimal,
    ): BigDecimal {
        return when {
            this < min -> min
            this > max -> max
            else -> this
        }
    }

    companion object {
        private val MIN_ACTIVITY_FACTOR = BigDecimal("1.200")
        private val MAX_ACTIVITY_FACTOR = BigDecimal("2.000")
    }
}

@Service
class TdeeCalculator {
    fun calculate(
        restingMetabolicRate: BigDecimal,
        activityFactor: BigDecimal,
    ): BigDecimal {
        return NutritionDecimal.calories(restingMetabolicRate.multiply(activityFactor))
    }
}

data class GoalRecommendation(
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val targetCalories: BigDecimal,
)

@Service
class GoalCalculator {
    fun recommend(
        input: NormalizedGoalPreviewInput,
        maintenanceCalories: BigDecimal,
    ): GoalRecommendation {
        if (input.goalType == GoalType.MAINTAIN_WEIGHT) {
            return GoalRecommendation(
                dailyEnergyDelta = BigDecimal("0.00"),
                weeklyWeightChangeKg = BigDecimal("0.000"),
                targetCalories = maintenanceCalories,
            )
        }

        val weeklyWeightChangeKg = input.currentWeightKg
            .multiply(input.speed.percentBodyWeightPerWeek())
            .divide(BigDecimal("100"), 3, RoundingMode.HALF_UP)
        val unsignedDailyDelta = weeklyWeightChangeKg
            .multiply(CALORIES_PER_KG)
            .divide(BigDecimal("7"), 2, RoundingMode.HALF_UP)

        val signedDelta = when (input.goalType) {
            GoalType.LOSE_WEIGHT -> unsignedDailyDelta.negate()
            GoalType.GAIN_WEIGHT -> unsignedDailyDelta
            GoalType.MAINTAIN_WEIGHT -> BigDecimal.ZERO
        }

        return GoalRecommendation(
            dailyEnergyDelta = signedDelta.setScale(2, RoundingMode.HALF_UP),
            weeklyWeightChangeKg = when (input.goalType) {
                GoalType.LOSE_WEIGHT -> weeklyWeightChangeKg.negate()
                GoalType.GAIN_WEIGHT -> weeklyWeightChangeKg
                GoalType.MAINTAIN_WEIGHT -> BigDecimal.ZERO
            }.setScale(3, RoundingMode.HALF_UP),
            targetCalories = NutritionDecimal.calories(maintenanceCalories.add(signedDelta)),
        )
    }

    private fun GoalChangeSpeed.percentBodyWeightPerWeek(): BigDecimal {
        return when (this) {
            GoalChangeSpeed.CONSERVATIVE -> BigDecimal("0.50")
            GoalChangeSpeed.BALANCED -> BigDecimal("0.75")
            GoalChangeSpeed.AGGRESSIVE -> BigDecimal("1.00")
        }
    }

    companion object {
        private val CALORIES_PER_KG = BigDecimal("7700")
    }
}

@Service
class TimelineCalculator {
    fun estimate(
        input: NormalizedGoalPreviewInput,
        weeklyWeightChangeKg: BigDecimal,
    ): TimelineEstimate {
        val targetWeightKg = input.targetWeightKg
        if (input.goalType == GoalType.MAINTAIN_WEIGHT || targetWeightKg == null || weeklyWeightChangeKg.signum() == 0) {
            return TimelineEstimate(
                estimatedWeeksMin = 0,
                estimatedWeeksMax = 0,
                estimatedMonths = BigDecimal("0.0"),
                estimatedTargetDate = null,
            )
        }

        val totalChangeKg = targetWeightKg.subtract(input.currentWeightKg).abs()
        val weeklyChangeKg = weeklyWeightChangeKg.abs()
        val exactWeeks = totalChangeKg.divide(weeklyChangeKg, 4, RoundingMode.HALF_UP)
        val exactWeeksDouble = exactWeeks.toDouble()
        val minWeeks = floor(exactWeeksDouble * 0.90).toInt().coerceAtLeast(1)
        val maxWeeks = ceil(exactWeeksDouble * 1.10).toInt().coerceAtLeast(minWeeks)

        return TimelineEstimate(
            estimatedWeeksMin = minWeeks,
            estimatedWeeksMax = maxWeeks,
            estimatedMonths = BigDecimal(maxWeeks)
                .divide(WEEKS_PER_MONTH, 1, RoundingMode.HALF_UP),
            estimatedTargetDate = input.calculationDate.plusWeeks(maxWeeks.toLong()),
        )
    }

    companion object {
        private val WEEKS_PER_MONTH = BigDecimal("4.345")
    }
}

@Service
class MacroCalculator {
    fun calculate(
        input: NormalizedGoalPreviewInput,
        targetCalories: BigDecimal,
    ): MacroRecommendation {
        val proteinGrams = input.currentWeightKg.multiply(BigDecimal("1.800")).setScale(3, RoundingMode.HALF_UP)
        val proteinCalories = proteinGrams.multiply(CALORIES_PER_PROTEIN_GRAM).setScale(2, RoundingMode.HALF_UP)
        val fatCalories = targetCalories.multiply(BigDecimal("0.250")).setScale(2, RoundingMode.HALF_UP)
        val fatGrams = fatCalories.divide(CALORIES_PER_FAT_GRAM, 3, RoundingMode.HALF_UP)
        val remainingCalories = targetCalories.subtract(proteinCalories).subtract(fatCalories).max(BigDecimal.ZERO)
        val carbsGrams = remainingCalories.divide(CALORIES_PER_CARB_GRAM, 3, RoundingMode.HALF_UP)
        val carbsCalories = carbsGrams.multiply(CALORIES_PER_CARB_GRAM).setScale(2, RoundingMode.HALF_UP)

        return MacroRecommendation(
            proteinGrams = proteinGrams,
            proteinCalories = proteinCalories,
            carbsGrams = carbsGrams,
            carbsCalories = carbsCalories,
            fatGrams = fatGrams,
            fatCalories = fatCalories,
        )
    }

    companion object {
        private val CALORIES_PER_PROTEIN_GRAM = BigDecimal("4")
        private val CALORIES_PER_CARB_GRAM = BigDecimal("4")
        private val CALORIES_PER_FAT_GRAM = BigDecimal("9")
    }
}

@Service
class GoalSafetyValidator {
    fun validate(
        input: NormalizedGoalPreviewInput,
        targetCalories: BigDecimal,
    ): List<GoalSafetyWarning> {
        val warnings = mutableListOf<GoalSafetyWarning>()
        val currentBmi = bmi(input.currentWeightKg, input.heightCm)
        val targetBmi = input.targetWeightKg?.let { bmi(it, input.heightCm) }
        val targetWeightChangePercent = input.targetWeightKg?.let { targetWeightKg ->
            targetWeightKg.subtract(input.currentWeightKg).abs()
                .multiply(BigDecimal("100"))
                .divide(input.currentWeightKg, 2, RoundingMode.HALF_UP)
        }

        if (input.ageYears < 18) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.UNDER_18,
                message = "Goal estimates are not suitable for minors.",
                blocking = true,
            )
        }
        if (currentBmi < MIN_HEALTHY_BMI) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.LOW_CURRENT_BMI,
                message = "Current BMI is below the healthy range.",
                blocking = input.goalType == GoalType.LOSE_WEIGHT,
            )
        }
        if (targetBmi != null && targetBmi < MIN_HEALTHY_BMI) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.LOW_TARGET_BMI,
                message = "Target BMI is below the healthy range.",
                blocking = true,
            )
        }
        if (input.goalType == GoalType.LOSE_WEIGHT && input.speed == GoalChangeSpeed.AGGRESSIVE) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.AGGRESSIVE_WEIGHT_LOSS,
                message = "This weekly weight change is aggressive.",
                blocking = false,
            )
        }
        if (targetCalories < minimumCalories(input.sex)) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.LOW_TARGET_CALORIES,
                message = "Target calories are below the configured minimum threshold.",
                blocking = true,
            )
        }
        if (targetWeightChangePercent != null && targetWeightChangePercent > MAX_TARGET_WEIGHT_CHANGE_PERCENT) {
            warnings += GoalSafetyWarning(
                code = GoalSafetyWarningCode.UNREALISTIC_TARGET_WEIGHT_CHANGE,
                message = "Target weight change is outside the recommended first-release planning range.",
                blocking = false,
            )
        }

        return warnings
    }

    private fun minimumCalories(sex: GoalCalculatorSex): BigDecimal {
        return when (sex) {
            GoalCalculatorSex.FEMALE -> BigDecimal("1200.00")
            GoalCalculatorSex.MALE -> BigDecimal("1500.00")
        }
    }

    private fun bmi(
        weightKg: BigDecimal,
        heightCm: BigDecimal,
    ): BigDecimal {
        val heightM = heightCm.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
        return weightKg.divide(heightM.multiply(heightM), 2, RoundingMode.HALF_UP)
    }

    companion object {
        private val MIN_HEALTHY_BMI = BigDecimal("18.50")
        private val MAX_TARGET_WEIGHT_CHANGE_PERCENT = BigDecimal("35.00")
    }
}

@Service
class GoalPreviewService(
    private val inputNormalizer: GoalProfileInputNormalizer,
    private val rmrCalculator: RmrCalculator,
    private val activityCalculator: ActivityCalculator,
    private val tdeeCalculator: TdeeCalculator,
    private val goalCalculator: GoalCalculator,
    private val timelineCalculator: TimelineCalculator,
    private val macroCalculator: MacroCalculator,
    private val goalSafetyValidator: GoalSafetyValidator,
) {
    fun preview(
        input: GoalPreviewInput,
        calculationDate: LocalDate,
        maintenanceOverride: BigDecimal? = null,
    ): GoalPreviewResult {
        val normalizedInput = inputNormalizer.normalize(input, calculationDate)
        val rmr = rmrCalculator.calculate(normalizedInput)
        val activityFactor = activityCalculator.activityFactor(normalizedInput)
        val maintenanceCalories = maintenanceOverride?.let(NutritionDecimal::calories)
            ?: tdeeCalculator.calculate(rmr, activityFactor)
        val recommendation = goalCalculator.recommend(normalizedInput, maintenanceCalories)
        val timeline = timelineCalculator.estimate(normalizedInput, recommendation.weeklyWeightChangeKg)
        val macros = macroCalculator.calculate(normalizedInput, recommendation.targetCalories)

        return GoalPreviewResult(
            formula = rmrCalculator.formulaDescriptor(),
            calculationDate = calculationDate,
            maintenanceCalories = maintenanceCalories,
            targetCalories = recommendation.targetCalories,
            activityFactor = activityFactor,
            dailyEnergyDelta = recommendation.dailyEnergyDelta,
            weeklyWeightChangeKg = recommendation.weeklyWeightChangeKg,
            timeline = timeline,
            macros = macros,
            warnings = goalSafetyValidator.validate(normalizedInput, recommendation.targetCalories),
        )
    }
}
