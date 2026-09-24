package com.gyro.api.goal.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.calculator.GoalPreviewInput
import com.gyro.api.goal.application.calculator.GoalPreviewResult
import com.gyro.api.goal.application.calculator.GoalPreviewService
import com.gyro.api.goal.application.calculator.ObservedGoalCalibration
import com.gyro.api.goal.application.calculator.ObservedGoalCalibrationStatus
import com.gyro.api.goal.application.calculator.ObservedGoalRecommendation
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysisService
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Service
class GoalPreviewApplicationService(
    private val goalPreviewService: GoalPreviewService,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val timeProvider: TimeProvider,
    private val observedEnergyAnalysisService: ObservedEnergyAnalysisService,
    private val entitlementGateService: EntitlementGateService,
) {
    @Transactional(readOnly = true)
    fun preview(
        userId: UUID,
        input: GoalPreviewInput,
    ): GoalPreviewResult {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        val calculationDate = timeProvider.today(ZoneId.of(timezone))
        val formula = goalPreviewService.preview(
            input = input,
            calculationDate = calculationDate,
        )
        val evidence = observedEnergyAnalysisService.analyze(userId, calculationDate.minusDays(1))
        if (!evidence.sufficient || evidence.estimatedTdee == null || evidence.confidence == null) {
            return formula.copy(
                observedCalibration = evidence.toCalibration(ObservedGoalCalibrationStatus.INSUFFICIENT_EVIDENCE),
            )
        }
        val rawTarget = evidence.estimatedTdee.add(formula.dailyEnergyDelta)
        val rawAdjustment = rawTarget.subtract(formula.targetCalories)
        if (rawAdjustment.abs() < MIN_MEANINGFUL_ADJUSTMENT ||
            (evidence.confidence == RecalibrationConfidence.LOW && rawAdjustment.signum() < 0)
        ) {
            return formula.copy(
                observedCalibration = evidence.toCalibration(ObservedGoalCalibrationStatus.NO_MEANINGFUL_DIFFERENCE),
            )
        }
        val cappedAdjustment = rawAdjustment
            .min(evidence.confidence.maxAdjustment)
            .max(evidence.confidence.maxAdjustment.negate())
        val calibratedTarget = formula.targetCalories.add(cappedAdjustment).max(MINIMUM_TARGET)
        val calibrated = goalPreviewService.preview(
            input = input,
            calculationDate = calculationDate,
            maintenanceOverride = calibratedTarget.subtract(formula.dailyEnergyDelta),
        )
        val allowed = entitlementGateService.hasFeatureAccess(userId, RECALIBRATION_FEATURE)
        return formula.copy(
            observedCalibration = evidence.toCalibration(
                status = if (allowed) ObservedGoalCalibrationStatus.AVAILABLE else ObservedGoalCalibrationStatus.LOCKED,
                recommendation = if (allowed) {
                    ObservedGoalRecommendation(
                        maintenanceCalories = calibrated.maintenanceCalories,
                        targetCalories = calibrated.targetCalories,
                        dailyEnergyDelta = calibrated.dailyEnergyDelta,
                        weeklyWeightChangeKg = calibrated.weeklyWeightChangeKg,
                        timeline = calibrated.timeline,
                        macros = calibrated.macros,
                        warnings = calibrated.warnings,
                        observationBasis = evidence.auditBasis(),
                    )
                } else {
                    null
                },
            ),
        )
    }

    private fun com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis.toCalibration(
        status: ObservedGoalCalibrationStatus,
        recommendation: ObservedGoalRecommendation? = null,
    ) = ObservedGoalCalibration(
        status = status,
        windowDays = windowDays,
        windowStart = windowStart,
        windowEnd = windowEnd,
        loggedDays = loggedDays,
        loggedDaysRequired = loggedDaysRequired,
        weighInDays = weighInDays,
        weightSpanDays = weightSpanDays,
        confidence = confidence?.name,
        recommendation = recommendation,
    )

    private companion object {
        val MIN_MEANINGFUL_ADJUSTMENT = BigDecimal(50)
        val MINIMUM_TARGET = BigDecimal(1200)
        const val RECALIBRATION_FEATURE = "goal_recalibration"
    }
}
