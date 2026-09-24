package com.gyro.api.food

import com.gyro.api.food.infrastructure.localizedPortionDisplayText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FoodPortionDisplayTextFormatterTest {
    @Test
    fun `formatter keeps english portion description for BCP 47 english locales`() {
        assertEquals(
            "1 cup, diced",
            localizedPortionDisplayText(
                locale = "en-us",
                amount = BigDecimal("1.0000"),
                unitName = "cup",
                modifier = "diced",
                gramWeight = BigDecimal("140.0000"),
                portionDescription = "1 cup, diced",
            ),
        )
    }

    @Test
    fun `formatter falls back to english description for unsupported locales`() {
        assertEquals(
            "1 cup, diced",
            localizedPortionDisplayText(
                locale = "es",
                amount = BigDecimal("1.0000"),
                unitName = "cup",
                modifier = "diced",
                gramWeight = BigDecimal("140.0000"),
                portionDescription = "1 cup, diced",
            ),
        )
    }

    @Test
    fun `formatter uses gram suffix only for persian locales`() {
        assertEquals(
            "1 فنجان diced (140 گرم)",
            localizedPortionDisplayText(
                locale = "fa-ir",
                amount = BigDecimal("1.0000"),
                unitName = "فنجان",
                modifier = "diced",
                gramWeight = BigDecimal("140.0000"),
                portionDescription = "1 cup, diced",
            ),
        )
    }
}
