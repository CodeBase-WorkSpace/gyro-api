package com.gyro.api.diary.application

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One completed day that carries both recorded intake and the target active on that date. */
internal data class WeekendGapDayEvidence(
    val date: LocalDate,
    val intakeCalories: BigDecimal,
    val targetCalories: BigDecimal,
)

internal enum class WeekendGapDirection { HIGHER, LOWER }

internal enum class WeekendGapBand { MODERATE, LARGE, VERY_LARGE }

/**
 * Why no candidate was produced. These are metric label values, so the set is
 * deliberately small, stable, and free of user data.
 */
internal enum class WeekendGapSuppressionReason {
    INVALID_PERIOD,
    DUPLICATE_DATES,
    INSUFFICIENT_WEEKEND_DAYS,
    INSUFFICIENT_WEEKDAY_DAYS,
    MISSING_WEEKEND_DAY_NAME,
    INSUFFICIENT_WEEK_SPREAD,
    INVALID_TARGET_SUM,
    BELOW_MATERIALITY_THRESHOLD,
}

internal sealed interface WeekendGapOutcome {
    data class Eligible(val candidate: DashboardInsightCandidate<WeekendGapInsight>) : WeekendGapOutcome
    data class Suppressed(val reason: WeekendGapSuppressionReason) : WeekendGapOutcome
}

/**
 * Pure policy for the Iranian weekend intake observation.
 *
 * Thursday and Friday are compared against Saturday through Wednesday using a
 * ratio of sums against the target that was actually active on each date, so a
 * schedule with different weekday targets cannot fabricate a gap out of raw
 * calories alone. Repository access deliberately stays outside this class.
 *
 * The policy still builds the dashboard candidate directly, so the calculation
 * and the transport/ranking model evolve together. That coupling is accepted
 * while there is exactly one consumer and one fingerprint version; a second
 * surface or a `V2` policy is the point at which to split a raw result type out
 * of this class and map it to `DashboardInsightCandidate` separately.
 */
@Component
class WeekendGapObservationFactory {
    internal fun create(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        evidence: List<WeekendGapDayEvidence>,
    ): WeekendGapOutcome {
        if (periodStart.isAfter(periodEnd)) return suppressed(WeekendGapSuppressionReason.INVALID_PERIOD)
        if (ChronoUnit.DAYS.between(periodStart, periodEnd) + 1L != WINDOW_DAYS.toLong()) {
            return suppressed(WeekendGapSuppressionReason.INVALID_PERIOD)
        }
        // One date must describe one day. Silently keeping whichever duplicate
        // came first would let list order decide eligibility, direction, band,
        // and the displayed value.
        if (evidence.distinctBy(WeekendGapDayEvidence::date).size != evidence.size) {
            return suppressed(WeekendGapSuppressionReason.DUPLICATE_DATES)
        }

        val eligible = evidence.filter { day ->
            !day.date.isBefore(periodStart) &&
                !day.date.isAfter(periodEnd) &&
                day.targetCalories.signum() > 0 &&
                day.intakeCalories.signum() >= 0
        }
        val weekend = eligible.filter { it.date.dayOfWeek in WEEKEND_DAYS }
        val weekday = eligible.filter { it.date.dayOfWeek !in WEEKEND_DAYS }

        if (weekend.size < MIN_WEEKEND_DAYS) {
            return suppressed(WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS)
        }
        if (weekday.size < MIN_WEEKDAY_DAYS) {
            return suppressed(WeekendGapSuppressionReason.INSUFFICIENT_WEEKDAY_DAYS)
        }
        if (WEEKEND_DAYS.any { day -> weekend.none { it.date.dayOfWeek == day } }) {
            return suppressed(WeekendGapSuppressionReason.MISSING_WEEKEND_DAY_NAME)
        }
        // A recurring pattern claim needs more than a single unusual week.
        if (eligible.mapTo(mutableSetOf(), ::saturdayWeekIndex).size < MIN_WEEKS) {
            return suppressed(WeekendGapSuppressionReason.INSUFFICIENT_WEEK_SPREAD)
        }

        val weekendDelta = targetRelativeDeltaPercent(weekend)
            ?: return suppressed(WeekendGapSuppressionReason.INVALID_TARGET_SUM)
        val weekdayDelta = targetRelativeDeltaPercent(weekday)
            ?: return suppressed(WeekendGapSuppressionReason.INVALID_TARGET_SUM)
        val gap = weekendDelta.subtract(weekdayDelta).setScale(SCALE, RoundingMode.HALF_UP)
        if (gap.abs() < MIN_GAP_PERCENTAGE_POINTS) {
            return suppressed(WeekendGapSuppressionReason.BELOW_MATERIALITY_THRESHOLD)
        }

        val direction = if (gap.signum() > 0) WeekendGapDirection.HIGHER else WeekendGapDirection.LOWER
        return WeekendGapOutcome.Eligible(
            DashboardInsightCandidate(
                insight = WeekendGapInsight(
                    impressionId = CoachObservationFingerprintPolicy.weekendGap(
                        evidenceEnd = periodEnd,
                        direction = direction,
                        band = bandOf(gap.abs()),
                    ),
                    value = gap.setScale(0, RoundingMode.HALF_UP).toInt(),
                    weekendTargetDeltaPercent = weekendDelta,
                    weekdayTargetDeltaPercent = weekdayDelta,
                    weekendLoggedDayCount = weekend.size,
                    weekdayLoggedDayCount = weekday.size,
                    loggedDayCount = weekend.size + weekday.size,
                    windowDays = WINDOW_DAYS,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                ),
                // Salience uses the unrounded gap so values either side of a display
                // boundary still rank deterministically.
                magnitude = (gap.abs().toDouble() / MAGNITUDE_DIVISOR).coerceIn(0.35, 1.0),
            )
        )
    }

    private companion object {
        fun suppressed(reason: WeekendGapSuppressionReason) = WeekendGapOutcome.Suppressed(reason)

        const val WINDOW_DAYS = 14
        const val MIN_WEEKEND_DAYS = 3
        const val MIN_WEEKDAY_DAYS = 6
        const val MIN_WEEKS = 2
        const val SCALE = 6
        const val MAGNITUDE_DIVISOR = 30.0
        val MIN_GAP_PERCENTAGE_POINTS: BigDecimal = BigDecimal("10.000000")
        val WEEKEND_DAYS = listOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun targetRelativeDeltaPercent(days: List<WeekendGapDayEvidence>): BigDecimal? {
            val intake = days.fold(BigDecimal.ZERO) { total, day -> total.add(day.intakeCalories) }
            val target = days.fold(BigDecimal.ZERO) { total, day -> total.add(day.targetCalories) }
            if (target.signum() <= 0) return null
            return intake.subtract(target)
                .multiply(BigDecimal(100))
                .divide(target, SCALE, RoundingMode.HALF_UP)
        }

        fun bandOf(absoluteGap: BigDecimal): WeekendGapBand = when {
            absoluteGap < BigDecimal("20") -> WeekendGapBand.MODERATE
            absoluteGap < BigDecimal("35") -> WeekendGapBand.LARGE
            else -> WeekendGapBand.VERY_LARGE
        }

        /**
         * Absolute index of the Saturday-started week containing [date]. 1970-01-03
         * (epoch day 2) was a Saturday.
         */
        fun saturdayWeekIndex(day: WeekendGapDayEvidence): Long =
            Math.floorDiv(day.date.toEpochDay() - 2L, 7L)
    }
}
