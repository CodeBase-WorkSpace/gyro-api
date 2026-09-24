package com.gyro.api.common.decimal

import java.math.BigDecimal
import java.math.RoundingMode

object NutritionDecimal {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(3)

    fun calories(value: BigDecimal): BigDecimal {
        return nonNegative(value, "Calories").setScale(2, RoundingMode.HALF_UP)
    }

    fun macro(value: BigDecimal): BigDecimal {
        return nonNegative(value, "Macro value").setScale(3, RoundingMode.HALF_UP)
    }

    fun servingQuantity(value: BigDecimal): BigDecimal {
        return positive(value, "Serving quantity").setScale(3, RoundingMode.HALF_UP)
    }

    fun sodium(value: BigDecimal): BigDecimal {
        return nonNegative(value, "Sodium").setScale(3, RoundingMode.HALF_UP)
    }

    fun weight(value: BigDecimal): BigDecimal {
        return positive(value, "Weight").setScale(3, RoundingMode.HALF_UP)
    }

    private fun nonNegative(
        value: BigDecimal,
        label: String,
    ): BigDecimal {
        require(value >= BigDecimal.ZERO) { "$label must be zero or greater." }
        return value
    }

    private fun positive(
        value: BigDecimal,
        label: String,
    ): BigDecimal {
        require(value > BigDecimal.ZERO) { "$label must be greater than zero." }
        return value
    }
}
