package com.gyro.api.diary.application

import com.gyro.api.diary.infrastructure.CoachInsightImpression
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachObservationFingerprintPolicyTest {
    @Test
    fun `calorie magnitude bands cover every boundary`() {
        assertEquals("NEAR", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("5")))
        assertEquals("MODERATE", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("5.000001")))
        assertEquals("MODERATE", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("15")))
        assertEquals("LARGE", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("15.000001")))
        assertEquals("LARGE", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("25")))
        assertEquals("VERY_LARGE", CoachObservationFingerprintPolicy.calorieBand(BigDecimal("25.000001")))
    }

    @Test
    fun `score and protein magnitude bands cover every boundary`() {
        assertEquals("SMALL", CoachObservationFingerprintPolicy.scoreTrendBand(5))
        assertEquals("MEDIUM", CoachObservationFingerprintPolicy.scoreTrendBand(6))
        assertEquals("MEDIUM", CoachObservationFingerprintPolicy.scoreTrendBand(10))
        assertEquals("LARGE", CoachObservationFingerprintPolicy.scoreTrendBand(11))

        assertEquals("LOW", CoachObservationFingerprintPolicy.proteinBand(49))
        assertEquals("DEVELOPING", CoachObservationFingerprintPolicy.proteinBand(50))
        assertEquals("DEVELOPING", CoachObservationFingerprintPolicy.proteinBand(74))
        assertEquals("CLOSE", CoachObservationFingerprintPolicy.proteinBand(75))
        assertEquals("CLOSE", CoachObservationFingerprintPolicy.proteinBand(89))
        assertEquals("TARGET", CoachObservationFingerprintPolicy.proteinBand(90))
    }

    @Test
    fun `rolling dates refresh fingerprints without implying a material change`() {
        val first = CoachObservationFingerprintPolicy.calorieComparison(
            LocalDate.parse("2026-07-22"),
            BigDecimal("-13"),
        )
        val rolled = CoachObservationFingerprintPolicy.calorieComparison(
            LocalDate.parse("2026-07-23"),
            BigDecimal("-14"),
        )
        val changed = CoachObservationFingerprintPolicy.calorieComparison(
            LocalDate.parse("2026-07-23"),
            BigDecimal("16"),
        )

        assertNotEquals(first, rolled)
        assertEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(rolled),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changed),
        )
    }

    @Test
    fun `measured TDEE fingerprints keep evidence dates exact but material meaning stable`() {
        val first = CoachObservationFingerprintPolicy.measuredTdee(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.MEDIUM,
            displayedValue = 2340,
        )
        val rolled = CoachObservationFingerprintPolicy.measuredTdee(
            evidenceEnd = LocalDate.parse("2026-08-02"),
            confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.MEDIUM,
            displayedValue = 2350,
        )
        val changedConfidence = CoachObservationFingerprintPolicy.measuredTdee(
            evidenceEnd = LocalDate.parse("2026-08-02"),
            confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.HIGH,
            displayedValue = 2340,
        )
        val changedBand = CoachObservationFingerprintPolicy.measuredTdee(
            evidenceEnd = LocalDate.parse("2026-08-02"),
            confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.MEDIUM,
            displayedValue = 2450,
        )

        assertEquals("OBS|MT|V1|2026-08-01|MEDIUM|B2300", first)
        assertEquals(DashboardInsightKind.MEASURED_TDEE, CoachObservationFingerprintPolicy.kindOf(first))
        assertEquals(DashboardInsightKind.MEASURED_TDEE, CoachObservationFingerprintPolicy.recordableKind(first))
        assertNotEquals(first, rolled)
        assertEquals("V1|MEDIUM|B2300", CoachObservationFingerprintPolicy.materialSignature(first))
        assertEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(rolled),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedConfidence),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedBand),
        )
    }

    @Test
    fun `weekend gap fingerprints rotate on the period end and change with direction or band`() {
        val first = CoachObservationFingerprintPolicy.weekendGap(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            direction = WeekendGapDirection.HIGHER,
            band = WeekendGapBand.MODERATE,
        )
        val rolled = CoachObservationFingerprintPolicy.weekendGap(
            evidenceEnd = LocalDate.parse("2026-08-02"),
            direction = WeekendGapDirection.HIGHER,
            band = WeekendGapBand.MODERATE,
        )
        val changedDirection = CoachObservationFingerprintPolicy.weekendGap(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            direction = WeekendGapDirection.LOWER,
            band = WeekendGapBand.MODERATE,
        )
        val changedBand = CoachObservationFingerprintPolicy.weekendGap(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            direction = WeekendGapDirection.HIGHER,
            band = WeekendGapBand.VERY_LARGE,
        )

        assertEquals("OBS|WG|V1|2026-08-01|HIGHER|MODERATE", first)
        assertEquals(DashboardInsightKind.WEEKEND_GAP, CoachObservationFingerprintPolicy.kindOf(first))
        assertEquals(DashboardInsightKind.WEEKEND_GAP, CoachObservationFingerprintPolicy.recordableKind(first))
        assertNotEquals(first, rolled)
        assertEquals("V1|HIGHER|MODERATE", CoachObservationFingerprintPolicy.materialSignature(first))
        assertEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(rolled),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedDirection),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedBand),
        )
    }

    @Test
    fun `goal forecast fingerprints rotate on the rolling dates and hold the saved deadline material`() {
        val first = goalForecastFingerprint()
        val rolledEvidence = goalForecastFingerprint(evidenceEnd = "2026-08-02")
        val rolledForecast = goalForecastFingerprint(forecastTargetDate = "2027-02-16")
        val movedDeadline = goalForecastFingerprint(originalTargetDate = "2027-02-01")
        val changedDelayBand = goalForecastFingerprint(delayBand = GoalForecastDelayBand.LATE_31_60)
        val changedProgressBand = goalForecastFingerprint(progressBand = GoalForecastProgressBand.P50_75)
        val unavailable = goalForecastFingerprint(
            forecastTargetDate = null,
            status = GoalForecastStatus.FLAT_TREND,
            delayBand = GoalForecastDelayBand.NONE,
        )

        assertEquals(
            "OBS|GF|V1|2026-08-01|2027-01-01|2027-02-14|AV|LATE_8_30|P25_50",
            first,
        )
        assertEquals("OBS|GF|V1|2026-08-01|2027-01-01|NONE|FT|NONE|P25_50", unavailable)
        listOf(first, unavailable).forEach { fingerprint ->
            assertEquals(DashboardInsightKind.GOAL_FORECAST, CoachObservationFingerprintPolicy.kindOf(fingerprint))
            assertEquals(
                DashboardInsightKind.GOAL_FORECAST,
                CoachObservationFingerprintPolicy.recordableKind(fingerprint),
            )
            assertTrue(fingerprint.length <= 80, "$fingerprint exceeds the impression key column")
        }

        assertEquals("V1|2027-01-01|AV|LATE_8_30|P25_50", CoachObservationFingerprintPolicy.materialSignature(first))
        // The rolling evidence and forecast dates rotate the identity without being material.
        listOf(rolledEvidence, rolledForecast).forEach { rolled ->
            assertNotEquals(first, rolled)
            assertEquals(
                CoachObservationFingerprintPolicy.materialSignature(first),
                CoachObservationFingerprintPolicy.materialSignature(rolled),
            )
        }
        listOf(movedDeadline, changedDelayBand, changedProgressBand, unavailable).forEach { changed ->
            assertNotEquals(
                CoachObservationFingerprintPolicy.materialSignature(first),
                CoachObservationFingerprintPolicy.materialSignature(changed),
            )
        }
    }

    @Test
    fun `a legacy kind-only goal forecast identity is never recordable`() {
        assertNull(CoachObservationFingerprintPolicy.recordableKind("GOAL_FORECAST"))
        assertNull(CoachObservationFingerprintPolicy.kindOf("OBS|GF|V1|2026-08-01|2027-01-01|NONE|AV|NONE"))
        assertNull(
            CoachObservationFingerprintPolicy.kindOf(
                "OBS|GF|V2|2026-08-01|2027-01-01|NONE|FT|NONE|P25_50",
            ),
        )
        assertNull(
            CoachObservationFingerprintPolicy.kindOf(
                "OBS|GF|V1|not-a-date|2027-01-01|NONE|FT|NONE|P25_50",
            ),
        )
        assertNull(
            CoachObservationFingerprintPolicy.kindOf(
                "OBS|GF|V1|2026-08-01|2027-01-01|NONE|XX|NONE|P25_50",
            ),
        )
    }

    @Test
    fun `trend explanation weight and intake bands cover every boundary`() {
        assertEquals(TrendExplanationWeightBand.W_SMALL, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("0.20")))
        assertEquals(TrendExplanationWeightBand.W_SMALL, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("0.399")))
        assertEquals(TrendExplanationWeightBand.W_MEDIUM, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("0.40")))
        assertEquals(TrendExplanationWeightBand.W_MEDIUM, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("0.749")))
        assertEquals(TrendExplanationWeightBand.W_LARGE, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("0.75")))
        assertEquals(TrendExplanationWeightBand.W_LARGE, CoachObservationFingerprintPolicy.trendExplanationWeightBand(BigDecimal("1.50")))

        assertEquals(TrendExplanationIntakeBand.I_10_15, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("10")))
        assertEquals(TrendExplanationIntakeBand.I_10_15, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("14.999")))
        assertEquals(TrendExplanationIntakeBand.I_15_25, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("15")))
        assertEquals(TrendExplanationIntakeBand.I_15_25, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("24.999")))
        assertEquals(TrendExplanationIntakeBand.I_25_40, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("25")))
        assertEquals(TrendExplanationIntakeBand.I_25_40, CoachObservationFingerprintPolicy.trendExplanationIntakeBand(BigDecimal("40")))
    }

    @Test
    fun `trend explanation fingerprints rotate on the period end while both bands stay material`() {
        val first = CoachObservationFingerprintPolicy.trendExplanation(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            weightBand = TrendExplanationWeightBand.W_MEDIUM,
            intakeBand = TrendExplanationIntakeBand.I_15_25,
        )
        val rolled = CoachObservationFingerprintPolicy.trendExplanation(
            evidenceEnd = LocalDate.parse("2026-08-02"),
            weightBand = TrendExplanationWeightBand.W_MEDIUM,
            intakeBand = TrendExplanationIntakeBand.I_15_25,
        )
        val changedWeightBand = CoachObservationFingerprintPolicy.trendExplanation(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            weightBand = TrendExplanationWeightBand.W_LARGE,
            intakeBand = TrendExplanationIntakeBand.I_15_25,
        )
        val changedIntakeBand = CoachObservationFingerprintPolicy.trendExplanation(
            evidenceEnd = LocalDate.parse("2026-08-01"),
            weightBand = TrendExplanationWeightBand.W_MEDIUM,
            intakeBand = TrendExplanationIntakeBand.I_25_40,
        )

        assertEquals("OBS|TX|V1|2026-08-01|W_MEDIUM|I_15_25", first)
        assertEquals(DashboardInsightKind.TREND_EXPLANATION, CoachObservationFingerprintPolicy.kindOf(first))
        assertEquals(DashboardInsightKind.TREND_EXPLANATION, CoachObservationFingerprintPolicy.recordableKind(first))
        assertNotEquals(first, rolled)
        assertEquals("V1|W_MEDIUM|I_15_25", CoachObservationFingerprintPolicy.materialSignature(first))
        assertEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(rolled),
        )
        // Either magnitude band changing counts as material.
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedWeightBand),
        )
        assertNotEquals(
            CoachObservationFingerprintPolicy.materialSignature(first),
            CoachObservationFingerprintPolicy.materialSignature(changedIntakeBand),
        )
    }

    @Test
    fun `trend explanation is a generated-only kind`() {
        // The bare kind name is never a recordable identity.
        assertNull(CoachObservationFingerprintPolicy.recordableKind("TREND_EXPLANATION"))
        assertEquals(
            DashboardInsightKind.TREND_EXPLANATION,
            CoachObservationFingerprintPolicy.recordableKind("OBS|TX|V1|2026-07-22|W_SMALL|I_10_15"),
        )
    }

    @Test
    fun `trend explanation observation ranks against other kinds by actionability`() {
        val today = LocalDate.parse("2026-07-23")
        val trend = candidate(
            DashboardInsightKind.TREND_EXPLANATION,
            "OBS|TX|V1|2026-07-22|W_MEDIUM|I_15_25",
            magnitude = 0.6,
        )
        val bestDay = candidate(
            DashboardInsightKind.BEST_DAY,
            "OBS|BD|2026-07-22|2026-07-21|80",
            magnitude = 0.6,
        )

        // Equal magnitude and novelty: the 0.80 actionability weight outranks BEST_DAY's 0.7.
        val ranked = rank(listOf(bestDay, trend), emptyList(), today)
        assertEquals(DashboardInsightKind.TREND_EXPLANATION, ranked.first().insight.kind)
        // The two-observation cap is unchanged.
        assertEquals(2, ranked.size)
    }

    @Test
    fun `logging streak fingerprints advance only at milestones`() {
        assertEquals("OBS|LS|M1", CoachObservationFingerprintPolicy.loggingStreak(2))
        assertEquals("OBS|LS|M3", CoachObservationFingerprintPolicy.loggingStreak(6))
        assertEquals("OBS|LS|M7", CoachObservationFingerprintPolicy.loggingStreak(13))
        assertEquals("OBS|LS|M14", CoachObservationFingerprintPolicy.loggingStreak(14))
    }

    @Test
    fun `write validation recognizes generated and rolling-deployment identities`() {
        assertEquals(
            DashboardInsightKind.CALORIE_ADHERENCE,
            CoachObservationFingerprintPolicy.recordableKind("OBS|CA|2026-07-22|DOWN|MODERATE"),
        )
        assertEquals(
            DashboardInsightKind.BEST_DAY,
            CoachObservationFingerprintPolicy.recordableKind("OBS|BD|2026-07-22|2026-07-21|80"),
        )
        assertEquals(
            DashboardInsightKind.SCORE_TREND,
            CoachObservationFingerprintPolicy.recordableKind("OBS|ST|2026-07-22|UP|MEDIUM"),
        )
        assertEquals(
            DashboardInsightKind.PROTEIN_CONSISTENCY,
            CoachObservationFingerprintPolicy.recordableKind("OBS|PC|2026-07-22|TARGET|UNKNOWN"),
        )
        assertEquals(
            DashboardInsightKind.LOGGING_STREAK,
            CoachObservationFingerprintPolicy.recordableKind("OBS|LS|M14"),
        )
        assertEquals(
            DashboardInsightKind.COACH_TIP,
            CoachObservationFingerprintPolicy.recordableKind("COACH_TIP_39"),
        )
        assertEquals(
            DashboardInsightKind.CALORIE_ADHERENCE,
            CoachObservationFingerprintPolicy.recordableKind("CALORIE_ADHERENCE"),
        )
    }

    @Test
    fun `write validation rejects malformed and unknown identities`() {
        listOf(
            "",
            "COACH_TIP_40",
            "COACH_TIP",
            "OBS|CA|not-a-date|DOWN|MODERATE",
            "OBS|BD|2026-07-22|2026-07-21|101",
            "OBS|ST|2026-07-22|STABLE|SMALL",
            "OBS|PC|2026-07-22|UNKNOWN|UP",
            "OBS|LS|M2",
            "OBS|XX|2026-07-22|UP|LARGE",
            "OBS|MT|V1|not-a-date|MEDIUM|B2300",
            "OBS|MT|V2|2026-07-22|MEDIUM|B2300",
            "OBS|MT|V1|2026-07-22|UNKNOWN|B2300",
            "OBS|MT|V1|2026-07-22|MEDIUM|B2350",
            "OBS|MT|V1|2026-07-22|MEDIUM|B-100",
            "OBS|MT|V1|2026-07-22|MEDIUM|B02300",
            "MEASURED_TDEE",
            "OBS|WG|V1|not-a-date|HIGHER|MODERATE",
            "OBS|WG|V2|2026-07-22|HIGHER|MODERATE",
            "OBS|WG|V1|2026-07-22|UP|MODERATE",
            "OBS|WG|V1|2026-07-22|HIGHER|SMALL",
            "OBS|WG|V1|2026-07-22|HIGHER",
            "OBS|WG|V1|2026-07-22|HIGHER|MODERATE|EXTRA",
            "WEEKEND_GAP",
            "OBS|TX|V1|not-a-date|W_MEDIUM|I_15_25",
            "OBS|TX|V2|2026-07-22|W_MEDIUM|I_15_25",
            "OBS|TX|V1|2026-07-22|W_HUGE|I_15_25",
            "OBS|TX|V1|2026-07-22|W_MEDIUM|I_40_60",
            "OBS|TX|V1|2026-07-22|W_MEDIUM",
            "OBS|TX|V1|2026-07-22|W_MEDIUM|I_15_25|EXTRA",
            "TREND_EXPLANATION",
        ).forEach { assertNull(CoachObservationFingerprintPolicy.recordableKind(it), it) }
    }

    @Test
    fun `same day impressions stay pinned while recent kinds rotate`() {
        val today = LocalDate.parse("2026-07-23")
        val pinnedBestDay = candidate(
            DashboardInsightKind.BEST_DAY,
            "OBS|BD|2026-07-22|2026-07-21|80",
            magnitude = 0.4,
        )
        val calorie = candidate(
            DashboardInsightKind.CALORIE_ADHERENCE,
            "OBS|CA|2026-07-22|DOWN|MODERATE",
            magnitude = 1.0,
        )

        val ranked = rank(
            listOf(calorie, pinnedBestDay),
            listOf(CoachInsightImpression(pinnedBestDay.insight.impressionId, today)),
            today,
        )

        assertEquals(DashboardInsightKind.BEST_DAY, ranked.first().insight.kind)
    }

    @Test
    fun `another unseen kind wins a cooldown but a material change can interrupt it`() {
        val today = LocalDate.parse("2026-07-23")
        val recent = CoachInsightImpression(
            "OBS|CA|2026-07-21|DOWN|MODERATE",
            today.minusDays(1),
        )
        val sameMeaning = candidate(
            DashboardInsightKind.CALORIE_ADHERENCE,
            "OBS|CA|2026-07-22|DOWN|MODERATE",
            magnitude = 1.0,
        )
        val changed = candidate(
            DashboardInsightKind.CALORIE_ADHERENCE,
            "OBS|CA|2026-07-22|UP|LARGE",
            magnitude = 1.0,
        )
        val unseenBest = candidate(
            DashboardInsightKind.BEST_DAY,
            "OBS|BD|2026-07-22|2026-07-22|75",
            magnitude = 0.4,
        )

        assertEquals(
            DashboardInsightKind.BEST_DAY,
            rank(listOf(sameMeaning, unseenBest), listOf(recent), today).first().insight.kind,
        )
        assertEquals(
            DashboardInsightKind.CALORIE_ADHERENCE,
            rank(listOf(changed, unseenBest), listOf(recent), today).first().insight.kind,
        )
    }

    @Test
    fun `a repeated fingerprint remains renderable`() {
        val today = LocalDate.parse("2026-07-23")
        val repeated = candidate(
            DashboardInsightKind.PROTEIN_CONSISTENCY,
            "OBS|PC|2026-07-22|TARGET|STABLE",
            magnitude = 0.7,
        )

        val ranked = rank(
            listOf(repeated),
            listOf(CoachInsightImpression(repeated.insight.impressionId, today.minusDays(3))),
            today,
        )

        assertEquals(listOf(repeated), ranked)
    }

    private fun goalForecastFingerprint(
        evidenceEnd: String = "2026-08-01",
        originalTargetDate: String = "2027-01-01",
        forecastTargetDate: String? = "2027-02-14",
        status: GoalForecastStatus = GoalForecastStatus.AVAILABLE,
        delayBand: GoalForecastDelayBand = GoalForecastDelayBand.LATE_8_30,
        progressBand: GoalForecastProgressBand = GoalForecastProgressBand.P25_50,
    ) = CoachObservationFingerprintPolicy.goalForecast(
        evidenceEnd = LocalDate.parse(evidenceEnd),
        originalTargetDate = LocalDate.parse(originalTargetDate),
        forecastTargetDate = forecastTargetDate?.let(LocalDate::parse),
        status = status,
        delayBand = delayBand,
        progressBand = progressBand,
    )

    private fun goalForecast() = GoalForecast(
        status = GoalForecastStatus.AVAILABLE,
        originalTargetDate = LocalDate.parse("2027-01-01"),
        forecastTargetDate = LocalDate.parse("2027-02-14"),
        delayDays = 44,
        startWeightKg = BigDecimal("90.000"),
        targetWeightKg = BigDecimal("80.000"),
        fittedWeightKg = BigDecimal("86.000"),
        observedKgPerWeek = BigDecimal("-0.420"),
        progressPercent = BigDecimal("40.00"),
        evidenceStart = LocalDate.parse("2026-07-05"),
        evidenceEnd = LocalDate.parse("2026-08-01"),
        weighInDayCount = 12,
        weightSpanDays = 27,
        milestones = listOf(25, 50, 75, 100).mapIndexed { index, progressPercent ->
            GoalForecastMilestone(
                progressPercent = progressPercent,
                targetWeightKg = BigDecimal("90.000")
                    .subtract(BigDecimal("2.500").multiply(BigDecimal(index + 1))),
                plannedDate = LocalDate.parse("2026-10-01").plusMonths(index.toLong()),
                forecastDate = if (index == 0) null else LocalDate.parse("2026-11-14").plusMonths(index.toLong()),
                state = when (index) {
                    0 -> GoalForecastMilestoneState.REACHED
                    1 -> GoalForecastMilestoneState.NEXT
                    else -> GoalForecastMilestoneState.UPCOMING
                },
            )
        },
    )

    private fun candidate(
        kind: DashboardInsightKind,
        fingerprint: String,
        magnitude: Double,
    ): DashboardInsightCandidate<DashboardInsight> {
        val start = LocalDate.parse("2026-07-16")
        val end = LocalDate.parse("2026-07-22")
        val insight: DashboardInsight = when (kind) {
            DashboardInsightKind.CALORIE_ADHERENCE -> CalorieAdherenceInsight(
                fingerprint, 1, 1800, 2000, BigDecimal("-10"), 5, start, end,
            )
            DashboardInsightKind.SCORE_TREND -> ScoreTrendInsight(
                fingerprint, 1, DashboardInsightTrend.UP, 5, 5, start, end,
            )
            DashboardInsightKind.BEST_DAY -> BestDayInsight(fingerprint, 1, end, 5, start, end)
            DashboardInsightKind.PROTEIN_CONSISTENCY -> ProteinConsistencyInsight(
                fingerprint, 1, DashboardInsightTrend.STABLE, 5, start, end,
            )
            DashboardInsightKind.LOGGING_STREAK -> LoggingStreakInsight(fingerprint, 1, false)
            DashboardInsightKind.COACH_TIP -> CoachTipInsight(fingerprint, 1)
            DashboardInsightKind.MEASURED_TDEE -> MeasuredTdeeInsight(
                fingerprint, 2300, RecalibrationConfidence.MEDIUM, "OLS_7700_V1", "V1",
                14, 10, 4, 10, start.minusDays(7), end,
            )
            DashboardInsightKind.WEEKEND_GAP -> WeekendGapInsight(
                fingerprint, 10, BigDecimal.TEN, BigDecimal.ZERO, 4, 10, 14, 14,
                start.minusDays(7), end,
            )
            DashboardInsightKind.TREND_EXPLANATION -> TrendExplanationInsight(
                fingerprint, -10, BigDecimal("-10"), BigDecimal("0.5"), 1800, 2000,
                10, 4, 10, 14, start.minusDays(7), end, RecalibrationConfidence.MEDIUM,
            )
            DashboardInsightKind.GOAL_FORECAST -> GoalForecastInsight(
                fingerprint, 40, goalForecast(),
            )
        }
        return DashboardInsightCandidate(insight, magnitude)
    }

    private fun rank(
        candidates: List<DashboardInsightCandidate<DashboardInsight>>,
        impressions: List<CoachInsightImpression>,
        today: LocalDate,
    ) = ObservationSaliencePolicy.rank(
        candidates,
        impressions,
        today,
    ).map(ObservationSelection::candidate)
}
