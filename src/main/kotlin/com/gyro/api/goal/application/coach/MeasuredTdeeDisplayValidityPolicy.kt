package com.gyro.api.goal.application.coach

import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import java.math.BigDecimal

/**
 * Versioned presentation-only plausibility gates for the measured TDEE observation.
 *
 * These bounds intentionally do not alter observed-energy or recalibration math. They
 * keep an implausible OLS result, especially one driven by an erroneous weigh-in, out
 * of the ranked Coach payload until better evidence is available. Nutrition Coach owns
 * this policy: changing a boundary requires product documentation, metric review, and a
 * version bump because it changes which users see a numeric health-related estimate.
 *
 * The 1,200–5,000 kcal range bounds the displayed energy estimate. The ±1.50 kg/week
 * and 0.15 kg/day standard-error limits bound the fitted weight evidence rather than
 * changing recalibration math. They are deployment-controlled deliberately; runtime
 * configuration would make support and experiment attribution less reproducible.
 */
internal object MeasuredTdeeDisplayValidityPolicy {
    const val VERSION = "V1"
    const val MIN_DISPLAYED_TDEE_KCAL = 1_200
    const val MAX_DISPLAYED_TDEE_KCAL = 5_000

    // Retained for source compatibility; the bounds now live on the shared policy the
    // measured-TDEE and trend-explanation observations both consume.
    val MAX_ABSOLUTE_TREND_KG_PER_WEEK: BigDecimal = WeightTrendPlausibilityPolicy.MAX_ABSOLUTE_TREND_KG_PER_WEEK
    val MAX_SLOPE_STD_ERROR_KG_PER_DAY: BigDecimal = WeightTrendPlausibilityPolicy.MAX_SLOPE_STD_ERROR_KG_PER_DAY

    fun suppressionReason(
        analysis: ObservedEnergyAnalysis,
        displayedValue: Int,
    ): MeasuredTdeeSuppressionReason? {
        if (displayedValue !in MIN_DISPLAYED_TDEE_KCAL..MAX_DISPLAYED_TDEE_KCAL) {
            return MeasuredTdeeSuppressionReason.DISPLAYED_TDEE_OUT_OF_RANGE
        }

        val estimatedTdee = analysis.estimatedTdee
            ?: return MeasuredTdeeSuppressionReason.INVALID_ANALYSIS
        if (
            estimatedTdee.compareTo(BigDecimal(MIN_DISPLAYED_TDEE_KCAL)) < 0 ||
            estimatedTdee.compareTo(BigDecimal(MAX_DISPLAYED_TDEE_KCAL)) > 0
        ) return MeasuredTdeeSuppressionReason.ESTIMATED_TDEE_OUT_OF_RANGE

        val observedKgPerWeek = analysis.observedKgPerWeek
            ?: return MeasuredTdeeSuppressionReason.MISSING_WEIGHT_TREND
        if (observedKgPerWeek.abs() > MAX_ABSOLUTE_TREND_KG_PER_WEEK) {
            return MeasuredTdeeSuppressionReason.WEIGHT_TREND_OUT_OF_RANGE
        }
        analysis.trendRSquared?.let { rSquared ->
            if (rSquared.signum() < 0 || rSquared > BigDecimal.ONE) {
                return MeasuredTdeeSuppressionReason.INVALID_R_SQUARED
            }
        }
        analysis.trendStdErrorKgPerDay?.let { standardError ->
            if (standardError.signum() < 0 || standardError > MAX_SLOPE_STD_ERROR_KG_PER_DAY) {
                return MeasuredTdeeSuppressionReason.SLOPE_STANDARD_ERROR_OUT_OF_RANGE
            }
        }
        return null
    }

    fun isValid(analysis: ObservedEnergyAnalysis, displayedValue: Int): Boolean =
        suppressionReason(analysis, displayedValue) == null
}

/** Fixed-cardinality metric reasons for measured-TDEE presentation suppression. */
internal enum class MeasuredTdeeSuppressionReason {
    INVALID_ANALYSIS,
    DISPLAYED_TDEE_OUT_OF_RANGE,
    ESTIMATED_TDEE_OUT_OF_RANGE,
    MISSING_WEIGHT_TREND,
    WEIGHT_TREND_OUT_OF_RANGE,
    INVALID_R_SQUARED,
    SLOPE_STANDARD_ERROR_OUT_OF_RANGE,
}
