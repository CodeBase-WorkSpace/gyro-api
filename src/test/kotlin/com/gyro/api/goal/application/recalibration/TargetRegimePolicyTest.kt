package com.gyro.api.goal.application.recalibration

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetRegimePolicyTest {
    @Test
    fun `absolute calorie threshold starts a new regime`() {
        assertTrue(TargetRegimePolicy.startsNewRegime(BigDecimal("2500"), BigDecimal("2400")))
    }

    @Test
    fun `relative calorie threshold protects lower targets`() {
        assertTrue(TargetRegimePolicy.startsNewRegime(BigDecimal("1100"), BigDecimal("1180")))
    }

    @Test
    fun `small changes retain the existing regime`() {
        assertFalse(TargetRegimePolicy.startsNewRegime(BigDecimal("1200"), BigDecimal("1280")))
        assertFalse(TargetRegimePolicy.startsNewRegime(BigDecimal("1600"), BigDecimal("1590")))
    }
}
