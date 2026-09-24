package com.gyro.api.goal.application.coach

import com.gyro.api.common.trend.LinearTrend
import com.gyro.api.common.trend.LinearTrendFit
import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.diary.application.CoachObservationFingerprintPolicy
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.GoalForecast
import com.gyro.api.diary.application.GoalForecastCandidateOutcome
import com.gyro.api.diary.application.GoalForecastDelayBand
import com.gyro.api.diary.application.GoalForecastInsight
import com.gyro.api.diary.application.GoalForecastMilestone
import com.gyro.api.diary.application.GoalForecastMilestoneState
import com.gyro.api.diary.application.GoalForecastProgressBand
import com.gyro.api.diary.application.GoalForecastStatus
import com.gyro.api.diary.application.candidateOutcome
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Eligible candidate, or the single terminal outcome recorded for observability. */
internal sealed interface GoalForecastOutcome {
    val candidateOutcome: GoalForecastCandidateOutcome

    data class Eligible(
        val candidate: DashboardInsightCandidate<GoalForecastInsight>,
    ) : GoalForecastOutcome {
        override val candidateOutcome: GoalForecastCandidateOutcome
            get() = candidate.insight.goalForecast.status.candidateOutcome()
    }

    data class Ineligible(
        override val candidateOutcome: GoalForecastCandidateOutcome,
    ) : GoalForecastOutcome
}

/**
 * Pure policy that divides a weight goal into four quarter blocks and compares the
 * saved plan dates with the dates projected from the recent weight trend.
 *
 * Two things are deliberately separated. The milestone weights and their planned dates
 * are derived only from the saved goal, so an ordinary forecast refresh never moves
 * them and a goal edit regenerates all four. The projected dates are derived only from
 * the fitted trend, and every one of them is dropped when that trend is missing, flat,
 * opposed to the goal, stale, weak, or points past the twelve-month horizon — the four
 * blocks still render, without a fabricated date beside them.
 *
 * Nothing here reads or writes the database, and nothing here proposes a faster pace to
 * defend the saved deadline: a slower forecast is reported, not corrected.
 */
@Component
class GoalForecastObservationFactory {
    internal fun create(evidence: GoalForecastEvidence): GoalForecastOutcome {
        val daily = averageByDate(evidence.weights)
        val latestObservedKg = daily.lastOrNull()?.value
        val referenceWeightKg = latestObservedKg ?: evidence.startWeightKg
        if (hasPassed(referenceWeightKg, evidence.targetWeightKg, evidence.direction)) {
            return ineligible(GoalForecastCandidateOutcome.GOAL_ALREADY_REACHED)
        }
        if (evidence.targetWeightKg.subtract(referenceWeightKg).abs() < MIN_REMAINING_CHANGE_KG) {
            return ineligible(GoalForecastCandidateOutcome.REMAINING_CHANGE_TOO_SMALL)
        }

        val plannedMilestones = plannedMilestones(evidence)
        val evidenceStart = daily.firstOrNull()?.date
        val evidenceEnd = daily.lastOrNull()?.date
        val weightSpanDays = if (evidenceStart == null || evidenceEnd == null) {
            0
        } else {
            ChronoUnit.DAYS.between(evidenceStart, evidenceEnd).toInt()
        }

        val quality = trendQuality(evidence, daily, weightSpanDays)
        val forecast = when (quality) {
            is TrendQuality.Rejected -> unforecastable(
                evidence = evidence,
                status = quality.status,
                plannedMilestones = plannedMilestones,
                referenceWeightKg = referenceWeightKg,
                hasObservedWeight = latestObservedKg != null,
                evidenceStart = evidenceStart,
                evidenceEnd = evidenceEnd,
                weighInDayCount = daily.size,
                weightSpanDays = weightSpanDays,
            )

            is TrendQuality.Usable -> forecast(
                evidence = evidence,
                fit = quality.fit,
                plannedMilestones = plannedMilestones,
                evidenceStart = requireNotNull(evidenceStart),
                evidenceEnd = requireNotNull(evidenceEnd),
                weighInDayCount = daily.size,
                weightSpanDays = weightSpanDays,
            ) ?: return ineligible(GoalForecastCandidateOutcome.GOAL_ALREADY_REACHED)
        }

        return GoalForecastOutcome.Eligible(
            DashboardInsightCandidate(
                insight = GoalForecastInsight(
                    impressionId = CoachObservationFingerprintPolicy.goalForecast(
                        evidenceEnd = evidence.windowEnd,
                        originalTargetDate = forecast.originalTargetDate,
                        forecastTargetDate = forecast.forecastTargetDate,
                        status = forecast.status,
                        delayBand = delayBand(forecast.delayDays),
                        progressBand = progressBand(forecast.progressPercent),
                    ),
                    value = forecast.progressPercent.setScale(0, RoundingMode.HALF_UP).toInt(),
                    goalForecast = forecast,
                ),
                magnitude = magnitude(forecast),
            ),
        )
    }

    /**
     * The four blocks derived only from the saved goal. Weights interpolate the planned
     * change; dates interpolate the saved plan duration, and the final block always uses
     * the saved target date itself rather than a rounded offset.
     */
    private fun plannedMilestones(evidence: GoalForecastEvidence): List<PlannedMilestone> {
        val totalPlanDays = ChronoUnit.DAYS.between(evidence.planStart, evidence.originalTargetDate)
        val plannedChange = evidence.targetWeightKg.subtract(evidence.startWeightKg)
        return PROGRESS_STEPS.map { progressPercent ->
            val fraction = BigDecimal(progressPercent).divide(HUNDRED, FRACTION_SCALE, RoundingMode.HALF_UP)
            val plannedDate = if (progressPercent == FINAL_PROGRESS_PERCENT) {
                evidence.originalTargetDate
            } else {
                evidence.planStart.plusDays(
                    BigDecimal(totalPlanDays)
                        .multiply(fraction)
                        .setScale(0, RoundingMode.FLOOR)
                        .toLong(),
                )
            }
            PlannedMilestone(
                progressPercent = progressPercent,
                targetWeightKg = evidence.startWeightKg
                    .add(plannedChange.multiply(fraction))
                    .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP),
                plannedDate = plannedDate,
            )
        }
    }

    /**
     * Whether the recent weigh-ins support projecting a date at all. Each rejection maps
     * to exactly one reported status, and the order matters: too little evidence is not a
     * flat trend, and a near-zero trend is reported as flat rather than as the wrong
     * direction.
     */
    private fun trendQuality(
        evidence: GoalForecastEvidence,
        daily: List<TrendPoint>,
        weightSpanDays: Int,
    ): TrendQuality {
        if (daily.size < MIN_WEIGH_IN_DAYS || weightSpanDays < MIN_WEIGHT_SPAN_DAYS) {
            return TrendQuality.Rejected(GoalForecastStatus.INSUFFICIENT_EVIDENCE)
        }
        val latestDate = daily.last().date
        if (ChronoUnit.DAYS.between(latestDate, evidence.today) > MAX_EVIDENCE_AGE_DAYS) {
            return TrendQuality.Rejected(GoalForecastStatus.STALE_EVIDENCE)
        }
        val fit = LinearTrend.fitDaily(daily)
            ?: return TrendQuality.Rejected(GoalForecastStatus.INSUFFICIENT_EVIDENCE)

        val kgPerWeek = fit.slopePerDay.multiply(DAYS_PER_WEEK)
        if (kgPerWeek.abs() < MIN_TREND_KG_PER_WEEK) {
            return TrendQuality.Rejected(GoalForecastStatus.FLAT_TREND)
        }
        val trendDirection =
            if (kgPerWeek.signum() < 0) GoalForecastDirection.LOSS else GoalForecastDirection.GAIN
        if (trendDirection != evidence.direction) {
            return TrendQuality.Rejected(GoalForecastStatus.OPPOSITE_TREND)
        }
        if (kgPerWeek.abs() > MAX_TREND_KG_PER_WEEK) {
            return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        }
        val rSquared = fit.rSquared
            ?: return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        if (rSquared < MIN_R_SQUARED) {
            return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        }
        val standardError = fit.slopeStdError
            ?: return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        // A slope whose own standard error is half its size carries no usable date.
        if (standardError > fit.slopePerDay.abs().multiply(MAX_RELATIVE_STD_ERROR)) {
            return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        }
        if (isSinglePointDriven(daily, fit)) {
            return TrendQuality.Rejected(GoalForecastStatus.LOW_TREND_QUALITY)
        }
        return TrendQuality.Usable(fit)
    }

    /**
     * Whether one measurement is carrying the whole slope.
     *
     * Ordinary least squares is not a robust estimator, and `LinearTrend` says so: daily
     * weight noise is autocorrelated, so r-squared and the slope standard error both read
     * optimistically and a single mistyped or unusual weigh-in can move the slope
     * materially while still passing those two gates. A leave-one-out refit asks the
     * question they cannot: if dropping any single day reverses the direction or changes
     * the pace by more than half, the line describes that one day rather than the trend,
     * and no date derived from it should be shown.
     *
     * There is no size guard here on purpose. The caller has already required at least
     * [MIN_WEIGH_IN_DAYS] distinct dates, and the smallest accepted series is exactly
     * where one measurement carries the most weight — the last place to skip the check.
     */
    private fun isSinglePointDriven(daily: List<TrendPoint>, fit: LinearTrendFit): Boolean {
        val slope = fit.slopePerDay
        val tolerance = slope.abs().multiply(MAX_LEAVE_ONE_OUT_SLOPE_CHANGE)
        return daily.indices.any { index ->
            val withoutPoint = LinearTrend.fitDaily(daily.filterIndexed { at, _ -> at != index })
                ?: return@any true
            withoutPoint.slopePerDay.signum() != slope.signum() ||
                withoutPoint.slopePerDay.subtract(slope).abs() > tolerance
        }
    }

    /**
     * Projects every unfinished block onto the fitted line. Null when the fitted weight
     * has already passed the goal, which leaves no end date for an available forecast.
     */
    private fun forecast(
        evidence: GoalForecastEvidence,
        fit: LinearTrendFit,
        plannedMilestones: List<PlannedMilestone>,
        evidenceStart: LocalDate,
        evidenceEnd: LocalDate,
        weighInDayCount: Int,
        weightSpanDays: Int,
    ): GoalForecast? {
        val fittedWeightKg = fit.valueAt(evidenceEnd)
        if (hasPassed(fittedWeightKg, evidence.targetWeightKg, evidence.direction)) return null

        val projections = plannedMilestones.map { milestone ->
            if (hasPassed(fittedWeightKg, milestone.targetWeightKg, evidence.direction)) {
                MilestoneProjection.Reached
            } else {
                projectedDate(fit, milestone.targetWeightKg, evidenceEnd)
            }
        }
        val forecastTargetDate = (projections.last() as? MilestoneProjection.Projected)?.date
        val progressPercent = progressPercent(
            evidence = evidence,
            currentWeightKg = fittedWeightKg,
        )
        // Beyond the horizon the blocks still stand, but no date on them is honest.
        if (
            forecastTargetDate == null ||
            ChronoUnit.DAYS.between(evidenceEnd, forecastTargetDate) > MAX_HORIZON_DAYS
        ) {
            return unforecastableFrom(
                evidence = evidence,
                status = GoalForecastStatus.BEYOND_HORIZON,
                plannedMilestones = plannedMilestones,
                // Only a block the fitted weight actually passed is reached. A block whose
                // projection could not be computed is unfinished with no date, which is a
                // different thing entirely.
                reachedFlags = projections.map { it is MilestoneProjection.Reached },
                progressPercent = progressPercent,
                evidenceStart = evidenceStart,
                evidenceEnd = evidenceEnd,
                weighInDayCount = weighInDayCount,
                weightSpanDays = weightSpanDays,
            )
        }

        val nextIndex = projections.indexOfFirst { it !is MilestoneProjection.Reached }
        return GoalForecast(
            status = GoalForecastStatus.AVAILABLE,
            originalTargetDate = evidence.originalTargetDate,
            forecastTargetDate = forecastTargetDate,
            delayDays = ChronoUnit.DAYS
                .between(evidence.originalTargetDate, forecastTargetDate)
                .toInt(),
            startWeightKg = evidence.startWeightKg,
            targetWeightKg = evidence.targetWeightKg,
            fittedWeightKg = fittedWeightKg,
            observedKgPerWeek = fit.slopePerDay
                .multiply(DAYS_PER_WEEK)
                .setScale(TREND_SCALE, RoundingMode.HALF_UP),
            progressPercent = progressPercent,
            evidenceStart = evidenceStart,
            evidenceEnd = evidenceEnd,
            weighInDayCount = weighInDayCount,
            weightSpanDays = weightSpanDays,
            milestones = plannedMilestones.mapIndexed { index, milestone ->
                milestone.toMilestone(
                    forecastDate = (projections[index] as? MilestoneProjection.Projected)?.date,
                    state = milestoneState(
                        index = index,
                        reached = projections[index] is MilestoneProjection.Reached,
                        nextIndex = nextIndex,
                    ),
                )
            },
        )
    }

    private fun unforecastable(
        evidence: GoalForecastEvidence,
        status: GoalForecastStatus,
        plannedMilestones: List<PlannedMilestone>,
        referenceWeightKg: BigDecimal,
        hasObservedWeight: Boolean,
        evidenceStart: LocalDate?,
        evidenceEnd: LocalDate?,
        weighInDayCount: Int,
        weightSpanDays: Int,
    ): GoalForecast = unforecastableFrom(
        evidence = evidence,
        status = status,
        plannedMilestones = plannedMilestones,
        // Without any weigh-in, no block can be called reached; the plan simply stands.
        reachedFlags = plannedMilestones.map { milestone ->
            hasObservedWeight &&
                hasPassed(referenceWeightKg, milestone.targetWeightKg, evidence.direction)
        },
        progressPercent = if (hasObservedWeight) {
            progressPercent(evidence, referenceWeightKg)
        } else {
            BigDecimal.ZERO.setScale(PERCENT_SCALE)
        },
        evidenceStart = evidenceStart,
        evidenceEnd = evidenceEnd,
        weighInDayCount = weighInDayCount,
        weightSpanDays = weightSpanDays,
    )

    private fun unforecastableFrom(
        evidence: GoalForecastEvidence,
        status: GoalForecastStatus,
        plannedMilestones: List<PlannedMilestone>,
        reachedFlags: List<Boolean>,
        progressPercent: BigDecimal,
        evidenceStart: LocalDate?,
        evidenceEnd: LocalDate?,
        weighInDayCount: Int,
        weightSpanDays: Int,
    ): GoalForecast {
        val nextIndex = reachedFlags.indexOfFirst { !it }
        return GoalForecast(
            status = status,
            originalTargetDate = evidence.originalTargetDate,
            forecastTargetDate = null,
            delayDays = null,
            startWeightKg = evidence.startWeightKg,
            targetWeightKg = evidence.targetWeightKg,
            fittedWeightKg = null,
            observedKgPerWeek = null,
            progressPercent = progressPercent,
            evidenceStart = evidenceStart,
            evidenceEnd = evidenceEnd,
            weighInDayCount = weighInDayCount,
            weightSpanDays = weightSpanDays,
            milestones = plannedMilestones.mapIndexed { index, milestone ->
                milestone.toMilestone(
                    forecastDate = null,
                    state = milestoneState(index, reachedFlags[index], nextIndex),
                )
            },
        )
    }

    /**
     * The date the fitted line reaches [milestoneWeightKg], rounded up to the next whole
     * local date and never on or before the last day of evidence.
     */
    private fun projectedDate(
        fit: LinearTrendFit,
        milestoneWeightKg: BigDecimal,
        evidenceEnd: LocalDate,
    ): MilestoneProjection {
        if (fit.slopePerDay.signum() == 0) return MilestoneProjection.Unavailable
        val daysFromAnchor = milestoneWeightKg
            .subtract(fit.intercept)
            .divide(fit.slopePerDay, PROJECTION_SCALE, RoundingMode.HALF_UP)
        if (daysFromAnchor.abs() > MAX_PROJECTION_DAYS) return MilestoneProjection.Unavailable
        val projected = fit.anchorDate.plusDays(
            daysFromAnchor.setScale(0, RoundingMode.CEILING).toLong(),
        )
        return MilestoneProjection.Projected(maxOf(projected, evidenceEnd.plusDays(1)))
    }

    private fun progressPercent(
        evidence: GoalForecastEvidence,
        currentWeightKg: BigDecimal,
    ): BigDecimal {
        val plannedChange = evidence.targetWeightKg.subtract(evidence.startWeightKg)
        if (plannedChange.signum() == 0) return BigDecimal.ZERO.setScale(PERCENT_SCALE)
        return currentWeightKg.subtract(evidence.startWeightKg)
            .multiply(HUNDRED)
            .divide(plannedChange, PERCENT_SCALE, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO.setScale(PERCENT_SCALE), HUNDRED.setScale(PERCENT_SCALE))
    }

    private data class PlannedMilestone(
        val progressPercent: Int,
        val targetWeightKg: BigDecimal,
        val plannedDate: LocalDate,
    ) {
        fun toMilestone(forecastDate: LocalDate?, state: GoalForecastMilestoneState) =
            GoalForecastMilestone(
                progressPercent = progressPercent,
                targetWeightKg = targetWeightKg,
                plannedDate = plannedDate,
                forecastDate = forecastDate,
                state = state,
            )
    }

    /**
     * What the fitted line says about one block. [Reached] and [Unavailable] both carry
     * no date, but only [Reached] means the person has passed that weight — keeping them
     * as one nullable date once let an uncomputable projection read as an achievement.
     */
    private sealed interface MilestoneProjection {
        data object Reached : MilestoneProjection
        data class Projected(val date: LocalDate) : MilestoneProjection
        data object Unavailable : MilestoneProjection
    }

    private sealed interface TrendQuality {
        data class Usable(val fit: LinearTrendFit) : TrendQuality
        data class Rejected(val status: GoalForecastStatus) : TrendQuality
    }

    private companion object {
        val PROGRESS_STEPS = listOf(25, 50, 75, 100)
        const val FINAL_PROGRESS_PERCENT = 100
        const val MIN_WEIGH_IN_DAYS = 5
        const val MIN_WEIGHT_SPAN_DAYS = 14
        const val MAX_EVIDENCE_AGE_DAYS = 7L
        const val MAX_HORIZON_DAYS = 365L
        const val WEIGHT_SCALE = 3
        const val PERCENT_SCALE = 2
        const val TREND_SCALE = 3
        const val FRACTION_SCALE = 6
        const val PROJECTION_SCALE = 6
        val HUNDRED: BigDecimal = BigDecimal(100)
        val DAYS_PER_WEEK: BigDecimal = BigDecimal(7)
        val MIN_REMAINING_CHANGE_KG: BigDecimal = BigDecimal("0.5")
        val MIN_TREND_KG_PER_WEEK: BigDecimal = BigDecimal("0.05")
        val MAX_TREND_KG_PER_WEEK: BigDecimal = BigDecimal("1.50")
        val MIN_R_SQUARED: BigDecimal = BigDecimal("0.35")
        val MAX_RELATIVE_STD_ERROR: BigDecimal = BigDecimal("0.5")

        /** How far a leave-one-out refit may move the slope before the fit is one point's. */
        val MAX_LEAVE_ONE_OUT_SLOPE_CHANGE: BigDecimal = BigDecimal("0.5")

        /** Guards the date arithmetic long before `LocalDate` could overflow. */
        val MAX_PROJECTION_DAYS: BigDecimal = BigDecimal(400_000)

        fun ineligible(outcome: GoalForecastCandidateOutcome) = GoalForecastOutcome.Ineligible(outcome)

        fun hasPassed(
            weightKg: BigDecimal,
            milestoneKg: BigDecimal,
            direction: GoalForecastDirection,
        ): Boolean = when (direction) {
            GoalForecastDirection.LOSS -> weightKg <= milestoneKg
            GoalForecastDirection.GAIN -> weightKg >= milestoneKg
        }

        fun milestoneState(
            index: Int,
            reached: Boolean,
            nextIndex: Int,
        ): GoalForecastMilestoneState = when {
            reached -> GoalForecastMilestoneState.REACHED
            index == nextIndex -> GoalForecastMilestoneState.NEXT
            else -> GoalForecastMilestoneState.UPCOMING
        }

        fun delayBand(delayDays: Int?): GoalForecastDelayBand = when {
            delayDays == null -> GoalForecastDelayBand.NONE
            delayDays < -30 -> GoalForecastDelayBand.EARLY_30_PLUS
            delayDays < -7 -> GoalForecastDelayBand.EARLY_8_30
            delayDays <= 7 -> GoalForecastDelayBand.ON_PLAN
            delayDays <= 30 -> GoalForecastDelayBand.LATE_8_30
            delayDays <= 60 -> GoalForecastDelayBand.LATE_31_60
            else -> GoalForecastDelayBand.LATE_60_PLUS
        }

        fun progressBand(progressPercent: BigDecimal): GoalForecastProgressBand = when {
            progressPercent < BigDecimal(25) -> GoalForecastProgressBand.P0_25
            progressPercent < BigDecimal(50) -> GoalForecastProgressBand.P25_50
            progressPercent < BigDecimal(75) -> GoalForecastProgressBand.P50_75
            else -> GoalForecastProgressBand.P75_100
        }

        /**
         * An unavailable forecast is the least salient case. An available one grows more
         * salient the further it has moved from the saved deadline, in either direction.
         */
        fun magnitude(forecast: GoalForecast): Double {
            val delayDays = forecast.delayDays ?: return 0.35
            return (0.5 + kotlin.math.abs(delayDays).coerceAtMost(60) / 120.0).coerceAtMost(1.0)
        }

        /** One observation per date, ordered by date, matching the trend fit's own averaging. */
        fun averageByDate(points: List<TrendPoint>): List<TrendPoint> =
            points
                .groupBy { it.date }
                .toSortedMap()
                .map { (date, sameDay) ->
                    TrendPoint(
                        date = date,
                        value = sameDay
                            .fold(BigDecimal.ZERO) { total, point -> total.add(point.value) }
                            .divide(BigDecimal(sameDay.size), WEIGHT_SCALE, RoundingMode.HALF_UP),
                    )
                }
    }
}
