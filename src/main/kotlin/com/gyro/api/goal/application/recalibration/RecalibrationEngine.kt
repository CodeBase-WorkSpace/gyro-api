package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.trend.LinearTrend
import com.gyro.api.common.trend.TrendPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class RecalibrationWeightPoint(
    val date: LocalDate,
    val weightKg: BigDecimal,
)

data class RecalibrationInput(
    /** Weigh-ins inside the observation window, any order. */
    val weights: List<RecalibrationWeightPoint>,
    /** Days in the window with at least one diary entry. */
    val loggedDays: Int,
    /** Logged days in the last seven completed days of the observation period. */
    val recentLoggedDays: Int = 0,
    /** Window length in days (normally 14). */
    val windowDays: Int,
    /** Mean logged calories per logged day. */
    val averageLoggedCalories: BigDecimal,
    /** Current plan targets. */
    val currentCalories: BigDecimal,
    val currentProtein: BigDecimal,
    val currentCarbs: BigDecimal,
    val currentFat: BigDecimal,
    /** Signed intended energy delta from the calculator (negative = deficit). */
    val intendedDailyEnergyDelta: BigDecimal,
    /** Audit-only evidence boundaries; they do not participate in engine decisions. */
    val windowStart: LocalDate? = null,
    val intakeThrough: LocalDate? = null,
    val weightThrough: LocalDate? = null,
    val averageHistoricalTargetCalories: BigDecimal? = null,
)

sealed interface RecalibrationOutcome {
    /** Not enough data or the adjustment is too small to bother the user. */
    data class NoSuggestion(
        val reason: String,
        /**
         * Present only when the engine completed a genuine dead-zone evaluation.
         * Other no-suggestion outcomes must not be presented as confirmation that
         * the current target is calibrated.
         */
        val estimatedTdee: BigDecimal? = null,
    ) : RecalibrationOutcome

    data class Suggestion(
        val suggestedCalories: BigDecimal,
        val suggestedProtein: BigDecimal,
        val suggestedCarbs: BigDecimal,
        val suggestedFat: BigDecimal,
        val basis: Map<String, Any?>,
    ) : RecalibrationOutcome
}

data class RecalibrationWindowEvaluation(
    val windowDays: Int,
    val outcome: RecalibrationOutcome,
    val observedKgPerWeek: BigDecimal? = null,
    val hasSufficientEvidence: Boolean = false,
)

/**
 * Pure recalibration math (suggest + confirm; the caller owns persistence and
 * consent). Estimates the user's actual maintenance from logged intake and the
 * measured weight trend, re-derives the target that achieves the plan's
 * intended energy delta, and caps the correction by how much the data supports:
 *
 *   observed kg/day  = least-squares slope over one averaged reading per day
 *   actual TDEE      = avg intake - observed kg/day * 7700
 *   suggested target = actual TDEE + intended delta
 *
 * The slope is a date-aware estimate of linear change, not a robust one: daily weight
 * noise is autocorrelated and a single extreme reading moves it materially. The
 * confidence cap, not the estimator, is what bounds the damage that can do.
 *
 * Guards: the [RecalibrationConfidence] LOW thresholds, correction capped by the
 * earned tier, ignored under 50 kcal, never a decrease on LOW, absolute floor
 * 1200 kcal. Macros scale proportionally.
 */
object RecalibrationEngine {
    val MIN_ADJUSTMENT: BigDecimal = BigDecimal(50)
    val CALORIE_FLOOR: BigDecimal = BigDecimal(1200)
    val KCAL_PER_KG: BigDecimal = BigDecimal(7700)

    fun evaluate(input: RecalibrationInput): RecalibrationOutcome = evaluateWindow(input).outcome

    fun evaluateWindow(input: RecalibrationInput): RecalibrationWindowEvaluation {
        val policy = RecalibrationWindowPolicy.forDaysOrNull(input.windowDays)
            ?: return RecalibrationWindowEvaluation(input.windowDays, RecalibrationOutcome.NoSuggestion("UNSUPPORTED_WINDOW"))
        // Distinct days, not rows. Counting rows would let three weigh-ins on one
        // morning clear a three-day threshold.
        val daily = LinearTrend.fitDaily(input.weights.map { TrendPoint(it.date, it.weightKg) })
        val observedDays = daily?.observedDayCount ?: input.weights.map { it.date }.distinct().size
        if (observedDays < RecalibrationConfidence.LOW_WEIGH_IN_DAYS) {
            return RecalibrationWindowEvaluation(input.windowDays, RecalibrationOutcome.NoSuggestion("INSUFFICIENT_WEIGH_INS"))
        }
        val exactSpan = daily?.spanDays ?: 0L
        if (exactSpan < RecalibrationConfidence.LOW_SPAN_DAYS) {
            return RecalibrationWindowEvaluation(input.windowDays, RecalibrationOutcome.NoSuggestion("INSUFFICIENT_WEIGHT_SPAN"))
        }
        val loggedRatio = input.loggedDays.toDouble() / input.windowDays
        if (input.loggedDays < policy.minimumLoggedDays || loggedRatio < policy.minimumCoverage ||
            policy.requiresRecentCoverage && input.recentLoggedDays < MINIMUM_RECENT_LOGGED_DAYS
        ) {
            return RecalibrationWindowEvaluation(input.windowDays, RecalibrationOutcome.NoSuggestion("INSUFFICIENT_LOGGED_DAYS"))
        }

        // Non-null after the three gates above, which are the LOW thresholds.
        val confidence = RecalibrationConfidence.of(observedDays, exactSpan, loggedRatio)
            ?: return RecalibrationWindowEvaluation(input.windowDays, RecalibrationOutcome.NoSuggestion("INSUFFICIENT_LOGGED_DAYS"))
        val fit = requireNotNull(daily) { "A cleared span implies a fitted line." }
        val observedKgPerDay = fit.slopePerDay

        val actualTdee = input.averageLoggedCalories
            .subtract(observedKgPerDay.multiply(KCAL_PER_KG))
        val idealTarget = actualTdee.add(input.intendedDailyEnergyDelta)

        val rawAdjustment = idealTarget.subtract(input.currentCalories)
        if (rawAdjustment.abs() < MIN_ADJUSTMENT) {
            return RecalibrationWindowEvaluation(
                windowDays = input.windowDays,
                outcome = RecalibrationOutcome.NoSuggestion(
                    reason = "ADJUSTMENT_TOO_SMALL",
                    estimatedTdee = actualTdee.setScale(2, RoundingMode.HALF_UP),
                ),
                observedKgPerWeek = observedKgPerDay.multiply(BigDecimal(7)),
                hasSufficientEvidence = true,
            )
        }
        // LOW confidence may restore calories when observed loss outpaces the plan, but
        // may never recommend further restriction. The dominant systematic error in this
        // pipeline is calorie under-reporting, which biases estimatedTdee downward and
        // pushes suggestions toward cuts; the rule points against the known bias.
        //
        // Checked after MIN_ADJUSTMENT so a trivial -20 is honestly "nothing to do"
        // rather than "we are withholding something", and before the cap so the decision
        // is made on the real correction.
        if (confidence == RecalibrationConfidence.LOW && rawAdjustment.signum() < 0) {
            return RecalibrationWindowEvaluation(
                windowDays = input.windowDays,
                outcome = RecalibrationOutcome.NoSuggestion("LOW_CONFIDENCE_DECREASE_WITHHELD"),
                observedKgPerWeek = observedKgPerDay.multiply(BigDecimal(7)),
                hasSufficientEvidence = true,
            )
        }
        val maxAdjustment = confidence.maxAdjustment
        val clampedAdjustment = rawAdjustment
            .min(maxAdjustment)
            .max(maxAdjustment.negate())

        val targetBeforeFloor = input.currentCalories.add(clampedAdjustment)
        val flooredTo = CALORIE_FLOOR.takeIf { targetBeforeFloor < it }
        val suggested = targetBeforeFloor
            .max(CALORIE_FLOOR)
            .setScale(2, RoundingMode.HALF_UP)
        if (suggested.compareTo(input.currentCalories) == 0) {
            return RecalibrationWindowEvaluation(
                windowDays = input.windowDays,
                outcome = RecalibrationOutcome.NoSuggestion("ADJUSTMENT_TOO_SMALL"),
                observedKgPerWeek = observedKgPerDay.multiply(BigDecimal(7)),
                hasSufficientEvidence = true,
            )
        }

        val ratio = if (input.currentCalories > BigDecimal.ZERO) {
            suggested.divide(input.currentCalories, 6, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ONE
        }

        return RecalibrationWindowEvaluation(
            windowDays = input.windowDays,
            outcome = RecalibrationOutcome.Suggestion(
                suggestedCalories = suggested,
                suggestedProtein = macro(input.currentProtein, ratio),
                suggestedCarbs = macro(input.currentCarbs, ratio),
                suggestedFat = macro(input.currentFat, ratio),
                basis = mapOf(
                "windowDays" to input.windowDays,
                // Raw rows, unchanged in meaning; weighInDays is the number the gates use.
                "weighIns" to input.weights.size,
                "weighInDays" to observedDays,
                "weightSpanDays" to exactSpan,
                "loggedDays" to input.loggedDays,
                "averageLoggedCalories" to input.averageLoggedCalories.toPlainString(),
                "windowStart" to input.windowStart?.toString(),
                "intakeThrough" to input.intakeThrough?.toString(),
                "weightThrough" to input.weightThrough?.toString(),
                "averageHistoricalTargetCalories" to input.averageHistoricalTargetCalories?.toPlainString(),
                "trendMethod" to "OLS",
                // observedKgPerWeek is a client contract: read by the dashboard card.
                // Same key, same scale, larger magnitude than the EMA it replaced.
                "observedKgPerWeek" to observedKgPerDay.multiply(BigDecimal(7))
                    .setScale(3, RoundingMode.HALF_UP).toPlainString(),
                "trendSlopeKgPerDay" to observedKgPerDay.toPlainString(),
                // Diagnostic snapshots for auditing a past decision. No branch reads
                // them. Explicit null distinguishes "computed, undefined" (a plateau)
                // from a pre-OLS row that never recorded them at all.
                "trendRSquared" to fit.rSquared?.toPlainString(),
                "trendStdErrorKgPerDay" to fit.slopeStdError?.toPlainString(),
                "estimatedTdee" to actualTdee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "intendedDailyEnergyDelta" to input.intendedDailyEnergyDelta.toPlainString(),
                "confidence" to confidence.name,
                "maxAdjustment" to maxAdjustment.toPlainString(),
                "rawAdjustment" to rawAdjustment.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "clampedAdjustment" to clampedAdjustment.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "flooredTo" to flooredTo?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
                ),
            ),
            observedKgPerWeek = observedKgPerDay.multiply(BigDecimal(7)),
            hasSufficientEvidence = true,
        )
    }

    private fun macro(value: BigDecimal, ratio: BigDecimal): BigDecimal =
        value.multiply(ratio).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP)

    private const val MINIMUM_RECENT_LOGGED_DAYS = 3
}
