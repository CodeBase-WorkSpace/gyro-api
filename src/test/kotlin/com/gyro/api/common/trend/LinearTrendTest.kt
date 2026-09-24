package com.gyro.api.common.trend

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearTrendTest {
    private val anchor: LocalDate = LocalDate.parse("2026-07-01")

    @Test
    fun `perfect ascending line recovers its slope exactly`() {
        val fit = LinearTrend.fitDaily(series(0L to "80.0", 1L to "80.5", 2L to "81.0", 3L to "81.5", 4L to "82.0"))!!

        assertEquals(0, BigDecimal("0.500000").compareTo(fit.slopePerDay))
        assertEquals(0, BigDecimal("80.000000").compareTo(fit.intercept))
        assertEquals(anchor, fit.anchorDate)
        assertEquals(0, BigDecimal("1.0000").compareTo(fit.rSquared!!))
        assertEquals(0, BigDecimal.ZERO.compareTo(fit.slopeStdError!!))
        assertEquals(5, fit.observedDayCount)
        assertEquals(4L, fit.spanDays)
    }

    @Test
    fun `perfect descending line recovers its slope exactly`() {
        val fit = LinearTrend.fitDaily(series(0L to "82.0", 2L to "81.0", 4L to "80.0", 6L to "79.0"))!!

        assertEquals(0, BigDecimal("-0.500000").compareTo(fit.slopePerDay))
        assertEquals(0, BigDecimal("82.000000").compareTo(fit.intercept))
    }

    @Test
    fun `valueAt projects the fitted line, including outside the observed window`() {
        val fit = LinearTrend.fitDaily(series(0L to "80.0", 2L to "81.0", 4L to "82.0"))!!

        assertEquals(0, BigDecimal("80.000").compareTo(fit.valueAt(anchor)))
        assertEquals(0, BigDecimal("81.500").compareTo(fit.valueAt(anchor.plusDays(3))))
        assertEquals(0, BigDecimal("85.000").compareTo(fit.valueAt(anchor.plusDays(10))))
    }

    @Test
    fun `no line is defined below two distinct dates`() {
        assertNull(LinearTrend.fitDaily(emptyList()))
        assertNull(LinearTrend.fitDaily(series(0L to "80.0")))
    }

    @Test
    fun `repeated measurements on a single date do not define a line`() {
        // Zero variance in x. Reporting slope zero here would assert "no change" from
        // data that says nothing about change.
        assertNull(LinearTrend.fitDaily(series(0L to "80.0", 0L to "80.4", 0L to "79.6")))
    }

    @Test
    fun `same-day measurements collapse to one observation`() {
        val fit = LinearTrend.fitDaily(
            series(0L to "80.0", 0L to "80.4", 0L to "80.8", 8L to "79.4"),
        )!!

        // Three weigh-ins on one morning are one observed day averaging 80.4, not three
        // independent observations. The recalibration weigh-in gate depends on this.
        assertEquals(2, fit.observedDayCount)
        assertEquals(8L, fit.spanDays)
        assertEquals(0, BigDecimal("-0.125000").compareTo(fit.slopePerDay))
    }

    @Test
    fun `r squared and standard error are null with only two observations`() {
        val fit = LinearTrend.fitDaily(series(0L to "80.0", 7L to "79.0"))!!

        // A line through two points always fits perfectly, so a value of 1 would
        // claim evidence that does not exist.
        assertNull(fit.rSquared)
        assertNull(fit.slopeStdError)
        assertEquals(0, BigDecimal("-0.142857").compareTo(fit.slopePerDay))
    }

    @Test
    fun `a flat series has an exactly zero slope and an undefined r squared`() {
        val fit = LinearTrend.fitDaily(series(0L to "80.0", 3L to "80.0", 6L to "80.0", 9L to "80.0"))!!

        assertEquals(0, BigDecimal.ZERO.compareTo(fit.slopePerDay))
        assertEquals(0, BigDecimal("80.000000").compareTo(fit.intercept))
        // Total sum of squares is zero, so r squared is 0/0 rather than a perfect fit.
        assertNull(fit.rSquared)
        assertEquals(0, BigDecimal.ZERO.compareTo(fit.slopeStdError!!))
        assertEquals(0, BigDecimal("80.000").compareTo(fit.valueAt(anchor.plusDays(30))))
    }

    @Test
    fun `a flat two-point series reports a null standard error`() {
        val fit = LinearTrend.fitDaily(series(0L to "80.0", 7L to "80.0"))!!

        assertEquals(0, BigDecimal.ZERO.compareTo(fit.slopePerDay))
        assertNull(fit.rSquared)
        assertNull(fit.slopeStdError)
    }

    @Test
    fun `spanDays counts calendar offsets, not inclusive days`() {
        // The confidence tiers read these thresholds directly, so an off-by-one here
        // moves eligibility. July 1 to July 8 is 7.
        assertEquals(6L, LinearTrend.fitDaily(series(0L to "80.0", 6L to "79.0"))!!.spanDays)
        assertEquals(7L, LinearTrend.fitDaily(series(0L to "80.0", 7L to "79.0"))!!.spanDays)
    }

    @Test
    fun `identical values at different spacings produce different slopes`() {
        val values = listOf("80.0", "79.8", "79.6", "79.4", "79.2")
        val clustered = LinearTrend.fitDaily(
            listOf(0L, 1L, 2L, 3L, 13L).mapIndexed { index, day -> point(day, values[index]) },
        )!!
        val even = LinearTrend.fitDaily(
            listOf(0L, 3L, 6L, 9L, 12L).mapIndexed { index, day -> point(day, values[index]) },
        )!!

        // The replaced EMA path took List<BigDecimal> and discarded dates entirely, so
        // these two series were indistinguishable to it.
        assertNotEquals(clustered.slopePerDay, even.slopePerDay)
        assertEquals(0, BigDecimal("-0.066667").compareTo(even.slopePerDay))
        assertTrue(clustered.slopePerDay > even.slopePerDay)
    }

    @Test
    fun `a single extreme measurement moves the slope substantially`() {
        // Outlier policy is documented absence: ordinary least squares is influenced by
        // outliers and this phase accepts that. One 5 kg reading turns an otherwise flat
        // series into 0.5 kg/day, and r squared drops to 0.5 rather than flagging it.
        val fit = LinearTrend.fitDaily(
            series(0L to "80.0", 2L to "80.0", 4L to "80.0", 6L to "80.0", 8L to "85.0"),
        )!!

        assertEquals(0, BigDecimal("0.500000").compareTo(fit.slopePerDay))
        assertEquals(0, BigDecimal("0.5000").compareTo(fit.rSquared!!))
    }

    @Test
    fun `input order does not affect the fit`() {
        val ordered = LinearTrend.fitDaily(series(0L to "80.0", 2L to "81.0", 4L to "82.0"))!!
        val shuffled = LinearTrend.fitDaily(series(4L to "82.0", 0L to "80.0", 2L to "81.0"))!!

        assertEquals(ordered, shuffled)
    }

    @Test
    fun `a window far from the anchor is fitted from its own first observation`() {
        // Note what this does NOT prove: x is measured from the earliest observation, so
        // these become 0..3 and the centered form is never stressed. Large raw x values
        // are unreachable through this API. The test is here because a fit ten years out
        // must still be anchor-relative, not because it exercises conditioning.
        val fit = LinearTrend.fitDaily(
            series(3650L to "80.0", 3651L to "79.9", 3652L to "79.8", 3653L to "79.7"),
        )!!
        assertEquals(anchor.plusDays(3650), fit.anchorDate)

        assertEquals(0, BigDecimal("-0.100000").compareTo(fit.slopePerDay))
        assertEquals(0, BigDecimal("1.0000").compareTo(fit.rSquared!!))
    }

    private fun series(vararg entries: Pair<Long, String>): List<TrendPoint> =
        entries.map { (day, value) -> point(day, value) }

    private fun point(day: Long, value: String) = TrendPoint(anchor.plusDays(day), BigDecimal(value))
}
