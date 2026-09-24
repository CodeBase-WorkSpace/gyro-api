package com.gyro.api.goal.application.recalibration

import java.math.BigDecimal
import java.math.RoundingMode

/** Defines when an intentional schedule edit creates a new recalibration experiment. */
object TargetRegimePolicy {
    val ABSOLUTE_CALORIE_CHANGE: BigDecimal = BigDecimal(100)
    val RELATIVE_CALORIE_CHANGE: BigDecimal = BigDecimal("0.07")

    fun startsNewRegime(previous: BigDecimal, current: BigDecimal): Boolean {
        val absoluteDifference = previous.subtract(current).abs()
        if (absoluteDifference >= ABSOLUTE_CALORIE_CHANGE) return true
        if (previous <= BigDecimal.ZERO) return false
        return absoluteDifference.divide(previous, 6, RoundingMode.HALF_UP) >= RELATIVE_CALORIE_CHANGE
    }
}
