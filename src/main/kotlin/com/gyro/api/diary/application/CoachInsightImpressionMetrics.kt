package com.gyro.api.diary.application

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class CoachInsightImpressionMetrics(
    private val meterRegistry: MeterRegistry,
) {
    internal fun stateCandidateOutcome(outcome: MeasuredTdeeCandidateOutcome) {
        meterRegistry
            .counter(STATE_CANDIDATE_METRIC, "outcome", outcome.metricValue)
            .increment()
    }

    /**
     * The single terminal outcome of one trend-explanation evaluation. Fixed
     * cardinality and free of user data, so an eligibility-rate dashboard can
     * distinguish missing evidence from crossed regimes or immaterial signals.
     */
    internal fun stateTrendExplanationOutcome(outcome: TrendExplanationCandidateOutcome) {
        meterRegistry
            .counter(STATE_TREND_EXPLANATION_METRIC, "outcome", outcome.metricValue)
            .increment()
    }

    /**
     * The single terminal outcome of one goal-forecast evaluation. An observation is
     * still produced for every unforecastable state, so the label distinguishes those
     * states from a goal that was never eligible for a forecast at all.
     */
    internal fun stateGoalForecastOutcome(outcome: GoalForecastCandidateOutcome) {
        meterRegistry
            .counter(STATE_GOAL_FORECAST_METRIC, "outcome", outcome.metricValue)
            .increment()
    }

    fun lookupFailed() {
        failureCounter(OPERATION_LOOKUP).increment()
    }

    fun writeFailed() {
        failureCounter(OPERATION_WRITE).increment()
    }

    /**
     * Why a candidate was not offered for ranking. A low appearance rate for a
     * kind otherwise looks the same whether users lack evidence, targets failed
     * to resolve, or a window bug exists. Labels carry no user data.
     */
    internal fun stateObservationSuppressed(kind: DashboardInsightKind, reason: String) {
        meterRegistry.counter(
            STATE_SUPPRESSED_METRIC,
            "kind",
            kind.name,
            "reason",
            reason,
        ).increment()
    }

    fun stateObservationReturned(kind: DashboardInsightKind) {
        meterRegistry.counter(STATE_RETURNED_METRIC, "kind", kind.name).increment()
    }

    internal fun stateRotation(outcome: ObservationRotationOutcome) {
        meterRegistry.counter(
            STATE_ROTATION_METRIC,
            "outcome",
            outcome.name.lowercase(),
        ).increment()
    }

    fun stateTipFallback() {
        meterRegistry.counter(STATE_TIP_FALLBACK_METRIC).increment()
    }

    fun impressionAccepted(kind: DashboardInsightKind) {
        meterRegistry.counter(IMPRESSION_ACCEPTED_METRIC, "kind", kind.name).increment()
    }

    fun impressionRejected(reason: String) {
        meterRegistry.counter(IMPRESSION_REJECTED_METRIC, "reason", reason).increment()
    }

    fun impressionDuplicate() {
        meterRegistry.counter(IMPRESSION_NOOP_METRIC, "outcome", "duplicate").increment()
    }

    fun issuanceFailed(operation: String) {
        failureCounter(operation).increment()
    }

    private fun failureCounter(operation: String) =
        meterRegistry.counter(FAILURE_METRIC, "operation", operation)

    companion object {
        const val FAILURE_METRIC = "gyro.goal.coach.impressions.failures"
        const val STATE_RETURNED_METRIC = "gyro.goal.coach.state.observations.returned"
        const val STATE_ROTATION_METRIC = "gyro.goal.coach.state.observation.rotation"
        const val STATE_SUPPRESSED_METRIC = "gyro.goal.coach.state.observation.suppressed"
        const val STATE_TIP_FALLBACK_METRIC = "gyro.goal.coach.state.tip.fallback"
        const val STATE_CANDIDATE_METRIC = "gyro.goal.coach.state.observation.candidate"
        const val STATE_TREND_EXPLANATION_METRIC = "gyro.goal.coach.state.trend_explanation.candidate"
        const val STATE_GOAL_FORECAST_METRIC = "gyro.goal.coach.state.goal_forecast.candidate"
        const val IMPRESSION_ACCEPTED_METRIC = "gyro.goal.coach.impressions.accepted"
        const val IMPRESSION_REJECTED_METRIC = "gyro.goal.coach.impressions.rejected"
        const val IMPRESSION_NOOP_METRIC = "gyro.goal.coach.impressions.noop"
        const val OPERATION_LOOKUP = "lookup"
        const val OPERATION_WRITE = "write"
        const val REJECTION_NOT_ISSUED = "not_issued"
    }
}

internal enum class MeasuredTdeeCandidateOutcome(val metricValue: String) {
    ELIGIBLE("eligible"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    NOT_ENTITLED("not_entitled"),
    STATE_EXCLUDED("state_excluded"),
    INVALID_ESTIMATE("invalid_estimate"),
}

/** Terminal outcome of a trend-explanation evaluation; each value is a metric label. */
internal enum class TrendExplanationCandidateOutcome(val metricValue: String) {
    ELIGIBLE("eligible"),
    INSUFFICIENT_FOOD("insufficient_food"),
    INSUFFICIENT_WEIGHT("insufficient_weight"),
    NO_COMPATIBLE_TARGETS("no_compatible_targets"),
    TARGET_REGIME_CROSSED("target_regime_crossed"),
    INTAKE_GAP_NOT_MATERIAL("intake_gap_not_material"),
    WEIGHT_RISE_NOT_MATERIAL("weight_rise_not_material"),
    IMPLAUSIBLE_EVIDENCE("implausible_evidence"),
}

/**
 * Terminal outcome of a goal-forecast evaluation; each value is a metric label.
 *
 * The first seven mirror [GoalForecastStatus] and all produce an observation. The
 * remainder are goal-compatibility rejections that produce no observation at all.
 */
internal enum class GoalForecastCandidateOutcome(val metricValue: String) {
    AVAILABLE("available"),
    INSUFFICIENT_EVIDENCE("insufficient_evidence"),
    STALE_EVIDENCE("stale_evidence"),
    FLAT_TREND("flat_trend"),
    OPPOSITE_TREND("opposite_trend"),
    LOW_TREND_QUALITY("low_trend_quality"),
    BEYOND_HORIZON("beyond_horizon"),
    NO_ACTIVE_GOAL("no_active_goal"),
    MAINTENANCE_GOAL("maintenance_goal"),
    MISSING_TARGET("missing_target"),
    DIRECTION_CONFLICT("direction_conflict"),
    GOAL_ALREADY_REACHED("goal_already_reached"),
    NO_START_WEIGHT("no_start_weight"),
    PLANNED_CHANGE_TOO_SMALL("planned_change_too_small"),
    REMAINING_CHANGE_TOO_SMALL("remaining_change_too_small"),
    SCOPE_EXCLUDED("scope_excluded"),
}

internal fun GoalForecastStatus.candidateOutcome(): GoalForecastCandidateOutcome = when (this) {
    GoalForecastStatus.AVAILABLE -> GoalForecastCandidateOutcome.AVAILABLE
    GoalForecastStatus.INSUFFICIENT_EVIDENCE -> GoalForecastCandidateOutcome.INSUFFICIENT_EVIDENCE
    GoalForecastStatus.STALE_EVIDENCE -> GoalForecastCandidateOutcome.STALE_EVIDENCE
    GoalForecastStatus.FLAT_TREND -> GoalForecastCandidateOutcome.FLAT_TREND
    GoalForecastStatus.OPPOSITE_TREND -> GoalForecastCandidateOutcome.OPPOSITE_TREND
    GoalForecastStatus.LOW_TREND_QUALITY -> GoalForecastCandidateOutcome.LOW_TREND_QUALITY
    GoalForecastStatus.BEYOND_HORIZON -> GoalForecastCandidateOutcome.BEYOND_HORIZON
}
