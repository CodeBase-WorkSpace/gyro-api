package com.gyro.api.goal.application.calculator

import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import java.math.BigDecimal
import java.time.LocalDate

enum class GoalChangeSpeed {
    CONSERVATIVE,
    BALANCED,
    AGGRESSIVE,
}

data class GoalPreviewInput(
    val sex: GoalCalculatorSex,
    val birthDate: LocalDate,
    val heightCm: BigDecimal,
    val currentWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal?,
    val dailyMovementLevel: DailyMovementLevel,
    val workoutFrequency: WorkoutFrequency,
    val goalType: GoalType,
    val speed: GoalChangeSpeed = GoalChangeSpeed.BALANCED,
)

data class NormalizedGoalPreviewInput(
    val sex: GoalCalculatorSex,
    val birthDate: LocalDate,
    val ageYears: Int,
    val heightCm: BigDecimal,
    val currentWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal?,
    val dailyMovementLevel: DailyMovementLevel,
    val workoutFrequency: WorkoutFrequency,
    val goalType: GoalType,
    val speed: GoalChangeSpeed,
    val calculationDate: LocalDate,
)

data class GoalPreviewResult(
    val formula: FormulaDescriptor,
    val calculationDate: LocalDate,
    val maintenanceCalories: BigDecimal,
    val targetCalories: BigDecimal,
    val activityFactor: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val timeline: TimelineEstimate,
    val macros: MacroRecommendation,
    val warnings: List<GoalSafetyWarning>,
    val observedCalibration: ObservedGoalCalibration? = null,
)

enum class ObservedGoalCalibrationStatus {
    AVAILABLE,
    LOCKED,
    INSUFFICIENT_EVIDENCE,
    NO_MEANINGFUL_DIFFERENCE,
    NOT_APPLICABLE,
}

data class ObservedGoalCalibration(
    val status: ObservedGoalCalibrationStatus,
    val windowDays: Int? = null,
    val windowStart: LocalDate? = null,
    val windowEnd: LocalDate? = null,
    val loggedDays: Int = 0,
    val loggedDaysRequired: Int = 0,
    val weighInDays: Int = 0,
    val weightSpanDays: Long = 0,
    val confidence: String? = null,
    val recommendation: ObservedGoalRecommendation? = null,
)

data class ObservedGoalRecommendation(
    val maintenanceCalories: BigDecimal,
    val targetCalories: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val timeline: TimelineEstimate,
    val macros: MacroRecommendation,
    val warnings: List<GoalSafetyWarning>,
    val observationBasis: Map<String, Any?>,
)

data class FormulaDescriptor(
    val name: String,
    val version: String,
)

data class TimelineEstimate(
    val estimatedWeeksMin: Int,
    val estimatedWeeksMax: Int,
    val estimatedMonths: BigDecimal,
    val estimatedTargetDate: LocalDate?,
)

data class MacroRecommendation(
    val proteinGrams: BigDecimal,
    val proteinCalories: BigDecimal,
    val carbsGrams: BigDecimal,
    val carbsCalories: BigDecimal,
    val fatGrams: BigDecimal,
    val fatCalories: BigDecimal,
)

enum class GoalSafetyWarningCode {
    UNDER_18,
    LOW_CURRENT_BMI,
    LOW_TARGET_BMI,
    LOW_TARGET_CALORIES,
    AGGRESSIVE_WEIGHT_LOSS,
    UNREALISTIC_TARGET_WEIGHT_CHANGE,
}

data class GoalSafetyWarning(
    val code: GoalSafetyWarningCode,
    val message: String,
    val blocking: Boolean,
)
