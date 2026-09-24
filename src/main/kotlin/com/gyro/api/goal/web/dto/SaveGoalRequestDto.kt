package com.gyro.api.goal.web.dto

import com.gyro.api.common.error.InvalidGoalScheduleException
import com.gyro.api.goal.application.goal_schedule.NutritionTargetsCommand
import com.gyro.api.goal.application.goal_schedule.PlanScheduleDraftCommand
import com.gyro.api.goal.application.calculator.GoalChangeSpeed
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanCalculatorSnapshot
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanCalculatorProfile
import com.gyro.api.goal.application.nutrition_plan.CalculatorUpdateMode
import com.gyro.api.goal.application.nutrition_plan.SaveNutritionPlanCommand
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.domain.WorkoutFrequency
import com.gyro.api.weight.domain.WeightUnit
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

data class SaveGoalRequest(
    @field:Valid
    @field:NotNull
    val goal: SaveGoalOutcomeRequest?,

    @field:Valid
    @field:NotNull
    val activePlan: SaveActiveNutritionPlanRequest?,

    val acceptedWarningCodes: Set<String> = emptySet(),
    val blockingWarningCodes: Set<String> = emptySet(),
) {
    fun toCommand(): SaveNutritionPlanCommand {
        val goalRequest = requireNotNull(goal)
        val planRequest = requireNotNull(activePlan)
        val targetsRequest = requireNotNull(planRequest.baseTargets)
        val targetWeight = goalRequest.targetWeight

        return SaveNutritionPlanCommand(
            startDate = requireNotNull(planRequest.startDate),
            goalType = requireNotNull(goalRequest.type),
            calories = requireNotNull(targetsRequest.calories),
            protein = requireNotNull(targetsRequest.protein),
            carbs = requireNotNull(targetsRequest.carbs),
            fat = requireNotNull(targetsRequest.fat),
            fiber = targetsRequest.fiber,
            targetWeight = targetWeight?.value,
            targetWeightUnit = targetWeight?.unit,
            targetDate = goalRequest.targetDate,
            schedule = planRequest.schedule?.toCommand(planRequest.startDate) ?: PlanScheduleDraftCommand(
                scheduleType = GoalScheduleType.FLAT,
                activeFrom = requireNotNull(planRequest.startDate),
            ),
            calculatorUpdateMode = planRequest.calculatorUpdateMode,
            calculatorSnapshot = planRequest.calculator?.toCommand(targetsRequest),
            acceptedWarningCodes = acceptedWarningCodes,
            blockingWarningCodes = blockingWarningCodes,
        )
    }
}

data class SaveGoalOutcomeRequest(
    @field:NotNull
    val type: GoalType?,

    @field:Valid
    val targetWeight: SaveWeightValueRequest? = null,

    val targetDate: LocalDate? = null,
)

data class SaveWeightValueRequest(
    @field:NotNull
    @field:DecimalMin("20")
    @field:DecimalMax("500")
    val value: BigDecimal?,

    @field:NotNull
    val unit: WeightUnit?,
)

data class SaveActiveNutritionPlanRequest(
    @field:NotNull
    val startDate: LocalDate?,

    @field:Valid
    @field:NotNull
    val baseTargets: SaveNutritionTargetsRequest?,

    @field:Valid
    val schedule: SavePlanScheduleRequest? = null,

    @field:Valid
    val calculator: SaveCalculatorSnapshotRequest? = null,

    val calculatorUpdateMode: CalculatorUpdateMode? = null,
)

data class SaveNutritionTargetsRequest(
    @field:NotNull
    @field:DecimalMin("800")
    @field:DecimalMax("6000")
    val calories: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("0")
    @field:DecimalMax("400")
    val protein: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("0")
    @field:DecimalMax("800")
    val carbs: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("0")
    @field:DecimalMax("300")
    val fat: BigDecimal?,

    @field:DecimalMin("0")
    @field:DecimalMax("150")
    val fiber: BigDecimal? = null,
) {
    fun toCommand(): NutritionTargetsCommand {
        return NutritionTargetsCommand(
            calories = requireNotNull(calories),
            protein = requireNotNull(protein),
            carbs = requireNotNull(carbs),
            fat = requireNotNull(fat),
            fiber = fiber,
        )
    }
}

data class SavePlanScheduleRequest(
    @field:NotNull
    val type: GoalScheduleType? = GoalScheduleType.FLAT,

    val activeTo: LocalDate? = null,

    @field:DecimalMin("800")
    @field:DecimalMax("140000")
    val weeklyCalorieBudget: BigDecimal? = null,

    @field:Valid
    val weekdayTargets: Map<String, SaveNutritionTargetsRequest> = emptyMap(),

    @field:Valid
    val dateOverrides: Map<LocalDate, SaveNutritionTargetsRequest> = emptyMap(),

    val macroAdjustmentMode: MacroTargetAdjustmentMode = MacroTargetAdjustmentMode.FIXED_GRAMS,
    val dietMode: String? = null,
) {
    fun toCommand(startDate: LocalDate?): PlanScheduleDraftCommand {
        validateWeekdayTargetKeys()
        return PlanScheduleDraftCommand(
            scheduleType = requireNotNull(type),
            activeFrom = requireNotNull(startDate),
            activeTo = activeTo,
            weeklyCalorieBudget = weeklyCalorieBudget,
            weekdayTargets = weekdayTargets.mapValues { it.value.toCommand() },
            dateOverrides = dateOverrides.mapValues { it.value.toCommand() },
            macroAdjustmentMode = macroAdjustmentMode,
            dietMode = dietMode,
        )
    }

    private fun validateWeekdayTargetKeys() {
        weekdayTargets.keys.forEach { key ->
            runCatching { DayOfWeek.valueOf(key) }
                .getOrElse { throw InvalidGoalScheduleException("weekdayTargets contains an invalid day: $key") }
        }
    }
}

data class SaveCalculatorSnapshotRequest(
    @field:Valid
    @field:NotNull
    val formula: SaveFormulaDescriptorRequest?,

    @field:NotNull
    @field:DecimalMin("800")
    @field:DecimalMax("20000")
    val maintenanceCalories: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("800")
    @field:DecimalMax("20000")
    val targetCalories: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("1.0")
    @field:DecimalMax("3.0")
    val activityFactor: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("-10000")
    @field:DecimalMax("10000")
    val dailyEnergyDelta: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("-10")
    @field:DecimalMax("10")
    val weeklyWeightChangeKg: BigDecimal?,

    @field:Valid
    @field:NotNull
    val timeline: SaveTimelineEstimateRequest?,

    @field:Size(max = 20)
    val warningCodes: Set<String> = emptySet(),

    @field:Valid
    val profile: SaveCalculatorProfileRequest? = null,

    val maintenanceSource: CalculatorMaintenanceSource = CalculatorMaintenanceSource.FORMULA,

    @field:DecimalMin("800")
    @field:DecimalMax("20000")
    val formulaMaintenanceCalories: BigDecimal? = null,

    val observationBasis: Map<String, Any?>? = null,
) {
    fun toCommand(targets: SaveNutritionTargetsRequest): NutritionPlanCalculatorSnapshot {
        val timelineRequest = requireNotNull(timeline)
        val formulaRequest = requireNotNull(formula)
        return NutritionPlanCalculatorSnapshot(
            formula = requireNotNull(formulaRequest.name),
            formulaVersion = requireNotNull(formulaRequest.version),
            maintenanceCalories = requireNotNull(maintenanceCalories),
            targetCalories = requireNotNull(targetCalories),
            activityFactor = requireNotNull(activityFactor),
            dailyEnergyDelta = requireNotNull(dailyEnergyDelta),
            weeklyWeightChangeKg = requireNotNull(weeklyWeightChangeKg),
            estimatedWeeksMin = requireNotNull(timelineRequest.estimatedWeeksMin),
            estimatedWeeksMax = requireNotNull(timelineRequest.estimatedWeeksMax),
            estimatedTargetDate = timelineRequest.estimatedTargetDate,
            recommendedProtein = requireNotNull(targets.protein),
            recommendedCarbs = requireNotNull(targets.carbs),
            recommendedFat = requireNotNull(targets.fat),
            recommendedFiber = targets.fiber,
            warningCodes = warningCodes,
            profile = profile?.toCommand(),
            maintenanceSource = maintenanceSource,
            formulaMaintenanceCalories = formulaMaintenanceCalories ?: requireNotNull(maintenanceCalories),
            observationBasis = observationBasis,
        )
    }
}

data class SaveCalculatorProfileRequest(
    @field:NotNull
    val sex: GoalCalculatorSex?,

    @field:NotNull
    val birthDate: LocalDate?,

    @field:NotNull
    @field:DecimalMin("50")
    @field:DecimalMax("300")
    val heightCm: BigDecimal?,

    @field:NotNull
    @field:DecimalMin("20")
    @field:DecimalMax("500")
    val currentWeightKg: BigDecimal?,

    @field:DecimalMin("20")
    @field:DecimalMax("500")
    val targetWeightKg: BigDecimal? = null,

    @field:NotNull
    val dailyMovementLevel: DailyMovementLevel?,

    @field:NotNull
    val workoutFrequency: WorkoutFrequency?,

    val speed: GoalChangeSpeed = GoalChangeSpeed.BALANCED,
) {
    fun toCommand() = NutritionPlanCalculatorProfile(
        sex = requireNotNull(sex),
        birthDate = requireNotNull(birthDate),
        heightCm = requireNotNull(heightCm),
        currentWeightKg = requireNotNull(currentWeightKg),
        targetWeightKg = targetWeightKg,
        dailyMovementLevel = requireNotNull(dailyMovementLevel),
        workoutFrequency = requireNotNull(workoutFrequency),
        speed = speed,
    )
}

data class SaveFormulaDescriptorRequest(
    @field:NotNull
    @field:Size(min = 1, max = 80)
    val name: String?,

    @field:NotNull
    @field:Size(min = 1, max = 40)
    val version: String?,
)

data class SaveTimelineEstimateRequest(
    @field:NotNull
    @field:Min(0)
    @field:Max(2600)
    val estimatedWeeksMin: Int?,

    @field:NotNull
    @field:Min(0)
    @field:Max(2600)
    val estimatedWeeksMax: Int?,

    val estimatedTargetDate: LocalDate?,
)
