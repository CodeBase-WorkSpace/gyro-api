package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.CoachObservationFingerprintPolicy
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.MeasuredTdeeInsight
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.goal.application.recalibration.RecalibrationWindowPolicy
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Component

/** Builds the ranked TDEE observation from one already-computed live analysis. */
@Component
class MeasuredTdeeObservationFactory {
    internal fun create(analysis: ObservedEnergyAnalysis): DashboardInsightCandidate<MeasuredTdeeInsight>? =
        (evaluate(analysis) as? MeasuredTdeeObservationOutcome.Eligible)?.candidate

    internal fun evaluate(analysis: ObservedEnergyAnalysis): MeasuredTdeeObservationOutcome {
        if (
            !analysis.sufficient ||
            RecalibrationWindowPolicy.forDaysOrNull(analysis.windowDays) == null ||
            analysis.windowStart.isAfter(analysis.windowEnd) ||
            ChronoUnit.DAYS.between(analysis.windowStart, analysis.windowEnd) + 1L != analysis.windowDays.toLong() ||
            analysis.loggedDays < 0 ||
            analysis.weighInDays < 0 ||
            analysis.weightSpanDays < 0L
        ) return suppressed(MeasuredTdeeSuppressionReason.INVALID_ANALYSIS)

        val confidence = analysis.confidence
            ?: return suppressed(MeasuredTdeeSuppressionReason.INVALID_ANALYSIS)
        val estimatedTdee = analysis.estimatedTdee
            ?: return suppressed(MeasuredTdeeSuppressionReason.INVALID_ANALYSIS)
        if (estimatedTdee.signum() <= 0) {
            return suppressed(MeasuredTdeeSuppressionReason.ESTIMATED_TDEE_OUT_OF_RANGE)
        }
        val weightSpanDays = analysis.weightSpanDays
            .takeIf { it <= Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return suppressed(MeasuredTdeeSuppressionReason.INVALID_ANALYSIS)

        val displayedValue = runCatching {
            estimatedTdee
                .setScale(-DISPLAY_PRECISION, RoundingMode.HALF_UP)
                .intValueExact()
        }.getOrNull() ?: return suppressed(MeasuredTdeeSuppressionReason.INVALID_ANALYSIS)
        val suppressionReason = MeasuredTdeeDisplayValidityPolicy.suppressionReason(analysis, displayedValue)
        if (suppressionReason != null) return suppressed(suppressionReason)

        return MeasuredTdeeObservationOutcome.Eligible(DashboardInsightCandidate(
            insight = MeasuredTdeeInsight(
                impressionId = CoachObservationFingerprintPolicy.measuredTdee(
                    evidenceEnd = analysis.windowEnd,
                    confidence = confidence,
                    displayedValue = displayedValue,
                ),
                value = displayedValue,
                loggedDayCount = analysis.loggedDays,
                periodStart = analysis.windowStart,
                periodEnd = analysis.windowEnd,
                confidence = confidence,
                estimatorVersion = CoachObservationFingerprintPolicy.MEASURED_TDEE_ESTIMATOR_VERSION,
                displayPolicyVersion = MeasuredTdeeDisplayValidityPolicy.VERSION,
                windowDays = analysis.windowDays,
                weighInDayCount = analysis.weighInDays,
                weightSpanDays = weightSpanDays,
            ),
            magnitude = confidenceMagnitude(confidence),
        ))
    }

    private companion object {
        const val DISPLAY_PRECISION = 1

        fun suppressed(reason: MeasuredTdeeSuppressionReason) =
            MeasuredTdeeObservationOutcome.Suppressed(reason)

        fun confidenceMagnitude(
            confidence: RecalibrationConfidence,
        ): Double = when (confidence) {
            RecalibrationConfidence.LOW -> 0.60
            RecalibrationConfidence.MEDIUM -> 0.80
            RecalibrationConfidence.HIGH -> 1.00
        }
    }
}

internal sealed interface MeasuredTdeeObservationOutcome {
    data class Eligible(val candidate: DashboardInsightCandidate<MeasuredTdeeInsight>) : MeasuredTdeeObservationOutcome
    data class Suppressed(val reason: MeasuredTdeeSuppressionReason) : MeasuredTdeeObservationOutcome
}
