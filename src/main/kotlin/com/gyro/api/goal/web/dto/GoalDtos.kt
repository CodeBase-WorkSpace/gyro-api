package com.gyro.api.goal.web.dto

import com.gyro.api.goal.application.ActiveNutritionPlanView
import com.gyro.api.goal.application.CurrentGoalAggregateView
import com.gyro.api.goal.application.DailyTargetView
import com.gyro.api.goal.application.GoalConfigurationStatus
import com.gyro.api.goal.application.GoalOutcomeView
import com.gyro.api.goal.application.NutritionTargetsView
import com.gyro.api.goal.application.PlanCalculatorView
import com.gyro.api.goal.application.PlanScheduleView
import com.gyro.api.goal.application.WeightValueView
import com.gyro.api.goal.application.calculator.GoalChangeSpeed
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.WorkoutFrequency
import com.gyro.api.weight.domain.WeightUnit
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class GoalResponse(
    val status: GoalConfigurationStatus,
    val goal: GoalOutcomeDto? = null,
    val activePlan: NutritionPlanDto? = null,
    val todayTarget: TodayTargetDto? = null,
)

data class GoalOutcomeDto(
    val targetWeight: WeightValueDto?,
    val targetDate: LocalDate?,
)

data class WeightValueDto(
    val value: BigDecimal,
    val unit: WeightUnit,
)

data class NutritionPlanDto(
    val id: UUID,
    val goalType: GoalType?,
    val startDate: LocalDate,
    val baseTargets: NutritionTargetsDto,
    val calculator: PlanCalculatorDto?,
    val schedule: PlanScheduleDto?,
)

data class NutritionTargetsDto(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal?,
)

data class PlanCalculatorDto(
    val formula: String,
    val formulaVersion: String,
    val maintenanceCalories: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val expectedWeeklyWeightChangeKg: BigDecimal,
    val profile: PlanCalculatorProfileDto?,
    val maintenanceSource: CalculatorMaintenanceSource,
    val formulaMaintenanceCalories: BigDecimal,
    val observationBasis: Map<String, Any?>?,
)

data class PlanCalculatorProfileDto(
    val sex: GoalCalculatorSex,
    val birthDate: LocalDate,
    val heightCm: BigDecimal,
    val currentWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal?,
    val dailyMovementLevel: DailyMovementLevel,
    val workoutFrequency: WorkoutFrequency,
    val speed: GoalChangeSpeed?,
)

data class PlanScheduleDto(
    val type: GoalScheduleType,
    val activeFrom: LocalDate,
    val activeTo: LocalDate?,
    val weeklyCalorieBudget: BigDecimal?,
    val weekdayTargets: Map<String, Any?>,
    val dateOverrides: Map<String, Any?>,
    val macroAdjustmentMode: MacroTargetAdjustmentMode,
    val dietMode: String?,
)

data class TodayTargetDto(
    val date: LocalDate,
    val source: DailyTargetSource,
    val sourceDetail: String?,
    val targets: NutritionTargetsDto,
)

fun CurrentGoalAggregateView.toResponse(): GoalResponse {
    return GoalResponse(
        status = status,
        goal = goal?.toDto(),
        activePlan = activePlan?.toDto(),
        todayTarget = todayTarget?.toDto(),
    )
}

private fun GoalOutcomeView.toDto(): GoalOutcomeDto {
    return GoalOutcomeDto(
        targetWeight = targetWeight?.toDto(),
        targetDate = targetDate,
    )
}

private fun WeightValueView.toDto(): WeightValueDto {
    return WeightValueDto(
        value = value,
        unit = unit,
    )
}

private fun ActiveNutritionPlanView.toDto(): NutritionPlanDto {
    return NutritionPlanDto(
        id = id,
        goalType = goalType,
        startDate = startDate,
        baseTargets = baseTargets.toDto(),
        calculator = calculator?.toDto(),
        schedule = schedule?.toDto(),
    )
}

private fun NutritionTargetsView.toDto(): NutritionTargetsDto {
    return NutritionTargetsDto(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
    )
}

private fun PlanCalculatorView.toDto(): PlanCalculatorDto {
    return PlanCalculatorDto(
        formula = formula,
        formulaVersion = formulaVersion,
        maintenanceCalories = maintenanceCalories,
        dailyEnergyDelta = dailyEnergyDelta,
        expectedWeeklyWeightChangeKg = expectedWeeklyWeightChangeKg,
        profile = profile?.let {
            PlanCalculatorProfileDto(
                sex = it.sex,
                birthDate = it.birthDate,
                heightCm = it.heightCm,
                currentWeightKg = it.currentWeightKg,
                targetWeightKg = it.targetWeightKg,
                dailyMovementLevel = it.dailyMovementLevel,
                workoutFrequency = it.workoutFrequency,
                speed = it.speed,
            )
        },
        maintenanceSource = maintenanceSource,
        formulaMaintenanceCalories = formulaMaintenanceCalories,
        observationBasis = observationBasis,
    )
}

private fun PlanScheduleView.toDto(): PlanScheduleDto {
    return PlanScheduleDto(
        type = type,
        activeFrom = activeFrom,
        activeTo = activeTo,
        weeklyCalorieBudget = weeklyCalorieBudget,
        weekdayTargets = weekdayTargets,
        dateOverrides = dateOverrides,
        macroAdjustmentMode = macroAdjustmentMode,
        dietMode = dietMode,
    )
}

private fun DailyTargetView.toDto(): TodayTargetDto {
    return TodayTargetDto(
        date = date,
        source = source,
        sourceDetail = sourceDetail,
        targets = targets.toDto(),
    )
}
