package com.gyro.api.goal.application

import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.observability.HealthTrackingObservability
import com.gyro.api.goal.application.nutrition_plan.SaveNutritionPlanCommand
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Service
class GoalCommandService(
    private val nutritionPlanService: NutritionPlanService,
    private val healthTrackingObservability: HealthTrackingObservability,
) {
    fun saveGoal(
        userId: UUID,
        command: SaveNutritionPlanCommand,
    ): CurrentGoalAggregateView {
        validateMacroEnergy(command)
        validateSafetyWarnings(command)

        val savedPlan = nutritionPlanService.savePlan(
            userId = userId,
            command = command,
        )

        healthTrackingObservability.goalSaved(
            userId = userId,
            scheduleType = savedPlan.planSchedule?.type?.name,
        )

        return savedPlan.toCurrentGoalAggregateView()
    }

    fun deleteGoal(userId: UUID): CurrentGoalAggregateView {
        nutritionPlanService.deletePlans(userId)
        return unconfiguredGoalView()
    }

    private fun validateMacroEnergy(command: SaveNutritionPlanCommand) {
        val macroCalories = command.protein.multiply(PROTEIN_CALORIES_PER_GRAM)
            .add(command.carbs.multiply(CARBOHYDRATE_CALORIES_PER_GRAM))
            .add(command.fat.multiply(FAT_CALORIES_PER_GRAM))
        val lowerBound = command.calories.multiply(BigDecimal.ONE.subtract(MACRO_CALORIE_TOLERANCE))
        val upperBound = command.calories.multiply(BigDecimal.ONE.add(MACRO_CALORIE_TOLERANCE))

        if (macroCalories < lowerBound || macroCalories > upperBound) {
            throw FieldValidationException(
                message = "Macro calories must be within 25 percent of total calories.",
                fieldErrors = listOf(
                    ApiErrorResponse.FieldError(
                        field = "activePlan.baseTargets",
                        errorMessage = "Macro calories must be within 25 percent of total calories.",
                        code = "MACRO_CALORIES_OUT_OF_RANGE",
                    )
                ),
            )
        }
    }

    private fun validateSafetyWarnings(command: SaveNutritionPlanCommand) {
        val snapshotWarningCodes = command.calculatorSnapshot?.warningCodes.orEmpty()
        val acceptedWarningCodes = command.acceptedWarningCodes
        val blockingWarningCodes = command.blockingWarningCodes
        val allWarningCodes = snapshotWarningCodes + acceptedWarningCodes + blockingWarningCodes

        if (allWarningCodes.any { it.isBlank() }) {
            throw FieldValidationException(
                message = "Warning codes must not be blank.",
                fieldErrors = listOf(
                    ApiErrorResponse.FieldError(
                        field = "activePlan.calculator.warningCodes",
                        errorMessage = "Warning codes must not be blank.",
                        code = "BLANK_WARNING_CODE",
                    )
                ),
            )
        }

        if (blockingWarningCodes.isNotEmpty()) {
            throw FieldValidationException(
                message = "Goal has blocking safety warnings and cannot be saved.",
                fieldErrors = listOf(
                    ApiErrorResponse.FieldError(
                        field = "blockingWarningCodes",
                        errorMessage = "Goal has blocking safety warnings and cannot be saved.",
                        code = "BLOCKING_SAFETY_WARNING",
                    )
                ),
            )
        }

        val unacceptedWarningCodes = snapshotWarningCodes - acceptedWarningCodes
        if (unacceptedWarningCodes.isNotEmpty()) {
            throw FieldValidationException(
                message = "Calculator warning codes must be accepted before saving.",
                fieldErrors = listOf(
                    ApiErrorResponse.FieldError(
                        field = "acceptedWarningCodes",
                        errorMessage = "Calculator warning codes must be accepted before saving.",
                        code = "UNACCEPTED_SAFETY_WARNING",
                    )
                ),
            )
        }
    }

    companion object {
        private val PROTEIN_CALORIES_PER_GRAM = BigDecimal("4")
        private val CARBOHYDRATE_CALORIES_PER_GRAM = BigDecimal("4")
        private val FAT_CALORIES_PER_GRAM = BigDecimal("9")
        private val MACRO_CALORIE_TOLERANCE = BigDecimal("0.25").setScale(2, RoundingMode.UNNECESSARY)
    }
}
