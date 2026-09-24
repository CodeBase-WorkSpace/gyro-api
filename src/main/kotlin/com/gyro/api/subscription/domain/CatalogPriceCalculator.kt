package com.gyro.api.subscription.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Calculates the immutable payable amount stored on a catalog price version. */
object CatalogPriceCalculator {
    private val oneHundred = BigDecimal("100.00")

    fun calculate(baseAmount: BigDecimal, discountPercent: BigDecimal): BigDecimal {
        val normalizedBaseAmount = baseAmount.setScale(2, RoundingMode.UNNECESSARY)
        val normalizedDiscountPercent = discountPercent.setScale(2, RoundingMode.UNNECESSARY)

        require(normalizedBaseAmount >= BigDecimal.ZERO) { "Base amount must be non-negative." }
        require(normalizedDiscountPercent >= BigDecimal.ZERO) { "Discount percent must be non-negative." }
        require(normalizedDiscountPercent < oneHundred) { "Discount percent must be less than 100." }

        return normalizedBaseAmount
            .multiply(oneHundred - normalizedDiscountPercent)
            .divide(oneHundred, 2, RoundingMode.HALF_UP)
    }
}
