package com.gyro.api.goal.application.coach

import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import java.math.BigDecimal

/**
 * Shared plausibility gates for a fitted weight trend used by presentation.
 *
 * These bounds intentionally do not alter observed-energy or recalibration math. They
 * keep an implausible OLS result — especially one driven by an erroneous weigh-in or by
 * autocorrelated daily noise — out of any ranked Coach observation until better evidence
 * is available. The measured-TDEE display policy and the trend-explanation observation
 * both consume the same contract so the two never disagree about which fits are usable.
 */
internal object WeightTrendPlausibilityPolicy {
    val MAX_ABSOLUTE_TREND_KG_PER_WEEK: BigDecimal = BigDecimal("1.50")
    val MAX_SLOPE_STD_ERROR_KG_PER_DAY: BigDecimal = BigDecimal("0.15")

    /**
     * Whether the fit's magnitude, coefficient of determination, and slope standard
     * error stay inside bounds. A flat fit has a null r-squared by design; its zero
     * slope and standard error still pass the independent bounds below.
     */
    fun hasPlausibleTrend(analysis: ObservedEnergyAnalysis): Boolean {
        val observedKgPerWeek = analysis.observedKgPerWeek ?: return false
        if (observedKgPerWeek.abs().compareTo(MAX_ABSOLUTE_TREND_KG_PER_WEEK) > 0) return false

        analysis.trendRSquared?.let { rSquared ->
            if (rSquared.signum() < 0 || rSquared.compareTo(BigDecimal.ONE) > 0) return false
        }

        analysis.trendStdErrorKgPerDay?.let { standardError ->
            if (
                standardError.signum() < 0 ||
                standardError.compareTo(MAX_SLOPE_STD_ERROR_KG_PER_DAY) > 0
            ) return false
        }

        return true
    }
}
