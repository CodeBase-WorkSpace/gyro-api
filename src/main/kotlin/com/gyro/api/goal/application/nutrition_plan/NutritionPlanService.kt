package com.gyro.api.goal.application.nutrition_plan

import com.gyro.api.common.date.DateAccessPolicy
import com.gyro.api.common.decimal.NutritionDecimal
import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.goal.application.goal_schedule.NutritionTargetsCommand
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.toSaveCommand
import com.gyro.api.goal.application.calculator.GoalChangeSpeed
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.DailyEnergyDeltaSource
import com.gyro.api.goal.domain.MacroTargetAdjustmentMode
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.goal.domain.PlanScheduleEntity
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import com.gyro.api.weight.domain.toKilograms
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@Service
class NutritionPlanService(
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val planScheduleService: PlanScheduleService,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val dateAccessPolicy: DateAccessPolicy,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
    private val observedCalculatorSnapshotValidator: ObservedCalculatorSnapshotValidator,
) {

    @Transactional
    fun savePlan(
        userId: UUID,
        command: SaveNutritionPlanCommand,
    ): NutritionPlanReadModel {
        validateGoal(command)
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        val normalized = command.copy(
            calculatorSnapshot = observedCalculatorSnapshotValidator.validate(
                userId = userId,
                timezone = timezone,
                goalType = command.goalType,
                targetWeightKg = command.targetWeightKg(),
                targetDate = command.targetDate,
                snapshot = command.calculatorSnapshot,
            ),
        ).normalized()
        planScheduleService.validateScheduleAccess(
            userId = userId,
            scheduleType = normalized.schedule.scheduleType,
        )
        dateAccessPolicy.assertWritable(userId, normalized.startDate)
        dateAccessPolicy.assertWritable(userId, normalized.schedule.activeFrom)
        normalized.schedule.activeTo?.let { dateAccessPolicy.assertWritable(userId, it) }
        normalized.schedule.dateOverrides.keys.forEach { dateAccessPolicy.assertWritable(userId, it) }
        val existingPlan = nutritionPlanRepository.findByUserIdAndStartDate(
            userId = userId,
            startDate = normalized.startDate,
        )
        val plan = existingPlan ?: NutritionPlanEntity(
            userId = userId,
            startDate = normalized.startDate,
            timezone = timezone,
            calories = normalized.calories,
            protein = normalized.protein,
            carbs = normalized.carbs,
            fat = normalized.fat,
        )
        val calculatorMode = resolveCalculatorUpdateMode(normalized, existingPlan)
        val preservedAdvancedSchedule = if (
            calculatorMode == CalculatorUpdateMode.CLEAR &&
            normalized.schedule.scheduleType == GoalScheduleType.FLAT
        ) {
            existingPlan?.id?.let { planId ->
                planScheduleService.willPreserveAdvancedSchedule(
                    userId = userId,
                    nutritionPlanId = planId,
                    requestedScheduleType = normalized.schedule.scheduleType,
                )
            } ?: false
        } else {
            false
        }
        requireCalculatorBaseForAdvancedOverlay(
            command = normalized,
            calculatorMode = calculatorMode,
            preservedAdvancedSchedule = preservedAdvancedSchedule,
        )

        plan.timezone = timezone
        plan.calories = normalized.calories
        plan.protein = normalized.protein
        plan.carbs = normalized.carbs
        plan.fat = normalized.fat
        plan.fiber = normalized.fiber
        plan.targetWeight = normalized.targetWeight
        plan.targetWeightUnit = normalized.targetWeightUnit
        plan.targetDate = normalized.targetDate
        plan.calculatorGoalType = normalized.goalType
        when (calculatorMode) {
            CalculatorUpdateMode.REPLACE -> {
                plan.applyCalculatorSnapshot(requireNotNull(normalized.calculatorSnapshot))
                plan.safetyWarningCodes = normalized.calculatorSnapshot.warningCodes.toTypedArray()
            }
            CalculatorUpdateMode.CLEAR -> {
                plan.applyCalculatorSnapshot(null)
                plan.safetyWarningCodes = null
            }
            CalculatorUpdateMode.PRESERVE -> Unit
        }
        logger.info(
            "Nutrition plan calculator provenance update userId={} startDate={} mode={}",
            userId,
            normalized.startDate,
            calculatorMode,
        )

        val savedPlan = nutritionPlanRepository.saveAndFlush(plan)
        val savedPlanId = requireNotNull(savedPlan.id) { "Nutrition plan id is required after save." }
        val schedule = planScheduleService.savePlanSchedule(
            userId = userId,
            command = normalized.schedule.toSaveCommand(
                nutritionPlanId = savedPlanId,
                formulaName = savedPlan.calculatorFormula,
                formulaVersion = savedPlan.calculatorFormulaVersion,
                baseTargets = NutritionTargetsCommand(
                    calories = savedPlan.calories,
                    protein = savedPlan.protein,
                    carbs = savedPlan.carbs,
                    fat = savedPlan.fat,
                    fiber = savedPlan.fiber,
                ),
            ),
        )

        val readModel = savedPlan.toReadModel(
            planSchedule = schedule,
            dailyTarget = planScheduleService.resolveDailyTarget(
                userId = userId,
                plan = savedPlan,
                activeOn = savedPlan.startDate,
            ),
        )
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.GOAL)
        return readModel
    }

    @Transactional(readOnly = true)
    fun findActivePlan(
        userId: UUID,
        activeOn: LocalDate,
    ): NutritionPlanReadModel? {
        val plan = nutritionPlanRepository.findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(
            userId = userId,
            startDate = activeOn,
        ) ?: return null
        val planSchedule = planScheduleService.findPlanSchedule(
            userId = userId,
            nutritionPlanId = requireNotNull(plan.id),
        )
        return plan.toReadModel(
            planSchedule = planSchedule,
            dailyTarget = planScheduleService.resolveDailyTarget(
                userId = userId,
                plan = plan,
                activeOn = activeOn,
            ),
        )
    }

    @Transactional
    fun deletePlans(userId: UUID): Int {
        val deleted = nutritionPlanRepository.deleteAllByUserId(userId)
        if (deleted > 0) {
            dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.GOAL)
        }
        return deleted
    }

    private fun validateGoal(command: SaveNutritionPlanCommand) {
        if (command.calories <= java.math.BigDecimal.ZERO) {
            throw fieldValidation("activePlan.baseTargets.calories", "POSITIVE", "calories must be greater than zero.")
        }
        if (
            command.protein < java.math.BigDecimal.ZERO ||
            command.carbs < java.math.BigDecimal.ZERO ||
            command.fat < java.math.BigDecimal.ZERO ||
            command.fiber?.let { it < java.math.BigDecimal.ZERO } == true
        ) {
            throw fieldValidation("activePlan.baseTargets", "NON_NEGATIVE", "macro targets must be zero or greater.")
        }
        if (command.targetWeight?.let { it <= java.math.BigDecimal.ZERO } == true) {
            throw fieldValidation("goal.targetWeight.value", "POSITIVE", "targetWeight must be greater than zero.")
        }
        if ((command.targetWeight == null) != (command.targetWeightUnit == null)) {
            throw fieldValidation(
                field = "goal.targetWeight",
                code = "INCOMPLETE_TARGET_WEIGHT",
                message = "targetWeight and targetWeightUnit must be provided together.",
            )
        }
        if (command.targetDate != null && command.targetDate.isBefore(command.startDate)) {
            throw fieldValidation(
                field = "goal.targetDate",
                code = "TARGET_DATE_BEFORE_START_DATE",
                message = "targetDate must be on or after startDate.",
            )
        }
        val snapshot = command.calculatorSnapshot
        if (command.calculatorUpdateMode == CalculatorUpdateMode.REPLACE && snapshot == null) {
            throw fieldValidation(
                field = "activePlan.calculator",
                code = "CALCULATOR_SNAPSHOT_REQUIRED",
                message = "calculator is required when calculatorUpdateMode is REPLACE.",
            )
        }
        if (command.calculatorUpdateMode == CalculatorUpdateMode.PRESERVE && snapshot != null) {
            throw fieldValidation(
                field = "activePlan.calculatorUpdateMode",
                code = "CALCULATOR_UPDATE_MODE_CONFLICT",
                message = "calculator must be omitted when calculatorUpdateMode is PRESERVE.",
            )
        }
        if (command.calculatorUpdateMode == CalculatorUpdateMode.CLEAR && snapshot != null) {
            throw fieldValidation(
                field = "activePlan.calculatorUpdateMode",
                code = "CALCULATOR_UPDATE_MODE_CONFLICT",
                message = "calculator must be omitted when calculatorUpdateMode is CLEAR.",
            )
        }
        if (snapshot != null && snapshot.estimatedWeeksMax < snapshot.estimatedWeeksMin) {
            throw fieldValidation(
                field = "activePlan.calculator.timeline.estimatedWeeksMax",
                code = "INVALID_TIMELINE_RANGE",
                message = "estimatedWeeksMax must be greater than or equal to estimatedWeeksMin.",
            )
        }
        if (
            snapshot != null &&
            (!snapshot.targetCalories.sameValue(command.calories) ||
                !snapshot.recommendedProtein.sameValue(command.protein) ||
                !snapshot.recommendedCarbs.sameValue(command.carbs) ||
                !snapshot.recommendedFat.sameValue(command.fat) ||
                !snapshot.recommendedFiber.sameValue(command.fiber))
        ) {
            throw fieldValidation(
                field = "activePlan.calculator",
                code = "CALCULATOR_SNAPSHOT_TARGET_MISMATCH",
                message = "calculator targets must match activePlan.baseTargets.",
            )
        }
    }

    private fun SaveNutritionPlanCommand.targetWeightKg(): BigDecimal? {
        val targetWeight = targetWeight ?: return null
        return targetWeightUnit?.toKilograms(targetWeight)
    }

    private fun resolveCalculatorUpdateMode(
        command: SaveNutritionPlanCommand,
        existingPlan: NutritionPlanEntity?,
    ): CalculatorUpdateMode {
        if (
            command.schedule.isAdvancedOverlay() &&
            existingPlan?.hasCalculatorSnapshot() != true &&
            command.calculatorUpdateMode != CalculatorUpdateMode.REPLACE &&
            command.calculatorSnapshot == null
        ) {
            throw calculatorBaseRequired()
        }

        command.calculatorUpdateMode?.let { requested ->
            if (requested == CalculatorUpdateMode.PRESERVE) {
                if (existingPlan?.hasCalculatorSnapshot() != true) {
                    throw fieldValidation(
                        field = "activePlan.calculatorUpdateMode",
                        code = "CALCULATOR_SNAPSHOT_NOT_FOUND",
                        message = "there is no calculator snapshot to preserve.",
                    )
                }
                requireCalculatorInputsUnchanged(command, existingPlan)
            }
            return requested
        }

        if (command.calculatorSnapshot != null) return CalculatorUpdateMode.REPLACE
        if (existingPlan?.hasCalculatorSnapshot() != true) return CalculatorUpdateMode.CLEAR

        return if (calculatorInputsUnchanged(command, existingPlan)) {
            CalculatorUpdateMode.PRESERVE
        } else {
            logger.warn(
                "Rejected ambiguous calculator downgrade userId={} startDate={}",
                existingPlan.userId,
                existingPlan.startDate,
            )
            throw fieldValidation(
                field = "activePlan.calculatorUpdateMode",
                code = "CALCULATOR_UPDATE_MODE_REQUIRED",
                message = "calculatorUpdateMode is required when calculator-backed plan inputs change.",
            )
        }
    }

    private fun requireCalculatorInputsUnchanged(
        command: SaveNutritionPlanCommand,
        existingPlan: NutritionPlanEntity,
    ) {
        if (!calculatorInputsUnchanged(command, existingPlan)) {
            throw fieldValidation(
                field = "activePlan.calculatorUpdateMode",
                code = "CALCULATOR_INPUTS_CHANGED",
                message = "calculator-backed plan inputs changed; use REPLACE or explicitly CLEAR the snapshot.",
            )
        }
    }

    private fun calculatorInputsUnchanged(
        command: SaveNutritionPlanCommand,
        plan: NutritionPlanEntity,
    ): Boolean =
        plan.calculatorGoalType == command.goalType &&
            plan.calories.sameValue(command.calories) &&
            plan.protein.sameValue(command.protein) &&
            plan.carbs.sameValue(command.carbs) &&
            plan.fat.sameValue(command.fat) &&
            plan.fiber.sameValue(command.fiber) &&
            plan.targetWeight.sameValue(command.targetWeight) &&
            plan.targetWeightUnit == command.targetWeightUnit &&
            plan.targetDate == command.targetDate

    private fun requireCalculatorBaseForAdvancedOverlay(
        command: SaveNutritionPlanCommand,
        calculatorMode: CalculatorUpdateMode,
        preservedAdvancedSchedule: Boolean,
    ) {
        if (calculatorMode != CalculatorUpdateMode.CLEAR) return
        if (!command.schedule.isAdvancedOverlay() && !preservedAdvancedSchedule) return

        throw calculatorBaseRequired()
    }

    private fun calculatorBaseRequired() = fieldValidation(
        field = "activePlan.schedule",
        code = "CALCULATOR_BASE_REQUIRED",
        message = "an advanced schedule requires an existing calculator-backed base plan.",
    )

    private fun SaveNutritionPlanCommand.normalized(): SaveNutritionPlanCommand {
        return copy(
            calories = NutritionDecimal.calories(calories),
            protein = NutritionDecimal.macro(protein),
            carbs = NutritionDecimal.macro(carbs),
            fat = NutritionDecimal.macro(fat),
            fiber = fiber?.let(NutritionDecimal::macro),
            targetWeight = targetWeight?.let(NutritionDecimal::weight),
            calculatorSnapshot = calculatorSnapshot?.normalized(),
        )
    }

    private fun NutritionPlanCalculatorSnapshot.normalized(): NutritionPlanCalculatorSnapshot {
        return copy(
            maintenanceCalories = NutritionDecimal.calories(maintenanceCalories),
            formulaMaintenanceCalories = NutritionDecimal.calories(formulaMaintenanceCalories),
            targetCalories = NutritionDecimal.calories(targetCalories),
            activityFactor = activityFactor.setScale(3),
            dailyEnergyDelta = dailyEnergyDelta.setScale(2),
            weeklyWeightChangeKg = weeklyWeightChangeKg.setScale(3),
            recommendedProtein = NutritionDecimal.macro(recommendedProtein),
            recommendedCarbs = NutritionDecimal.macro(recommendedCarbs),
            recommendedFat = NutritionDecimal.macro(recommendedFat),
            recommendedFiber = recommendedFiber?.let(NutritionDecimal::macro),
        )
    }

    private fun NutritionPlanEntity.applyCalculatorSnapshot(snapshot: NutritionPlanCalculatorSnapshot?) {
        calculatorFormula = snapshot?.formula
        calculatorFormulaVersion = snapshot?.formulaVersion
        maintenanceCalories = snapshot?.maintenanceCalories
        targetCalories = snapshot?.targetCalories
        activityFactor = snapshot?.activityFactor
        dailyEnergyDelta = snapshot?.dailyEnergyDelta
        dailyEnergyDeltaSource = snapshot?.let {
            when (it.maintenanceSource) {
                CalculatorMaintenanceSource.FORMULA -> DailyEnergyDeltaSource.FORMULA_WIZARD
                CalculatorMaintenanceSource.OBSERVED -> DailyEnergyDeltaSource.OBSERVED_WIZARD
            }
        }
        calculatorMaintenanceSource = snapshot?.maintenanceSource
        formulaMaintenanceCalories = snapshot?.formulaMaintenanceCalories
        calculatorObservationBasis = snapshot?.observationBasis
        weeklyWeightChangeKg = snapshot?.weeklyWeightChangeKg
        estimatedWeeksMin = snapshot?.estimatedWeeksMin
        estimatedWeeksMax = snapshot?.estimatedWeeksMax
        estimatedTargetDate = snapshot?.estimatedTargetDate
        recommendedProtein = snapshot?.recommendedProtein
        recommendedCarbs = snapshot?.recommendedCarbs
        recommendedFat = snapshot?.recommendedFat
        recommendedFiber = snapshot?.recommendedFiber
        calculatorSex = snapshot?.profile?.sex
        calculatorBirthDate = snapshot?.profile?.birthDate
        calculatorHeightCm = snapshot?.profile?.heightCm
        calculatorCurrentWeightKg = snapshot?.profile?.currentWeightKg
        calculatorTargetWeightKg = snapshot?.profile?.targetWeightKg
        calculatorDailyMovementLevel = snapshot?.profile?.dailyMovementLevel
        calculatorWorkoutFrequency = snapshot?.profile?.workoutFrequency
        calculatorChangeSpeed = snapshot?.profile?.speed?.name
    }

    private fun NutritionPlanEntity.hasCalculatorSnapshot(): Boolean =
        calculatorFormula != null &&
            calculatorFormulaVersion != null &&
            maintenanceCalories != null &&
            dailyEnergyDelta != null &&
            weeklyWeightChangeKg != null

    private fun BigDecimal?.sameValue(other: BigDecimal?): Boolean = when {
        this == null || other == null -> this == null && other == null
        else -> compareTo(other) == 0
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NutritionPlanService::class.java)
    }

}

private fun com.gyro.api.goal.application.goal_schedule.PlanScheduleDraftCommand.isAdvancedOverlay(): Boolean =
    scheduleType != GoalScheduleType.FLAT ||
        weeklyCalorieBudget != null ||
        weekdayTargets.isNotEmpty() ||
        dateOverrides.isNotEmpty() ||
        macroAdjustmentMode != MacroTargetAdjustmentMode.FIXED_GRAMS ||
        dietMode != null

private fun NutritionPlanEntity.toReadModel(
    planSchedule: PlanScheduleEntity?,
    dailyTarget: DailyTargetReadModel,
): NutritionPlanReadModel {
    return NutritionPlanReadModel(
        id = requireNotNull(id),
        goalType = calculatorGoalType,
        startDate = startDate,
        timezone = timezone,
        baseTargets = NutritionTargetsReadModel(
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber,
        ),
        goalOutcome = if (targetWeight == null && targetWeightUnit == null && targetDate == null) {
            null
        } else {
            GoalOutcomeReadModel(
                targetWeight = targetWeight,
                targetWeightUnit = targetWeightUnit,
                targetDate = targetDate,
            )
        },
        calculator = if (
            calculatorFormula == null ||
            calculatorFormulaVersion == null ||
            maintenanceCalories == null ||
            dailyEnergyDelta == null ||
            weeklyWeightChangeKg == null
        ) {
            null
        } else {
            NutritionPlanCalculatorReadModel(
                formula = calculatorFormula!!,
                formulaVersion = calculatorFormulaVersion!!,
                maintenanceCalories = maintenanceCalories!!,
                dailyEnergyDelta = dailyEnergyDelta!!,
                expectedWeeklyWeightChangeKg = weeklyWeightChangeKg!!,
                profile = if (
                    calculatorSex == null ||
                    calculatorBirthDate == null ||
                    calculatorHeightCm == null ||
                    calculatorCurrentWeightKg == null ||
                    calculatorDailyMovementLevel == null ||
                    calculatorWorkoutFrequency == null
                ) {
                    null
                } else {
                    NutritionPlanCalculatorProfile(
                        sex = calculatorSex!!,
                        birthDate = calculatorBirthDate!!,
                        heightCm = calculatorHeightCm!!,
                        currentWeightKg = calculatorCurrentWeightKg!!,
                        targetWeightKg = calculatorTargetWeightKg,
                        dailyMovementLevel = calculatorDailyMovementLevel!!,
                        workoutFrequency = calculatorWorkoutFrequency!!,
                        speed = calculatorChangeSpeed?.let { stored ->
                            runCatching { GoalChangeSpeed.valueOf(stored) }.getOrNull()
                        },
                    )
                },
                maintenanceSource = calculatorMaintenanceSource ?: CalculatorMaintenanceSource.FORMULA,
                formulaMaintenanceCalories = formulaMaintenanceCalories ?: maintenanceCalories!!,
                observationBasis = calculatorObservationBasis,
            )
        },
        planSchedule = planSchedule?.let {
            PlanScheduleReadModel(
                type = it.scheduleType,
                activeFrom = it.activeFrom,
                activeTo = it.activeTo,
                weeklyCalorieBudget = it.weeklyCalorieBudget,
                weekdayTargets = it.weekdayTargets,
                dateOverrides = it.dateOverrides,
                macroAdjustmentMode = it.macroAdjustmentMode,
                dietMode = it.dietMode,
            )
        },
        dailyTarget = dailyTarget,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun fieldValidation(
    field: String,
    code: String,
    message: String,
): FieldValidationException {
    return FieldValidationException(
        message = message,
        fieldErrors = listOf(
            ApiErrorResponse.FieldError(
                field = field,
                errorMessage = message,
                code = code,
            )
        ),
    )
}
