package com.gyro.api.food

import com.gyro.api.food.infrastructure.FoodSearchNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FoodSearchNormalizerTest {
    private val normalizer = FoodSearchNormalizer()

    @Test
    fun `normalizes english search text`() {
        val result = normalizer.normalizeSearchQuery("  Chicken   Breast!! ")

        assertEquals("chicken breast", result.value)
        assertEquals("en", result.locale)
        assertFalse(result.isBlank)
    }

    @Test
    fun `normalizes farsi search text`() {
        val result = normalizer.normalizeSearchQuery("  كاهو\u200Cی ۱۲۳، ")

        assertEquals("کاهوی 123", result.value)
        assertEquals("fa", result.locale)
        assertFalse(result.isBlank)
    }
}
