package com.gyro.api.food.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A user-defined serving for a batch-prepared entity (meal today, recipe later):
 * the total cooked weight and the weight of one serving, both in grams.
 */
data class ServingDefinition(
    val totalBatchWeight: BigDecimal,
    val servingWeight: BigDecimal,
) {
    init {
        require(totalBatchWeight > BigDecimal.ZERO) { "totalBatchWeight must be positive." }
        require(servingWeight > BigDecimal.ZERO) { "servingWeight must be positive." }
        require(servingWeight <= totalBatchWeight) { "servingWeight must not exceed totalBatchWeight." }
    }

    val servingsPerBatch: BigDecimal
        get() = totalBatchWeight.divide(servingWeight, 4, RoundingMode.HALF_UP)

    /** Fraction of the whole batch represented by [servingCount] servings. */
    fun batchFraction(servingCount: BigDecimal): BigDecimal {
        return servingCount.multiply(servingWeight)
            .divide(totalBatchWeight, 8, RoundingMode.HALF_UP)
    }

    companion object {
        fun ofNullable(totalBatchWeight: BigDecimal?, servingWeight: BigDecimal?): ServingDefinition? {
            if (totalBatchWeight == null && servingWeight == null) return null
            require(totalBatchWeight != null && servingWeight != null) {
                "totalBatchWeight and servingWeight must be provided together."
            }
            return ServingDefinition(totalBatchWeight, servingWeight)
        }
    }
}
