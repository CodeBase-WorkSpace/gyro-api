package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.CoachObservationFingerprintPolicy
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.TrendExplanationInsight
import com.gyro.api.diary.application.TrendExplanationCandidateOutcome
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTarget
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.ObservedEnergyWindowEvidence
import com.gyro.api.goal.application.recalibration.RecalibrationWindowPolicy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Eligible candidate, or the single terminal outcome recorded for observability. */
internal sealed interface TrendExplanationOutcome {
    val candidateOutcome: TrendExplanationCandidateOutcome

    data class Eligible(val candidate: DashboardInsightCandidate<TrendExplanationInsight>) : TrendExplanationOutcome {
        override val candidateOutcome: TrendExplanationCandidateOutcome
            get() = TrendExplanationCandidateOutcome.ELIGIBLE
    }

    data class Ineligible(
        override val candidateOutcome: TrendExplanationCandidateOutcome,
    ) : TrendExplanationOutcome
}

/**
 * Builds the descriptive trend-explanation observation from the ordered observed-energy
 * windows and the historical target that was active on each logged date.
 *
 * The observation describes two recorded facts — intake below historical targets while
 * the fitted short-term weight trend rose — without diagnosing a cause, invalidating the
 * records, or recommending a calorie change. Compatibility is decided against the actual
 * historical targets and their plan/regime, never against a calculator formula: intake is
 * compared with each date's own schedule-aware target, so ordinary Advanced daily
 * variation stays valid while a plan or regime boundary suppresses the observation.
 */
@Component
class TrendExplanationObservationFactory {
    /**
     * Evaluates the 14/21/28-day windows in order and returns the first eligible
     * candidate. A larger window is never preferred merely because its mismatch looks
     * stronger. When none is eligible, the furthest-progressed rejection is reported so
     * the metric distinguishes missing evidence from a crossed regime or immaterial signal.
     */
    internal fun create(
        candidates: List<ObservedEnergyWindowEvidence>,
        targetsByDate: Map<LocalDate, ScheduleAwareDailyTarget>,
    ): TrendExplanationOutcome {
        val evaluations = candidates.map { evaluateWindow(it, targetsByDate) }
        evaluations.firstNotNullOfOrNull { it as? TrendExplanationOutcome.Eligible }?.let { return it }
        val furthest = evaluations
            .filterIsInstance<TrendExplanationOutcome.Ineligible>()
            .maxByOrNull { progress(it.candidateOutcome) }
        return furthest ?: TrendExplanationOutcome.Ineligible(TrendExplanationCandidateOutcome.INSUFFICIENT_FOOD)
    }

    private fun evaluateWindow(
        evidence: ObservedEnergyWindowEvidence,
        targetsByDate: Map<LocalDate, ScheduleAwareDailyTarget>,
    ): TrendExplanationOutcome {
        val analysis = evidence.analysis
        if (
            RecalibrationWindowPolicy.forDaysOrNull(analysis.windowDays) == null ||
            analysis.windowStart.isAfter(analysis.windowEnd) ||
            ChronoUnit.DAYS.between(analysis.windowStart, analysis.windowEnd) + 1L != analysis.windowDays.toLong()
        ) {
            return ineligible(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE)
        }
        when (analysis.status) {
            ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD ->
                return ineligible(TrendExplanationCandidateOutcome.INSUFFICIENT_FOOD)
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_DAYS,
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_SPAN,
            -> return ineligible(TrendExplanationCandidateOutcome.INSUFFICIENT_WEIGHT)
            ObservedEnergyEvidenceStatus.SUFFICIENT -> Unit
        }

        // Sufficiency already guarantees >= 3 weigh-in days spanning >= 7 days and a fit
        // with quality metadata; a missing standard error means an unusable fit.
        val observedKgPerWeek = analysis.observedKgPerWeek
        if (
            analysis.trendStdErrorKgPerDay == null ||
            observedKgPerWeek == null ||
            !WeightTrendPlausibilityPolicy.hasPlausibleTrend(analysis)
        ) {
            return ineligible(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE)
        }
        // Flat, downward, or below the materiality floor. The upper bound is a
        // plausibility concern handled above.
        if (observedKgPerWeek < MIN_WEIGHT_RISE_KG_PER_WEEK) {
            return ineligible(TrendExplanationCandidateOutcome.WEIGHT_RISE_NOT_MATERIAL)
        }

        val comparison = evidence.intakeByDate.entries.map { (date, calories) -> date to calories }
        if (comparison.isEmpty()) {
            return ineligible(TrendExplanationCandidateOutcome.INSUFFICIENT_FOOD)
        }
        // Every logged day in the comparison needs a positive schedule-aware target. A
        // pre-plan date resolves to no target and lands here rather than being compared
        // against a later goal it never had.
        val targeted = comparison.map { (date, calories) ->
            val target = targetsByDate[date]?.target?.calories
            if (target == null || target.signum() <= 0) {
                return ineligible(TrendExplanationCandidateOutcome.NO_COMPATIBLE_TARGETS)
            }
            TargetedDay(date, calories, target, requireNotNull(targetsByDate[date]))
        }
        // The whole window must belong to one plan and one target regime; a plan start or
        // a material regime change inside the window is a boundary, not a mismatch.
        if (
            targeted.mapTo(mutableSetOf()) { it.schedule.planId }.size > 1 ||
            targeted.mapTo(mutableSetOf()) { it.schedule.goalType }.size > 1
        ) {
            return ineligible(TrendExplanationCandidateOutcome.TARGET_REGIME_CROSSED)
        }

        val sumRecorded = targeted.fold(BigDecimal.ZERO) { total, day -> total.add(day.calories) }
        val sumTarget = targeted.fold(BigDecimal.ZERO) { total, day -> total.add(day.target) }
        // Ratio of sums, not an average of daily percentages, so Advanced schedules with
        // different weekday targets stay valid.
        val deltaPercent = sumRecorded.subtract(sumTarget)
            .multiply(HUNDRED)
            .divide(sumTarget, SCALE, RoundingMode.HALF_UP)
        val belowPercent = deltaPercent.negate()
        if (belowPercent < MIN_BELOW_PERCENT) {
            return ineligible(TrendExplanationCandidateOutcome.INTAKE_GAP_NOT_MATERIAL)
        }
        // Intake more than 40% below target reads as incomplete logging, not adherence.
        if (belowPercent > MAX_BELOW_PERCENT) {
            return ineligible(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE)
        }

        val loggedDayCount = targeted.size
        val weightSpanDays = analysis.weightSpanDays
            .takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return ineligible(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE)
        val confidence = analysis.confidence
            ?: return ineligible(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE)

        return TrendExplanationOutcome.Eligible(
            DashboardInsightCandidate(
                insight = TrendExplanationInsight(
                    impressionId = CoachObservationFingerprintPolicy.trendExplanation(
                        evidenceEnd = analysis.windowEnd,
                        weightBand = CoachObservationFingerprintPolicy.trendExplanationWeightBand(observedKgPerWeek),
                        intakeBand = CoachObservationFingerprintPolicy.trendExplanationIntakeBand(belowPercent),
                    ),
                    value = deltaPercent.setScale(0, RoundingMode.HALF_UP).toInt(),
                    deltaPercent = deltaPercent,
                    weightTrendKgPerWeek = observedKgPerWeek,
                    averageIntakeCalories = sumRecorded
                        .divide(BigDecimal(loggedDayCount), 0, RoundingMode.HALF_UP)
                        .toInt(),
                    averageTargetCalories = sumTarget
                        .divide(BigDecimal(loggedDayCount), 0, RoundingMode.HALF_UP)
                        .toInt(),
                    loggedDayCount = loggedDayCount,
                    weighInDayCount = analysis.weighInDays,
                    weightSpanDays = weightSpanDays,
                    windowDays = analysis.windowDays,
                    periodStart = analysis.windowStart,
                    periodEnd = analysis.windowEnd,
                    confidence = confidence,
                ),
                magnitude = magnitude(observedKgPerWeek, belowPercent),
            ),
        )
    }

    private data class TargetedDay(
        val date: LocalDate,
        val calories: BigDecimal,
        val target: BigDecimal,
        val schedule: ScheduleAwareDailyTarget,
    )

    private companion object {
        val MIN_WEIGHT_RISE_KG_PER_WEEK: BigDecimal = BigDecimal("0.20")
        val MIN_BELOW_PERCENT: BigDecimal = BigDecimal("10")
        val MAX_BELOW_PERCENT: BigDecimal = BigDecimal("40")
        val HUNDRED: BigDecimal = BigDecimal(100)
        const val SCALE = 6
        val MAX_WEIGHT_RISE_KG_PER_WEEK: BigDecimal = WeightTrendPlausibilityPolicy.MAX_ABSOLUTE_TREND_KG_PER_WEEK

        fun ineligible(outcome: TrendExplanationCandidateOutcome) = TrendExplanationOutcome.Ineligible(outcome)

        /**
         * How far a rejection progressed through the gate sequence. When no window is
         * eligible, the furthest-progressed reason is the most informative one to record.
         */
        fun progress(outcome: TrendExplanationCandidateOutcome): Int = when (outcome) {
            TrendExplanationCandidateOutcome.INSUFFICIENT_FOOD -> 0
            TrendExplanationCandidateOutcome.INSUFFICIENT_WEIGHT -> 1
            TrendExplanationCandidateOutcome.WEIGHT_RISE_NOT_MATERIAL -> 2
            TrendExplanationCandidateOutcome.NO_COMPATIBLE_TARGETS -> 3
            TrendExplanationCandidateOutcome.TARGET_REGIME_CROSSED -> 4
            TrendExplanationCandidateOutcome.INTAKE_GAP_NOT_MATERIAL -> 5
            TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE -> 6
            TrendExplanationCandidateOutcome.ELIGIBLE -> 7
        }

        /** The average of the capped weight-rise and intake-gap magnitudes, in [0, 1]. */
        fun magnitude(observedKgPerWeek: BigDecimal, belowPercent: BigDecimal): Double {
            val weightRise = (observedKgPerWeek.toDouble() / MAX_WEIGHT_RISE_KG_PER_WEEK.toDouble())
                .coerceIn(0.0, 1.0)
            val intakeGap = (belowPercent.toDouble() / MAX_BELOW_PERCENT.toDouble())
                .coerceIn(0.0, 1.0)
            return (weightRise + intakeGap) / 2.0
        }
    }
}
