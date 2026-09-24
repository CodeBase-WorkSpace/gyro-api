package com.gyro.api.goal.application.recalibration

import java.math.BigDecimal

/**
 * How much correction the available data earns.
 *
 * Replaces a single on/off gate with a graded one: weak data still produces a
 * suggestion, but a small one, and the label says so. The tier caps the correction —
 * it never changes the arithmetic that produced it.
 *
 * Three axes, scored independently, and the **weakest** one wins. This is a two-input
 * estimator: the weight trend supplies the rate and the diary supplies intake, and a
 * TDEE estimate is only as good as the worse of the two. Twenty weigh-ins against 52%
 * diary coverage is LOW, because the calorie side is what will be wrong.
 *
 * Deliberately **not** inputs: r-squared and the slope standard error. Both are
 * residual-based, and daily weight noise is autocorrelated, so both read optimistically
 * and would manufacture HIGH. R-squared is additionally undefined on a plateau — the one
 * scenario recalibration exists for — so scoring it would silence exactly the users who
 * need the feature. Every axis here is something the user can act on and the coach card
 * can explain: "log two more days", "weigh in twice more". That is the rule for adding a
 * fourth.
 */
enum class RecalibrationConfidence(val maxAdjustment: BigDecimal) {
    LOW(BigDecimal(75)),
    MEDIUM(BigDecimal(150)),
    HIGH(BigDecimal(200)),
    ;

    companion object {
        const val LOW_WEIGH_IN_DAYS = 3
        const val LOW_SPAN_DAYS = 7L
        const val LOW_LOGGED_RATIO = 0.50

        /**
         * Five weigh-in days is load-bearing, not a round number. The nutrition coach
         * fixtures sit at exactly five, and moving this to six drops them to LOW, where
         * the increase-only rule withholds their (negative) correction and four coach
         * state tests change meaning.
         */
        const val MEDIUM_WEIGH_IN_DAYS = 5
        const val MEDIUM_SPAN_DAYS = 10L
        const val MEDIUM_LOGGED_RATIO = 0.60

        const val HIGH_WEIGH_IN_DAYS = 8
        const val HIGH_SPAN_DAYS = 12L
        const val HIGH_LOGGED_RATIO = 0.75

        /**
         * Null when the input does not clear even [LOW]; the caller reports which axis
         * fell short, since the coach card renders per-axis progress.
         *
         * [spanDays] is a calendar-day offset between the first and last observed day —
         * July 1 to July 8 is 7, not 8.
         */
        fun of(
            weighInDays: Int,
            spanDays: Long,
            loggedRatio: Double,
        ): RecalibrationConfidence? = when {
            weighInDays < LOW_WEIGH_IN_DAYS ||
                spanDays < LOW_SPAN_DAYS ||
                loggedRatio < LOW_LOGGED_RATIO -> null

            weighInDays >= HIGH_WEIGH_IN_DAYS &&
                spanDays >= HIGH_SPAN_DAYS &&
                loggedRatio >= HIGH_LOGGED_RATIO -> HIGH

            weighInDays >= MEDIUM_WEIGH_IN_DAYS &&
                spanDays >= MEDIUM_SPAN_DAYS &&
                loggedRatio >= MEDIUM_LOGGED_RATIO -> MEDIUM

            else -> LOW
        }
    }
}
