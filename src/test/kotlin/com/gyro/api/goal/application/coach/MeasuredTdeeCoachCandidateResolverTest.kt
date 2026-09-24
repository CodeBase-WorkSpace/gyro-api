package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.MeasuredTdeeCandidateOutcome
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasuredTdeeCoachCandidateResolverTest {
    private val metrics = Mockito.mock(CoachInsightImpressionMetrics::class.java)
    private val resolver = MeasuredTdeeCoachCandidateResolver(MeasuredTdeeObservationFactory(), metrics)

    @Test
    fun `excluded state invokes neither entitlement nor evidence loading`() {
        var entitlementCalls = 0
        var analysisCalls = 0

        val candidate = resolver.resolve(
            state = NutritionCoachState.RECOMMENDATION,
            allowMeasuredTdee = true,
            entitlementProvider = { entitlementCalls++; true },
            analysisProvider = { analysisCalls++; analysis() },
        )

        assertNull(candidate)
        assertEquals(0, entitlementCalls)
        assertEquals(0, analysisCalls)
        Mockito.verify(metrics).stateCandidateOutcome(MeasuredTdeeCandidateOutcome.STATE_EXCLUDED)
    }

    @Test
    fun `missing entitlement is decided before evidence loading`() {
        var analysisCalls = 0

        val candidate = resolver.resolve(
            state = NutritionCoachState.INSIGHTS,
            allowMeasuredTdee = true,
            entitlementProvider = { false },
            analysisProvider = { analysisCalls++; analysis() },
        )

        assertNull(candidate)
        assertEquals(0, analysisCalls)
        Mockito.verify(metrics).stateCandidateOutcome(MeasuredTdeeCandidateOutcome.NOT_ENTITLED)
    }

    @Test
    fun `plausibility suppression records its versioned fixed category`() {
        val candidate = resolver.resolve(
            state = NutritionCoachState.INSIGHTS,
            allowMeasuredTdee = true,
            entitlementProvider = { true },
            analysisProvider = { analysis(observedKgPerWeek = BigDecimal("1.51")) },
        )

        assertNull(candidate)
        Mockito.verify(metrics).stateCandidateOutcome(MeasuredTdeeCandidateOutcome.INVALID_ESTIMATE)
        Mockito.verify(metrics).stateObservationSuppressed(
            DashboardInsightKind.MEASURED_TDEE,
            "V1_WEIGHT_TREND_OUT_OF_RANGE",
        )
    }

    private fun analysis(
        observedKgPerWeek: BigDecimal = BigDecimal.ZERO,
    ) = ObservedEnergyAnalysis(
        status = ObservedEnergyEvidenceStatus.SUFFICIENT,
        windowDays = 14,
        windowStart = LocalDate.parse("2026-07-19"),
        windowEnd = LocalDate.parse("2026-08-01"),
        loggedDays = 10,
        loggedDaysRequired = 7,
        recentLoggedDays = 5,
        weighInDays = 5,
        weightSpanDays = 12,
        averageLoggedCalories = BigDecimal("2340"),
        observedKgPerWeek = observedKgPerWeek,
        estimatedTdee = BigDecimal("2340"),
        confidence = RecalibrationConfidence.MEDIUM,
        trendRSquared = BigDecimal.ONE,
        trendStdErrorKgPerDay = BigDecimal.ZERO,
    )
}
