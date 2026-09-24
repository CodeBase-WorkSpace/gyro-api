package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.DashboardInsight
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.MeasuredTdeeCandidateOutcome
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import org.springframework.stereotype.Component

/**
 * Owns the state-aware measured-TDEE eligibility sequence.
 *
 * State and entitlement are checked before invoking [analysisProvider], which preserves
 * redaction and avoids evidence reads for excluded or non-entitled responses. Keeping
 * that ordering here means adding a Coach state cannot accidentally bypass one of the
 * numeric-observation gates inside the main state machine.
 */
@Component
class MeasuredTdeeCoachCandidateResolver(
    private val factory: MeasuredTdeeObservationFactory,
    private val impressionMetrics: CoachInsightImpressionMetrics,
) {
    internal fun resolve(
        state: NutritionCoachState?,
        allowMeasuredTdee: Boolean,
        entitlementProvider: () -> Boolean,
        analysisProvider: () -> ObservedEnergyAnalysis,
    ): DashboardInsightCandidate<DashboardInsight>? {
        if (!allowMeasuredTdee || state !in QUIET_STATES) {
            impressionMetrics.stateCandidateOutcome(MeasuredTdeeCandidateOutcome.STATE_EXCLUDED)
            return null
        }
        if (!entitlementProvider()) {
            impressionMetrics.stateCandidateOutcome(MeasuredTdeeCandidateOutcome.NOT_ENTITLED)
            return null
        }
        val analysis = analysisProvider()
        if (!analysis.sufficient) {
            impressionMetrics.stateCandidateOutcome(MeasuredTdeeCandidateOutcome.INSUFFICIENT_EVIDENCE)
            return null
        }
        return when (val outcome = factory.evaluate(analysis)) {
            is MeasuredTdeeObservationOutcome.Eligible -> {
                impressionMetrics.stateCandidateOutcome(MeasuredTdeeCandidateOutcome.ELIGIBLE)
                outcome.candidate
            }
            is MeasuredTdeeObservationOutcome.Suppressed -> {
                impressionMetrics.stateCandidateOutcome(MeasuredTdeeCandidateOutcome.INVALID_ESTIMATE)
                impressionMetrics.stateObservationSuppressed(
                    kind = DashboardInsightKind.MEASURED_TDEE,
                    reason = "${MeasuredTdeeDisplayValidityPolicy.VERSION}_${outcome.reason.name}",
                )
                null
            }
        }
    }

    private companion object {
        val QUIET_STATES = setOf(
            NutritionCoachState.LEARNING,
            NutritionCoachState.WAITING,
            NutritionCoachState.ON_TRACK,
            NutritionCoachState.NO_CHANGE_RECOMMENDED,
            NutritionCoachState.OBSERVED_PROGRESS,
            NutritionCoachState.INSIGHTS,
        )
    }
}
