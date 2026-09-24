package com.gyro.api.common.trend

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TrendPoint(
    val date: LocalDate,
    val value: BigDecimal,
)

/**
 * A least-squares line fitted to one averaged observation per calendar day.
 *
 * This is a date-aware estimate of *linear change over the fitted window*. It is not
 * a robust estimator: daily weight noise is autocorrelated (hydration, sodium and
 * glycogen swings persist over 1-3 days), so [rSquared] and [slopeStdError] read
 * optimistically, and a single extreme measurement can move [slopePerDay] materially.
 * Callers must not treat those two fields as evidence quality without saying so.
 */
data class LinearTrendFit(
    /** Change in value per calendar day. */
    val slopePerDay: BigDecimal,
    /** Fitted value at [anchorDate]. */
    val intercept: BigDecimal,
    /** Earliest observed date. x is measured in days after this date. */
    val anchorDate: LocalDate,
    /**
     * Coefficient of determination, or null where it carries no information:
     * with two observations a line always fits perfectly, and with zero variance
     * in the values the total sum of squares is zero and r-squared is undefined.
     * Never reported as 1 for either case.
     */
    val rSquared: BigDecimal?,
    /** Standard error of [slopePerDay], or null below three observations (no residual dof). */
    val slopeStdError: BigDecimal?,
    /** Distinct calendar days observed, after same-day measurements were averaged. */
    val observedDayCount: Int,
    /** Calendar-day offset between the first and last observation. July 1 to July 8 is 7, not 8. */
    val spanDays: Long,
) {
    /** The fitted value on [date], extrapolating outside the observed window. */
    fun valueAt(date: LocalDate): BigDecimal =
        intercept
            .add(slopePerDay.multiply(BigDecimal(ChronoUnit.DAYS.between(anchorDate, date))))
            .setScale(VALUE_SCALE, RoundingMode.HALF_UP)

    private companion object {
        const val VALUE_SCALE = 3
    }
}

/**
 * Ordinary least squares of value on days-since-first-observation.
 *
 * There is deliberately one entry point. Both callers need daily semantics, so
 * same-day measurements are averaged internally and there is no way to ask for a fit
 * that treats them as independent observations — otherwise [LinearTrendFit.observedDayCount]
 * and the degrees-of-freedom arithmetic behind [LinearTrendFit.slopeStdError] could
 * disagree about what counts as an observation.
 */
object LinearTrend {
    private const val SLOPE_SCALE = 6
    private const val R_SQUARED_SCALE = 4
    private const val DAILY_MEAN_SCALE = 6

    /** Null when no line is defined: fewer than two distinct dates. */
    fun fitDaily(points: List<TrendPoint>): LinearTrendFit? {
        val daily = averageByDate(points)
        if (daily.size < 2) return null

        val anchorDate = daily.first().date
        val spanDays = ChronoUnit.DAYS.between(anchorDate, daily.last().date)
        val flat = daily.all { it.value.compareTo(daily.first().value) == 0 }

        // A flat series is short-circuited rather than fitted so the slope is exactly
        // zero. Floating-point residue in a fitted plateau would leak into calorie
        // targets that are asserted to the cent.
        if (flat) {
            return LinearTrendFit(
                slopePerDay = BigDecimal.ZERO.setScale(SLOPE_SCALE),
                intercept = daily.first().value.setScale(SLOPE_SCALE, RoundingMode.HALF_UP),
                anchorDate = anchorDate,
                rSquared = null,
                slopeStdError = if (daily.size < 3) null else BigDecimal.ZERO.setScale(SLOPE_SCALE),
                observedDayCount = daily.size,
                spanDays = spanDays,
            )
        }

        val xs = daily.map { ChronoUnit.DAYS.between(anchorDate, it.date).toDouble() }
        val ys = daily.map { it.value.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()

        // Centered form. The algebraically equivalent raw-moment expression
        // (n*Sxy - Sx*Sy) / (n*Sxx - Sx^2) cancels catastrophically when the x values
        // are large and clustered, which is exactly a long range with recent weigh-ins.
        var sumXy = 0.0
        var sumXx = 0.0
        for (index in daily.indices) {
            val dx = xs[index] - meanX
            sumXy += dx * (ys[index] - meanY)
            sumXx += dx * dx
        }
        // Unreachable: distinct dates guarantee x variance. Guarded rather than divided blindly.
        if (sumXx == 0.0) return null

        val slope = sumXy / sumXx
        val intercept = meanY - slope * meanX

        var sumResidualSquares = 0.0
        var sumTotalSquares = 0.0
        for (index in daily.indices) {
            val residual = ys[index] - (intercept + slope * xs[index])
            sumResidualSquares += residual * residual
            val deviation = ys[index] - meanY
            sumTotalSquares += deviation * deviation
        }

        val rSquared = if (daily.size < 3 || sumTotalSquares == 0.0) {
            null
        } else {
            (1.0 - sumResidualSquares / sumTotalSquares)
                .toBigDecimal()
                .setScale(R_SQUARED_SCALE, RoundingMode.HALF_UP)
        }

        val slopeStdError = if (daily.size < 3) {
            null
        } else {
            kotlin.math.sqrt(sumResidualSquares / (daily.size - 2) / sumXx)
                .toBigDecimal()
                .setScale(SLOPE_SCALE, RoundingMode.HALF_UP)
        }

        return LinearTrendFit(
            slopePerDay = slope.toBigDecimal().setScale(SLOPE_SCALE, RoundingMode.HALF_UP),
            intercept = intercept.toBigDecimal().setScale(SLOPE_SCALE, RoundingMode.HALF_UP),
            anchorDate = anchorDate,
            rSquared = rSquared,
            slopeStdError = slopeStdError,
            observedDayCount = daily.size,
            spanDays = spanDays,
        )
    }

    /** One observation per date, ordered by date. Same-day measurements are averaged. */
    private fun averageByDate(points: List<TrendPoint>): List<TrendPoint> =
        points
            .groupBy { it.date }
            .toSortedMap()
            .map { (date, sameDay) ->
                TrendPoint(
                    date = date,
                    value = sameDay
                        .fold(BigDecimal.ZERO) { total, point -> total.add(point.value) }
                        .divide(BigDecimal(sameDay.size), DAILY_MEAN_SCALE, RoundingMode.HALF_UP),
                )
            }
}
