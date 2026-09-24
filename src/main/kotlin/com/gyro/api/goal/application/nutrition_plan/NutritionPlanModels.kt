package com.gyro.api.goal.application.nutrition_plan

import com.gyro.api.goal.application.goal_schedule.PlanScheduleDraftCommand
import com.gyro.api.goal.application.goal_schedule.flatPlanScheduleDraft
import com.gyro.api.goal.application.calculator.GoalChangeSpeed
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.domain.WorkoutFrequency
import com.gyro.api.weight.domain.WeightUnit
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SaveNutritionPlanCommand(
    val startDate: LocalDate,
    val goalType: GoalType? = null,

    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal? = null,

    val targetWeight: BigDecimal? = null,
    val targetWeightUnit: WeightUnit? = null,
    val targetDate: LocalDate? = null,

    val schedule: PlanScheduleDraftCommand = flatPlanScheduleDraft(startDate),

    val calculatorUpdateMode: CalculatorUpdateMode? = null,
    val calculatorSnapshot: NutritionPlanCalculatorSnapshot? = null,
    val acceptedWarningCodes: Set<String> = emptySet(),
    val blockingWarningCodes: Set<String> = emptySet(),
)

enum class CalculatorUpdateMode {
    PRESERVE,
    REPLACE,
    CLEAR,
}

data class NutritionPlanCalculatorSnapshot(
    val formula: String,
    val formulaVersion: String,
    val maintenanceCalories: BigDecimal,
    val targetCalories: BigDecimal,
    val activityFactor: BigDecimal,
    val dailyEnergyDelta: BigDecimal,
    val weeklyWeightChangeKg: BigDecimal,
    val estimatedWeeksMin: Int,
    val estimatedWeeksMax: Int,
    val estimatedTargetDate: LocalDate?,
    val recommendedProtein: BigDecimal,
    val recommendedCarbs: BigDecimal,
    val recommendedFat: BigDecimal,
    val recommendedFiber: BigDecimal? = null,
    val warningCodes: Set<String> = emptySet(),
    val profile: NutritionPlanCalculatorProfile? = null,
    val maintenanceSource: CalculatorMaintenanceSource = CalculatorMaintenanceSource.FORMULA,
    val formulaMaintenanceCalories: BigDecimal = maintenanceCalories,
    val observationBasis: Map<String, Any?>? = null,
)

/**
 * Historical inputs that produced this plan's formula estimate.
 *
 * These values are not the user's current profile. Saving a recalculated plan
 * replaces the snapshot, and deleting the plan or account removes it through
 * the existing plan ownership and account-deletion lifecycle.
 */
data class NutritionPlanCalculatorProfile(
    val sex: GoalCalculatorSex,
    val birthDate: LocalDate,
    val heightCm: BigDecimal,
    val currentWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal?,
    val dailyMovementLevel: DailyMovementLevel,
    val workoutFrequency: WorkoutFrequency,
    val speed: GoalChangeSpeed? = null,
)

typealias NutritionGoalCalculatorSnapshot = NutritionPlanCalculatorSnapshot

data class GoalOutcomeReadModel(
    val targetWeight: BigDecimal?,
    val targetWeightUnit: WeightUnit?,
    val targetDate: LocalDate?,
)

data class NutritionTargetsReadModel(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal?,
)

data class NutritionPlanCalculatorReadModel(
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

data class PlanScheduleReadModel(
    val type: GoalScheduleType,
    val activeFrom: LocalDate,
    val activeTo: LocalDate?,
    val weeklyCalorieBudget: BigDecimal?,
    val weekdayTargets: Map<String, Any?>,
    val dateOverrides: Map<String, Any?>,
    val macroAdjustmentMode: MacroTargetAdjustmentMode,
    val dietMode: String?,
)

data class NutritionPlanReadModel(
    val id: UUID,
    val goalType: GoalType?,
    val startDate: LocalDate,
    val timezone: String,
    val baseTargets: NutritionTargetsReadModel,
    val goalOutcome: GoalOutcomeReadModel?,
    val calculator: NutritionPlanCalculatorReadModel?,
    val planSchedule: PlanScheduleReadModel?,
    val dailyTarget: DailyTargetReadModel,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val calories: BigDecimal
        get() = baseTargets.calories

    val protein: BigDecimal
        get() = baseTargets.protein

    val carbs: BigDecimal
        get() = baseTargets.carbs

    val fat: BigDecimal
        get() = baseTargets.fat

    val fiber: BigDecimal?
        get() = baseTargets.fiber

    val targetWeight: BigDecimal?
        get() = goalOutcome?.targetWeight

    val targetWeightUnit: WeightUnit?
        get() = goalOutcome?.targetWeightUnit

    val targetDate: LocalDate?
        get() = goalOutcome?.targetDate
}

data class PlanScheduleSummaryReadModel(
    val type: GoalScheduleType,
    val date: LocalDate,
    val source: DailyTargetSource,
    val sourceDetail: String? = null,
)

data class DailyTargetReadModel(
    val planScheduleSummary: PlanScheduleSummaryReadModel,
    val targets: NutritionTargetsReadModel,
){
    val type: GoalScheduleType
        get() = planScheduleSummary.type

    val date: LocalDate
        get() = planScheduleSummary.date

    val source: DailyTargetSource
        get() = planScheduleSummary.source

    val sourceDetail: String?
        get() = planScheduleSummary.sourceDetail

    val calories: BigDecimal
        get() = targets.calories

    val protein: BigDecimal
        get() = targets.protein

    val carbs: BigDecimal
        get() = targets.carbs

    val fat: BigDecimal
        get() = targets.fat

    val fiber: BigDecimal?
        get() = targets.fiber
}

enum class DailyTargetSource {
    BASE_PLAN,
    WEEKDAY_RULE,
    DATE_OVERRIDE,

    /**
     * A non-FLAT schedule whose owner no longer holds premium_schedules:
     * the stored schedule is preserved untouched, but from the lapse date the
     * daily target collapses to the schedule's weekly average.
     */
    DEGRADED_AVERAGE,
}
