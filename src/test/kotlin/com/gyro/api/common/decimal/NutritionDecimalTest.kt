package com.gyro.api.common.decimal

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NutritionDecimalTest {
    @Test
    fun `calories round to two decimal places`() {
        assertEquals(BigDecimal("123.46"), NutritionDecimal.calories(BigDecimal("123.456")))
    }

    @Test
    fun `macro values round to three decimal places`() {
        assertEquals(BigDecimal("12.346"), NutritionDecimal.macro(BigDecimal("12.3456")))
    }

    @Test
    fun `serving quantity must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            NutritionDecimal.servingQuantity(BigDecimal.ZERO)
        }
    }

    @Test
    fun `weight must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            NutritionDecimal.weight(BigDecimal("-1.0"))
        }
    }
}
