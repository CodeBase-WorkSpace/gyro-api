package com.gyro.api.subscription.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CatalogPriceCalculatorTest {
    @Test
    fun `zero discount preserves base amount`() {
        assertEquals(
            BigDecimal("1990000.00"),
            CatalogPriceCalculator.calculate(BigDecimal("1990000.00"), BigDecimal.ZERO),
        )
    }

    @Test
    fun `discount uses half up rounding at money scale`() {
        assertEquals(
            BigDecimal("66.67"),
            CatalogPriceCalculator.calculate(BigDecimal("100.00"), BigDecimal("33.33")),
        )
    }

    @Test
    fun `one hundred percent discount is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogPriceCalculator.calculate(BigDecimal("100.00"), BigDecimal("100.00"))
        }
    }
}
