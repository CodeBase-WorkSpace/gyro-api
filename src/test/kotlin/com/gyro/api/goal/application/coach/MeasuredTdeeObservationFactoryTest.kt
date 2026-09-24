package com.gyro.api.goal.application.coach

import com.gyro.api.common.trend.LinearTrend
import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.diary.application.DashboardInsightBasis
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasuredTdeeObservationFactoryTest {
    private val factory = MeasuredTdeeObservationFactory()

    @Test
    fun `sufficient evidence creates the structured measured TDEE candidate`() {
        val candidate = factory.create(analysis(estimatedTdee = BigDecimal("2344.99")))!!

        assertEquals(DashboardInsightKind.MEASURED_TDEE, candidate.insight.kind)
        assertEquals("OBS|MT|V1|2026-08-01|MEDIUM|B2300", candidate.insight.impressionId)
        assertEquals(2340, candidate.insight.value)
        assertEquals(DashboardInsightBasis.OBSERVED_ENERGY, candidate.insight.basis)
        assertEquals(RecalibrationConfidence.MEDIUM, candidate.insight.confidence)
        assertEquals("OLS_7700_V1", candidate.insight.estimatorVersion)
        assertEquals(MeasuredTdeeDisplayValidityPolicy.VERSION, candidate.insight.displayPolicyVersion)
        assertEquals(14, candidate.insight.windowDays)
        assertEquals(10, candidate.insight.loggedDayCount)
        assertEquals(5, candidate.insight.weighInDayCount)
        assertEquals(12, candidate.insight.weightSpanDays)
        assertEquals(LocalDate.parse("2026-07-19"), candidate.insight.periodStart)
        assertEquals(LocalDate.parse("2026-08-01"), candidate.insight.periodEnd)
        assertEquals(0.80, candidate.magnitude)
    }

    @Test
    fun `confidence tiers map to the requested ranking magnitudes`() {
        assertEquals(0.60, factory.create(analysis(confidence = RecalibrationConfidence.LOW))?.magnitude)
        assertEquals(0.80, factory.create(analysis(confidence = RecalibrationConfidence.MEDIUM))?.magnitude)
        assertEquals(1.00, factory.create(analysis(confidence = RecalibrationConfidence.HIGH))?.magnitude)
    }

    @Test
    fun `display rounding uses HALF_UP at the ten calorie boundary`() {
        val candidate = factory.create(analysis(estimatedTdee = BigDecimal("2345")))!!

        assertEquals(2350, candidate.insight.value)
        assertEquals("OBS|MT|V1|2026-08-01|MEDIUM|B2300", candidate.insight.impressionId)
    }

    @Test
    fun `flat trend with undefined r squared remains display-valid`() {
        val candidate = factory.create(
            analysis(
                trendRSquared = null,
                trendStdErrorKgPerDay = BigDecimal.ZERO,
            ),
        )!!

        assertEquals(2340, candidate.insight.value)
    }

    @Test
    fun `downward outlier trend is not display-valid`() {
        assertNull(
            factory.create(
                analysis(
                    estimatedTdee = BigDecimal("6150"),
                    observedKgPerWeek = BigDecimal("-3.50"),
                    trendRSquared = BigDecimal("0.80"),
                    trendStdErrorKgPerDay = BigDecimal("0.10"),
                ),
            ),
        )
    }

    @Test
    fun `upward outlier trend is not display-valid`() {
        assertNull(
            factory.create(
                analysis(
                    estimatedTdee = BigDecimal("1150"),
                    observedKgPerWeek = BigDecimal("3.50"),
                    trendRSquared = BigDecimal("0.80"),
                    trendStdErrorKgPerDay = BigDecimal("0.10"),
                ),
            ),
        )
    }

    @Test
    fun `near-flat noisy weight series with low r squared remains display-valid`() {
        val firstDate = LocalDate.parse("2026-07-20")
        val fit = LinearTrend.fitDaily(
            listOf(
                TrendPoint(firstDate, BigDecimal("80.0")),
                TrendPoint(firstDate.plusDays(3), BigDecimal("80.1")),
                TrendPoint(firstDate.plusDays(6), BigDecimal("79.9")),
                TrendPoint(firstDate.plusDays(9), BigDecimal("80.1")),
                TrendPoint(firstDate.plusDays(12), BigDecimal("79.9")),
            ),
        )!!
        val rSquared = fit.rSquared ?: error("near-flat series should have r squared")
        val slopeStdError = fit.slopeStdError ?: error("near-flat series should have slope standard error")

        val candidate = factory.create(
            analysis(
                estimatedTdee = BigDecimal("2351"),
                observedKgPerWeek = fit.slopePerDay.multiply(BigDecimal(7))
                    .setScale(3, RoundingMode.HALF_UP),
                trendRSquared = rSquared,
                trendStdErrorKgPerDay = slopeStdError,
            ),
        )!!

        assertTrue(rSquared < BigDecimal("0.20"))
        assertTrue(slopeStdError < BigDecimal("0.15"))
        assertEquals(2350, candidate.insight.value)
    }

    @Test
    fun `excessive slope standard error is invalid independently of r squared`() {
        assertNull(
            factory.create(
                analysis(
                    observedKgPerWeek = BigDecimal("0.20"),
                    trendRSquared = BigDecimal("0.80"),
                    trendStdErrorKgPerDay = BigDecimal("0.16"),
                ),
            ),
        )
    }

    @Test
    fun `suppression outcomes identify the versioned product policy boundary`() {
        val outcome = factory.evaluate(
            analysis(
                observedKgPerWeek = BigDecimal("1.51"),
                trendStdErrorKgPerDay = BigDecimal("0.10"),
            ),
        )

        assertEquals(
            MeasuredTdeeSuppressionReason.WEIGHT_TREND_OUT_OF_RANGE,
            (outcome as MeasuredTdeeObservationOutcome.Suppressed).reason,
        )
        assertEquals("V1", MeasuredTdeeDisplayValidityPolicy.VERSION)
    }

    @Test
    fun `insufficient and invalid evidence cannot create a candidate`() {
        assertNull(factory.create(analysis(status = ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD)))
        assertNull(factory.create(analysis(confidence = null)))
        assertNull(factory.create(analysis(estimatedTdee = null)))
        assertNull(factory.create(analysis(estimatedTdee = BigDecimal.ZERO)))
        assertNull(factory.create(analysis(estimatedTdee = BigDecimal("-1"))))
        assertNull(factory.create(analysis(estimatedTdee = BigDecimal("4"))))
        assertNull(factory.create(analysis(estimatedTdee = BigDecimal("21474836470"))))
        assertNull(factory.create(analysis(loggedDays = -1)))
        assertNull(factory.create(analysis(weighInDays = -1)))
        assertNull(factory.create(analysis(weightSpanDays = -1)))
        assertNull(factory.create(analysis(windowStart = LocalDate.parse("2026-08-02"))))
        assertNull(factory.create(analysis(windowDays = 15)))
    }

    private fun analysis(
        status: ObservedEnergyEvidenceStatus = ObservedEnergyEvidenceStatus.SUFFICIENT,
        confidence: RecalibrationConfidence? = RecalibrationConfidence.MEDIUM,
        estimatedTdee: BigDecimal? = BigDecimal("2340"),
        windowDays: Int = 14,
        windowStart: LocalDate = LocalDate.parse("2026-07-19"),
        windowEnd: LocalDate = LocalDate.parse("2026-08-01"),
        loggedDays: Int = 10,
        weighInDays: Int = 5,
        weightSpanDays: Long = 12,
        observedKgPerWeek: BigDecimal? = BigDecimal.ZERO,
        trendRSquared: BigDecimal? = BigDecimal.ONE,
        trendStdErrorKgPerDay: BigDecimal? = BigDecimal.ZERO,
    ) = ObservedEnergyAnalysis(
        status = status,
        windowDays = windowDays,
        windowStart = windowStart,
        windowEnd = windowEnd,
        loggedDays = loggedDays,
        loggedDaysRequired = 7,
        recentLoggedDays = 5,
        weighInDays = weighInDays,
        weightSpanDays = weightSpanDays,
        averageLoggedCalories = BigDecimal("2340"),
        observedKgPerWeek = observedKgPerWeek,
        estimatedTdee = estimatedTdee,
        confidence = confidence,
        trendRSquared = trendRSquared,
        trendStdErrorKgPerDay = trendStdErrorKgPerDay,
    )
}
