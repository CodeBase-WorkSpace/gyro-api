package com.gyro.api.goal.web.dto

import com.gyro.api.goal.application.calculator.*
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate

data class GoalPreviewRequest(
    @field:NotNull
    val sex: GoalCalculatorSex?,

    @field:NotNull
    val birthDate: LocalDate?,

    @field:NotNull
    @field:DecimalMin(value = "50.0")
    @field:DecimalMax(value = "300.0")
    val heightCm: BigDecimal?,

    @field:NotNull
    @field:DecimalMin(value = "20.0")
    @field:DecimalMax(value = "500.0")
    val currentWeightKg: BigDecimal?,

    @field:DecimalMin(value = "20.0")
    @field:DecimalMax(value = "500.0")
    val targetWeightKg: BigDecimal?,

    @field:NotNull
    val dailyMovementLevel: DailyMovementLevel?,

    @field:NotNull
    val workoutFrequency: WorkoutFrequency?,

    @field:NotNull
    val goalType: GoalType?,

    @field:NotNull
    val speed: GoalChangeSpeed?,
) {
    fun toInput(): GoalPreviewInput {
        return GoalPreviewInput(
            sex = requireNotNull(sex),
            birthDate = requireNotNull(birthDate),
            heightCm = requireNotNull(heightCm),
            currentWeightKg = requireNotNull(currentWeightKg),
            targetWeightKg = targetWeightKg,
            dailyMovementLevel = requireNotNull(dailyMovementLevel),
            workoutFrequency = requireNotNull(workoutFrequency),
            goalType = requireNotNull(goalType),
            speed = requireNotNull(speed),
        )
    }
}

data class GoalPreviewResponse(
    val formula: FormulaDescriptorResponse,
    val calculationDate: String,
    val maintenanceCalories: BigDecimal,
    val targetCalories: BigDecimal,
    val activityFactor: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val timeline: TimelineEstimateResponse,
    val macros: MacroRecommendationResponse,
    val warnings: List<GoalSafetyWarningResponse>,
    val observedCalibration: ObservedGoalCalibrationResponse?,
)

data class ObservedGoalCalibrationResponse(
    val status: ObservedGoalCalibrationStatus,
    val windowDays: Int?,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val loggedDays: Int,
    val loggedDaysRequired: Int,
    val weighInDays: Int,
    val weightSpanDays: Long,
    val confidence: String?,
    val recommendation: ObservedGoalRecommendationResponse?,
)

data class ObservedGoalRecommendationResponse(
    val maintenanceCalories: BigDecimal,
    val targetCalories: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val timeline: TimelineEstimateResponse,
    val macros: MacroRecommendationResponse,
    val warnings: List<GoalSafetyWarningResponse>,
    val observationBasis: Map<String, Any?>,
)

data class FormulaDescriptorResponse(
    val name: String,
    val version: String,
)

data class TimelineEstimateResponse(
    val estimatedWeeksMin: Int,
    val estimatedWeeksMax: Int,
    val estimatedMonths: BigDecimal,
    val estimatedTargetDate: String?,
)

data class MacroRecommendationResponse(
    val proteinGrams: BigDecimal,
    val proteinCalories: BigDecimal,
    val carbsGrams: BigDecimal,
    val carbsCalories: BigDecimal,
    val fatGrams: BigDecimal,
    val fatCalories: BigDecimal,
)

data class GoalSafetyWarningResponse(
    val code: GoalSafetyWarningCode,
    val message: String,
    val blocking: Boolean,
)

fun GoalPreviewResult.toResponse(): GoalPreviewResponse {
    return GoalPreviewResponse(
        formula = formula.toResponse(),
        calculationDate = calculationDate.toString(),
        maintenanceCalories = maintenanceCalories,
        targetCalories = targetCalories,
        activityFactor = activityFactor,
        dailyEnergyDelta = dailyEnergyDelta,
        weeklyWeightChangeKg = weeklyWeightChangeKg,
        timeline = timeline.toResponse(),
        macros = macros.toResponse(),
        warnings = warnings.map { it.toResponse() },
        observedCalibration = observedCalibration?.let { observed ->
            ObservedGoalCalibrationResponse(
                status = observed.status,
                windowDays = observed.windowDays,
                windowStart = observed.windowStart,
                windowEnd = observed.windowEnd,
                loggedDays = observed.loggedDays,
                loggedDaysRequired = observed.loggedDaysRequired,
                weighInDays = observed.weighInDays,
                weightSpanDays = observed.weightSpanDays,
                confidence = observed.confidence,
                recommendation = observed.recommendation?.let { recommendation ->
                    ObservedGoalRecommendationResponse(
                        maintenanceCalories = recommendation.maintenanceCalories,
                        targetCalories = recommendation.targetCalories,
                        dailyEnergyDelta = recommendation.dailyEnergyDelta,
                        weeklyWeightChangeKg = recommendation.weeklyWeightChangeKg,
                        timeline = recommendation.timeline.toResponse(),
                        macros = recommendation.macros.toResponse(),
                        warnings = recommendation.warnings.map { it.toResponse() },
                        observationBasis = recommendation.observationBasis,
                    )
                },
            )
        },
    )
}

private fun FormulaDescriptor.toResponse(): FormulaDescriptorResponse {
    return FormulaDescriptorResponse(
        name = name,
        version = version,
    )
}

private fun TimelineEstimate.toResponse(): TimelineEstimateResponse {
    return TimelineEstimateResponse(
        estimatedWeeksMin = estimatedWeeksMin,
        estimatedWeeksMax = estimatedWeeksMax,
        estimatedMonths = estimatedMonths,
        estimatedTargetDate = estimatedTargetDate?.toString(),
    )
}

private fun MacroRecommendation.toResponse(): MacroRecommendationResponse {
    return MacroRecommendationResponse(
        proteinGrams = proteinGrams,
        proteinCalories = proteinCalories,
        carbsGrams = carbsGrams,
        carbsCalories = carbsCalories,
        fatGrams = fatGrams,
        fatCalories = fatCalories,
    )
}

private fun GoalSafetyWarning.toResponse(): GoalSafetyWarningResponse {
    return GoalSafetyWarningResponse(
        code = code,
        message = message,
        blocking = blocking,
    )
}
