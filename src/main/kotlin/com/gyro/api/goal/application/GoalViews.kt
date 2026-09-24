package com.gyro.api.goal.application

import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.application.nutrition_plan.GoalOutcomeReadModel
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanCalculatorReadModel
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanReadModel
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.goal.application.nutrition_plan.PlanScheduleReadModel
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanCalculatorProfile
import com.gyro.api.weight.domain.WeightUnit
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class GoalConfigurationStatus {
    UNCONFIGURED,
    CONFIGURED,
}

data class CurrentGoalAggregateView(
    val status: GoalConfigurationStatus,
    val goal: GoalOutcomeView?,
    val activePlan: ActiveNutritionPlanView?,
    val todayTarget: DailyTargetView?,
)

data class GoalOutcomeView(
    val targetWeight: WeightValueView?,
    val targetDate: LocalDate?,
)

data class WeightValueView(
    val value: BigDecimal,
    val unit: WeightUnit,
)

data class ActiveNutritionPlanView(
    val id: UUID,
    val goalType: GoalType?,
    val startDate: LocalDate,
    val baseTargets: NutritionTargetsView,
    val calculator: PlanCalculatorView?,
    val schedule: PlanScheduleView?,
)

data class NutritionTargetsView(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal?,
)

data class PlanCalculatorView(
    val formula: String,
    val formulaVersion: String,
    val maintenanceCalories: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val expectedWeeklyWeightChangeKg: BigDecimal,
    val profile: NutritionPlanCalculatorProfile?,
    val maintenanceSource: CalculatorMaintenanceSource,
    val formulaMaintenanceCalories: BigDecimal,
    val observationBasis: Map<String, Any?>?,
)

data class PlanScheduleView(
    val type: GoalScheduleType,
    val activeFrom: LocalDate,
    val activeTo: LocalDate?,
    val weeklyCalorieBudget: BigDecimal?,
    val weekdayTargets: Map<String, Any?>,
    val dateOverrides: Map<String, Any?>,
    val macroAdjustmentMode: MacroTargetAdjustmentMode,
    val dietMode: String?,
)

data class DailyTargetView(
    val date: LocalDate,
    val source: DailyTargetSource,
    val sourceDetail: String?,
    val targets: NutritionTargetsView,
)

fun unconfiguredGoalView(): CurrentGoalAggregateView {
    return CurrentGoalAggregateView(
        status = GoalConfigurationStatus.UNCONFIGURED,
        goal = null,
        activePlan = null,
        todayTarget = null,
    )
}

fun NutritionPlanReadModel.toCurrentGoalAggregateView(): CurrentGoalAggregateView {
    return CurrentGoalAggregateView(
        status = GoalConfigurationStatus.CONFIGURED,
        goal = goalOutcome?.toView(),
        activePlan = toView(),
        todayTarget = dailyTarget.toView(),
    )
}

private fun GoalOutcomeReadModel.toView(): GoalOutcomeView {
    return GoalOutcomeView(
        targetWeight = if (targetWeight == null || targetWeightUnit == null) {
            null
        } else {
            WeightValueView(
                value = targetWeight,
                unit = targetWeightUnit,
            )
        },
        targetDate = targetDate,
    )
}

private fun NutritionPlanReadModel.toView(): ActiveNutritionPlanView {
    return ActiveNutritionPlanView(
        id = id,
        goalType = goalType,
        startDate = startDate,
        baseTargets = baseTargets.toView(),
        calculator = calculator?.toView(),
        schedule = planSchedule?.toView(),
    )
}

private fun DailyTargetReadModel.toView(): DailyTargetView {
    return DailyTargetView(
        date = date,
        source = source,
        sourceDetail = sourceDetail,
        targets = targets.toView(),
    )
}

private fun NutritionTargetsReadModel.toView(): NutritionTargetsView {
    return NutritionTargetsView(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
    )
}

private fun NutritionPlanCalculatorReadModel.toView(): PlanCalculatorView {
    return PlanCalculatorView(
        formula = formula,
        formulaVersion = formulaVersion,
        maintenanceCalories = maintenanceCalories,
        dailyEnergyDelta = dailyEnergyDelta,
        expectedWeeklyWeightChangeKg = expectedWeeklyWeightChangeKg,
        profile = profile,
        maintenanceSource = maintenanceSource,
        formulaMaintenanceCalories = formulaMaintenanceCalories,
        observationBasis = observationBasis,
    )
}

private fun PlanScheduleReadModel.toView(): PlanScheduleView {
    return PlanScheduleView(
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
