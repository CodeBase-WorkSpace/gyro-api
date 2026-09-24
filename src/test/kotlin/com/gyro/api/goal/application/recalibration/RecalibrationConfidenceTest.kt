package com.gyro.api.goal.application.recalibration

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecalibrationConfidenceTest {
    @Test
    fun `each tier is earned at its exact thresholds`() {
        assertEquals(RecalibrationConfidence.LOW, RecalibrationConfidence.of(3, 7L, 0.50))
        assertEquals(RecalibrationConfidence.MEDIUM, RecalibrationConfidence.of(5, 10L, 0.60))
        assertEquals(RecalibrationConfidence.HIGH, RecalibrationConfidence.of(8, 12L, 0.75))
    }

    @Test
    fun `nothing below the LOW thresholds earns a tier`() {
        assertNull(RecalibrationConfidence.of(2, 7L, 0.50))
        assertNull(RecalibrationConfidence.of(3, 6L, 0.50))
        assertNull(RecalibrationConfidence.of(3, 7L, 0.49))
    }

    @Test
    fun `span is a calendar offset, so six days is short of seven`() {
        // July 1 to July 8 is 7. An off-by-one here silently moves who qualifies.
        assertNull(RecalibrationConfidence.of(3, 6L, 1.0))
        assertEquals(RecalibrationConfidence.LOW, RecalibrationConfidence.of(3, 7L, 1.0))
    }

    @Test
    fun `the weakest axis decides the tier`() {
        // Plenty of weight data, thin diary. This is a two-input estimator and the
        // calorie side is what will be wrong, so the cap follows the worse input.
        assertEquals(RecalibrationConfidence.LOW, RecalibrationConfidence.of(20, 14L, 0.52))
        // Mirror: dense logging, barely any weigh-ins.
        assertEquals(RecalibrationConfidence.LOW, RecalibrationConfidence.of(3, 30L, 1.0))
        // One axis short of HIGH drops the whole thing to MEDIUM.
        assertEquals(RecalibrationConfidence.MEDIUM, RecalibrationConfidence.of(8, 12L, 0.74))
        assertEquals(RecalibrationConfidence.MEDIUM, RecalibrationConfidence.of(7, 12L, 0.75))
        assertEquals(RecalibrationConfidence.MEDIUM, RecalibrationConfidence.of(8, 11L, 0.75))
    }

    @Test
    fun `caps rise with confidence`() {
        assertEquals(0, BigDecimal(75).compareTo(RecalibrationConfidence.LOW.maxAdjustment))
        assertEquals(0, BigDecimal(150).compareTo(RecalibrationConfidence.MEDIUM.maxAdjustment))
        assertEquals(0, BigDecimal(200).compareTo(RecalibrationConfidence.HIGH.maxAdjustment))
    }

    @Test
    fun `the nutrition coach fixture shape sits at MEDIUM`() {
        // Five weigh-in days is load-bearing: at six, the coach fixtures drop to LOW,
        // their negative correction is withheld, and four coach state tests change
        // meaning. Pinned here so the boundary is not moved casually.
        assertEquals(RecalibrationConfidence.MEDIUM, RecalibrationConfidence.of(5, 12L, 1.0))
    }
}
