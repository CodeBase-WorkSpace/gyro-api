package com.gyro.api.goal.application.goal_schedule

import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class SavePlanScheduleCommand(
    val nutritionPlanId: UUID,
    val scheduleType: GoalScheduleType,
    val activeFrom: LocalDate,
    val activeTo: LocalDate? = null,
    val weeklyCalorieBudget: BigDecimal? = null,
    val weekdayTargets: Map<String, Any?> = emptyMap(),
    val dateOverrides: Map<String, Any?> = emptyMap(),
    val macroAdjustmentMode: MacroTargetAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
    val dietMode: String? = null,
    val formulaName: String? = null,
    val formulaVersion: String? = null,
    val scheduleSnapshot: Map<String, Any?> = emptyMap(),
)

data class PlanScheduleDraftCommand(
    val scheduleType: GoalScheduleType,
    val activeFrom: LocalDate,
    val activeTo: LocalDate? = null,
    val weeklyCalorieBudget: BigDecimal? = null,
    val weekdayTargets: Map<String, NutritionTargetsCommand> = emptyMap(),
    val dateOverrides: Map<LocalDate, NutritionTargetsCommand> = emptyMap(),
    val macroAdjustmentMode: MacroTargetAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
    val dietMode: String? = null,
    val scheduleSnapshot: Map<String, Any?> = emptyMap(),
)

data class NutritionTargetsCommand(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal? = null,
)

fun flatPlanScheduleDraft(startDate: LocalDate): PlanScheduleDraftCommand {
    return PlanScheduleDraftCommand(
        scheduleType = GoalScheduleType.FLAT,
        activeFrom = startDate,
    )
}

fun PlanScheduleDraftCommand.toSaveCommand(
    nutritionPlanId: UUID,
    formulaName: String?,
    formulaVersion: String?,
    baseTargets: NutritionTargetsCommand,
): SavePlanScheduleCommand {
    return SavePlanScheduleCommand(
        nutritionPlanId = nutritionPlanId,
        scheduleType = scheduleType,
        activeFrom = activeFrom,
        activeTo = activeTo,
        weeklyCalorieBudget = weeklyCalorieBudget,
        weekdayTargets = weekdayTargets.mapValues { it.value.toSnapshot() },
        dateOverrides = dateOverrides.mapKeys { it.key.toString() }.mapValues { it.value.toSnapshot() },
        macroAdjustmentMode = macroAdjustmentMode,
        dietMode = dietMode,
        formulaName = formulaName,
        formulaVersion = formulaVersion,
        scheduleSnapshot = mapOf(
            "scheduleType" to scheduleType.name,
            "activeFrom" to activeFrom.toString(),
            "activeTo" to activeTo?.toString(),
            "baseTargets" to baseTargets.toSnapshot(),
        ) + scheduleSnapshot,
    )
}

private fun NutritionTargetsCommand.toSnapshot(): Map<String, Any?> {
    return mapOf(
        "calories" to calories,
        "protein" to protein,
        "carbs" to carbs,
        "fat" to fat,
        "fiber" to fiber,
    )
}
