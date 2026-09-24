package com.gyro.api.goal.application.nutrition_plan

import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.calculator.GoalPreviewInput
import com.gyro.api.goal.application.calculator.GoalPreviewResult
import com.gyro.api.goal.application.calculator.GoalPreviewService
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysisService
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.subscription.application.EntitlementGateService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class ObservedCalculatorSnapshotValidator(
    private val observedEnergyAnalysisService: ObservedEnergyAnalysisService,
    private val entitlementGateService: EntitlementGateService,
    private val timeProvider: TimeProvider,
    private val goalPreviewService: GoalPreviewService,
) {
    fun validate(
        userId: UUID,
        timezone: String,
        goalType: GoalType?,
        targetWeightKg: BigDecimal?,
        targetDate: LocalDate?,
        snapshot: NutritionPlanCalculatorSnapshot?,
    ): NutritionPlanCalculatorSnapshot? {
        snapshot ?: return null
        if (snapshot.maintenanceSource == CalculatorMaintenanceSource.FORMULA) {
            if (snapshot.observationBasis != null) throw invalid("FORMULA_CALCULATOR_HAS_OBSERVATION_BASIS")
            return snapshot.copy(formulaMaintenanceCalories = snapshot.maintenanceCalories)
        }
        if (!entitlementGateService.hasFeatureAccess(userId, RECALIBRATION_FEATURE)) {
            throw invalid("OBSERVED_CALIBRATION_REQUIRES_ENTITLEMENT")
        }

        val calculationDate = timeProvider.today(ZoneId.of(timezone))
        val expectedEnd = calculationDate.minusDays(1)
        val suppliedEnd = snapshot.observationBasis?.get("windowEnd")?.toString()
        if (suppliedEnd != expectedEnd.toString()) throw invalid("CALCULATOR_PREVIEW_STALE")
        val profile = snapshot.profile ?: throw invalid("OBSERVED_CALCULATOR_PROFILE_REQUIRED")
        val speed = profile.speed ?: throw invalid("OBSERVED_CALCULATOR_SPEED_REQUIRED")
        val authoritativeGoalType = goalType ?: throw invalid("OBSERVED_CALCULATOR_GOAL_TYPE_REQUIRED")
        if (!profile.targetWeightKg.sameNullableValue(targetWeightKg)) {
            throw invalid("OBSERVED_CALCULATOR_PROFILE_MISMATCH")
        }
        val input = GoalPreviewInput(
            sex = profile.sex,
            birthDate = profile.birthDate,
            heightCm = profile.heightCm,
            currentWeightKg = profile.currentWeightKg,
            targetWeightKg = profile.targetWeightKg,
            dailyMovementLevel = profile.dailyMovementLevel,
            workoutFrequency = profile.workoutFrequency,
            goalType = authoritativeGoalType,
            speed = speed,
        )
        val formula = goalPreviewService.preview(input, calculationDate)
        val evidence = observedEnergyAnalysisService.analyze(userId, expectedEnd)
        val confidence = evidence.confidence
        val estimatedTdee = evidence.estimatedTdee
        if (!evidence.sufficient || confidence == null || estimatedTdee == null) {
            throw invalid("OBSERVED_CALIBRATION_EVIDENCE_CHANGED")
        }

        val rawTarget = estimatedTdee.add(formula.dailyEnergyDelta)
        val rawAdjustment = rawTarget.subtract(formula.targetCalories)
        if (rawAdjustment.abs() < MIN_MEANINGFUL_ADJUSTMENT ||
            (confidence == RecalibrationConfidence.LOW && rawAdjustment.signum() < 0)
        ) {
            throw invalid("OBSERVED_CALIBRATION_NOT_ACTIONABLE")
        }
        val cappedAdjustment = rawAdjustment
            .min(confidence.maxAdjustment)
            .max(confidence.maxAdjustment.negate())
        val expectedTarget = formula.targetCalories.add(cappedAdjustment).max(MINIMUM_TARGET)
        val calibrated = goalPreviewService.preview(
            input = input,
            calculationDate = calculationDate,
            maintenanceOverride = expectedTarget.subtract(formula.dailyEnergyDelta),
        )
        if (!snapshot.matchesAuthoritative(formula, calibrated) ||
            targetDate != calibrated.timeline.estimatedTargetDate
        ) {
            throw invalid("OBSERVED_CALIBRATION_MISMATCH")
        }
        return snapshot.copy(
            formula = formula.formula.name,
            formulaVersion = formula.formula.version,
            maintenanceCalories = calibrated.maintenanceCalories,
            targetCalories = calibrated.targetCalories,
            activityFactor = formula.activityFactor,
            dailyEnergyDelta = formula.dailyEnergyDelta,
            weeklyWeightChangeKg = formula.weeklyWeightChangeKg,
            estimatedWeeksMin = calibrated.timeline.estimatedWeeksMin,
            estimatedWeeksMax = calibrated.timeline.estimatedWeeksMax,
            estimatedTargetDate = calibrated.timeline.estimatedTargetDate,
            recommendedProtein = calibrated.macros.proteinGrams,
            recommendedCarbs = calibrated.macros.carbsGrams,
            recommendedFat = calibrated.macros.fatGrams,
            warningCodes = calibrated.nonBlockingWarningCodes(),
            formulaMaintenanceCalories = formula.maintenanceCalories,
            observationBasis = evidence.auditBasis(),
        )
    }

    private fun NutritionPlanCalculatorSnapshot.matchesAuthoritative(
        formula: GoalPreviewResult,
        calibrated: GoalPreviewResult,
    ): Boolean =
        this.formula == formula.formula.name &&
            formulaVersion == formula.formula.version &&
            formulaMaintenanceCalories.sameValue(formula.maintenanceCalories) &&
            maintenanceCalories.sameValue(calibrated.maintenanceCalories) &&
            targetCalories.sameValue(calibrated.targetCalories) &&
            activityFactor.sameValue(formula.activityFactor) &&
            dailyEnergyDelta.sameValue(formula.dailyEnergyDelta) &&
            weeklyWeightChangeKg.sameValue(formula.weeklyWeightChangeKg) &&
            estimatedWeeksMin == calibrated.timeline.estimatedWeeksMin &&
            estimatedWeeksMax == calibrated.timeline.estimatedWeeksMax &&
            estimatedTargetDate == calibrated.timeline.estimatedTargetDate &&
            recommendedProtein.sameValue(calibrated.macros.proteinGrams) &&
            recommendedCarbs.sameValue(calibrated.macros.carbsGrams) &&
            recommendedFat.sameValue(calibrated.macros.fatGrams) &&
            warningCodes == calibrated.nonBlockingWarningCodes()

    private fun GoalPreviewResult.nonBlockingWarningCodes(): Set<String> =
        warnings.filterNot { it.blocking }.mapTo(linkedSetOf()) { it.code.name }

    private fun invalid(code: String) = FieldValidationException(
        message = "Observed calculator preview is no longer valid.",
        fieldErrors = listOf(
            ApiErrorResponse.FieldError(
                field = "activePlan.calculator",
                errorMessage = "Observed calculator preview is no longer valid.",
                code = code,
            ),
        ),
    )

    private fun BigDecimal.sameValue(other: BigDecimal): Boolean = compareTo(other) == 0

    private fun BigDecimal?.sameNullableValue(other: BigDecimal?): Boolean = when {
        this == null || other == null -> this == null && other == null
        else -> compareTo(other) == 0
    }

    private companion object {
        val MIN_MEANINGFUL_ADJUSTMENT = BigDecimal(50)
        val MINIMUM_TARGET = BigDecimal(1200)
        const val RECALIBRATION_FEATURE = "goal_recalibration"
    }
}
