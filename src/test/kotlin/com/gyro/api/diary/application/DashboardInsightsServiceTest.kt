package com.gyro.api.diary.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.daily_score.application.DailyScoreAnalyticsService
import com.gyro.api.daily_score.application.DailyScoreAnalyticsSummary
import com.gyro.api.daily_score.application.DailyScoreBand
import com.gyro.api.daily_score.application.DailyScoreBreakdown
import com.gyro.api.daily_score.application.DailyScoreCoachAnalytics
import com.gyro.api.daily_score.application.DailyScoreCalorieTargetComparison
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.daily_score.application.DailyScorePeriodAverage
import com.gyro.api.daily_score.application.DailyScoreReadModel
import com.gyro.api.diary.infrastructure.DiaryNutritionTotals
import com.gyro.api.diary.infrastructure.DiaryRepository
import com.gyro.api.diary.infrastructure.CoachInsightImpressionRepository
import com.gyro.api.diary.infrastructure.CoachInsightImpression
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTarget
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.goal.application.nutrition_plan.PlanScheduleSummaryReadModel
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.user.application.UserTimezoneResolver
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DashboardInsightsServiceTest {
    private val userId = UUID.randomUUID()
    private val today = LocalDate.parse("2026-07-23")
    private val diary = Mockito.mock(DiaryRepository::class.java)
    private val targets = Mockito.mock(ScheduleAwareDailyTargetLoader::class.java)
    private val timezoneResolver = Mockito.mock(UserTimezoneResolver::class.java)
    private val time = Mockito.mock(TimeProvider::class.java)
    private val scoreAnalytics = Mockito.mock(DailyScoreAnalyticsService::class.java)
    private val impressions = Mockito.mock(CoachInsightImpressionRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val impressionMetrics = CoachInsightImpressionMetrics(meterRegistry)

    init {
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(null, null))
        Mockito.`when`(impressions.shownSince(userId, today.minusDays(6)))
            .thenReturn(emptyList())
    }

    @Test
    fun `protein consistency treats exactly ninety percent as successful and streak ends today`() {
        val current = dates("2026-07-16")
        val previous = dates("2026-07-09")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(time.today(ZoneId.of("Asia/Tehran"))).thenReturn(today)
        Mockito.`when`(
            diary.loggedDatesBetween(userId, today.minusDays(366), today)
        ).thenReturn(setOf(today, today.minusDays(1)))
        Mockito.`when`(diary.nutritionTotalsByDate(userId, previous.first(), current.last())).thenReturn(current.associateWith { DiaryNutritionTotals(true, BigDecimal("90")) })
        Mockito.`when`(targets.load(userId, previous + current)).thenReturn(current.associateWith(::scheduledTarget))

        val result = service().insightsFor(userId)

        assertEquals(2, result.first { it.kind == DashboardInsightKind.LOGGING_STREAK }.value)
        val protein = assertIs<ProteinConsistencyInsight>(
            result.first { it.kind == DashboardInsightKind.PROTEIN_CONSISTENCY },
        )
        assertEquals(100, protein.value)
        assertEquals(null, protein.trend)
        assertEquals(DashboardInsightBasis.LOGGED_DAYS, protein.basis)
    }

    @Test
    fun `logging streak is bounded and reports when the display cap is exceeded`() {
        val zone = ZoneId.of("Asia/Tehran")
        val loggedDates = (0L..366L).map(today::minusDays).toSet()
        val previous = dates("2026-07-09")
        val current = dates("2026-07-16")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(
            diary.loggedDatesBetween(userId, today.minusDays(366), today)
        ).thenReturn(loggedDates)
        Mockito.`when`(
            diary.nutritionTotalsByDate(userId, previous.first(), current.last())
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, previous + current)).thenReturn(emptyMap())

        val streak = assertIs<LoggingStreakInsight>(service().insightsFor(userId).single())

        assertEquals(365, streak.value)
        assertEquals(true, streak.capped)
    }

    @Test
    fun `daily score observations disclose eligible days in rolling windows`() {
        val current = analyticsSummary(
            from = "2026-07-16",
            to = "2026-07-22",
            average = "82",
            calorie = "91",
            formulaVersion = "v2",
            best = score("2026-07-21", 94, "v2"),
            scoreCount = 2,
        )
        val previous = analyticsSummary(
            from = "2026-07-11",
            to = "2026-07-17",
            average = "72",
            calorie = "80",
            formulaVersion = "v2",
            best = score("2026-07-15", 88, "v2"),
            scoreCount = 2,
        )
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(time.today(ZoneId.of("Asia/Tehran"))).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(
            diary.nutritionTotalsByDate(userId, LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-22"))
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, dates("2026-07-09") + dates("2026-07-16"))).thenReturn(emptyMap())
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(current, previous, calorieComparison()))

        val observations = service().insightsFor(userId)

        assertEquals(
            listOf(DashboardInsightKind.CALORIE_ADHERENCE, DashboardInsightKind.BEST_DAY),
            observations.map(DashboardInsight::kind),
        )
        assertEquals("OBS|CA|2026-07-22|DOWN|MODERATE", observations.first().impressionId)
        assertEquals("OBS|BD|2026-07-22|2026-07-21|94", observations.last().impressionId)
        val bestDay = assertIs<BestDayInsight>(observations.last())
        assertEquals(LocalDate.parse("2026-07-21"), bestDay.date)
        val calorie = assertIs<CalorieAdherenceInsight>(observations.first())
        assertEquals(DashboardInsightBasis.TARGET_COMPARISON, calorie.basis)
        assertEquals(-10, calorie.value)
        assertEquals(BigDecimal("-10.000000"), calorie.deltaPercent)
        assertEquals(1800, calorie.averageIntakeCalories)
        assertEquals(2000, calorie.averageTargetCalories)
        assertEquals(2, calorie.loggedDayCount)
        assertEquals(LocalDate.parse("2026-07-16"), calorie.periodStart)
        assertEquals(LocalDate.parse("2026-07-22"), calorie.periodEnd)

        val recommendationObservations = service().insightsFor(
            userId,
            ZoneId.of("Asia/Tehran"),
            excludedKinds = setOf(DashboardInsightKind.CALORIE_ADHERENCE),
        )
        assertEquals(
            listOf(DashboardInsightKind.BEST_DAY, DashboardInsightKind.SCORE_TREND),
            recommendationObservations.map(DashboardInsight::kind),
        )
    }

    @Test
    fun `supplemental candidates share exclusion ranking and observation limits`() {
        val zone = ZoneId.of("Asia/Tehran")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(null, null))

        val candidate = DashboardInsightCandidate(
            insight = MeasuredTdeeInsight(
                impressionId = "OBS|MT|V1|2026-07-22|MEDIUM|B2300",
                value = 2340,
                confidence = RecalibrationConfidence.MEDIUM,
                estimatorVersion = "OLS_7700_V1",
                displayPolicyVersion = "V1",
                windowDays = 14,
                loggedDayCount = 10,
                weighInDayCount = 4,
                weightSpanDays = 10,
                periodStart = LocalDate.parse("2026-07-09"),
                periodEnd = LocalDate.parse("2026-07-22"),
            ),
            magnitude = 0.80,
        )

        val observations = service().insightsFor(
            userId = userId,
            zone = zone,
            excludedKinds = emptySet(),
            asOfDate = null,
            supplementalCandidates = listOf(candidate),
        )

        assertTrue(observations.size <= 2)
        assertTrue(observations.any { it.kind == DashboardInsightKind.MEASURED_TDEE })
        assertTrue(
            service().insightsFor(
                userId = userId,
                zone = zone,
                excludedKinds = setOf(DashboardInsightKind.MEASURED_TDEE),
                asOfDate = null,
                supplementalCandidates = listOf(candidate),
            ).none { it.kind == DashboardInsightKind.MEASURED_TDEE },
        )
    }

    @Test
    fun `an unreported second observation stays eligible on the next ranking`() {
        val zone = ZoneId.of("Asia/Tehran")
        Mockito.`when`(time.today(zone)).thenReturn(today)
        val current = analyticsSummary(
            from = "2026-07-16",
            to = "2026-07-22",
            average = "82",
            calorie = "91",
            formulaVersion = "v2",
            best = score("2026-07-21", 94, "v2"),
            scoreCount = 2,
        )
        val previous = analyticsSummary(
            from = "2026-07-11",
            to = "2026-07-17",
            average = "72",
            calorie = "80",
            formulaVersion = "v2",
            best = score("2026-07-15", 88, "v2"),
            scoreCount = 2,
        )
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today))
            .thenReturn(emptySet())
        Mockito.`when`(
            diary.nutritionTotalsByDate(
                userId,
                LocalDate.parse("2026-07-09"),
                LocalDate.parse("2026-07-22"),
            )
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, dates("2026-07-09") + dates("2026-07-16")))
            .thenReturn(emptyMap())
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(current, previous, calorieComparison()))
        Mockito.`when`(impressions.shownSince(userId, today.minusDays(6)))
            .thenReturn(
                listOf(
                    CoachInsightImpression(
                        "OBS|BD|2026-07-22|2026-07-21|94",
                        today.minusDays(1),
                    )
                )
            )

        val observations = service().insightsFor(userId, zone)

        assertEquals("OBS|CA|2026-07-22|DOWN|MODERATE", observations.first().impressionId)
    }

    @Test
    fun `impression lookup failure keeps observations available and increments a metric`() {
        val zone = ZoneId.of("Asia/Tehran")
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(impressions.shownSince(userId, today.minusDays(6)))
            .thenThrow(IllegalStateException("database unavailable"))
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today))
            .thenReturn(setOf(today))
        Mockito.`when`(
            diary.nutritionTotalsByDate(
                userId,
                LocalDate.parse("2026-07-09"),
                LocalDate.parse("2026-07-22"),
            )
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, dates("2026-07-09") + dates("2026-07-16")))
            .thenReturn(emptyMap())

        val observations = service().insightsFor(userId, zone)

        assertTrue(observations.isNotEmpty())
        assertEquals(
            1.0,
            meterRegistry.get(CoachInsightImpressionMetrics.FAILURE_METRIC)
                .tag("operation", CoachInsightImpressionMetrics.OPERATION_LOOKUP)
                .counter()
                .count(),
        )
    }

    @Test
    fun `score trend is not compared across formula versions`() {
        val current = analyticsSummary(
            from = "2026-07-16",
            to = "2026-07-22",
            average = "82",
            calorie = null,
            formulaVersion = "v2",
            best = score("2026-07-21", 94, "v2"),
            scoreCount = 2,
        )
        val previous = analyticsSummary(
            from = "2026-07-11",
            to = "2026-07-17",
            average = "62",
            calorie = null,
            formulaVersion = "v1",
            best = score("2026-07-15", 88, "v1"),
            scoreCount = 2,
        )
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(time.today(ZoneId.of("Asia/Tehran"))).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(
            diary.nutritionTotalsByDate(userId, LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-22"))
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, dates("2026-07-09") + dates("2026-07-16"))).thenReturn(emptyMap())
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(current, previous))

        val observations = service().insightsFor(userId)

        assertEquals(false, observations.any { it.kind == DashboardInsightKind.SCORE_TREND })
    }

    @Test
    fun `raw calorie comparison remains eligible across score formula versions`() {
        val current = analyticsSummary(
            from = "2026-07-16",
            to = "2026-07-22",
            average = "82",
            calorie = null,
            formulaVersion = "v2",
            best = score("2026-07-21", 94, "v2"),
            scoreCount = 2,
        ).copy(formulaVersions = mapOf("v1" to 1, "v2" to 1))
        val previous = analyticsSummary(
            from = "2026-07-11",
            to = "2026-07-17",
            average = "62",
            calorie = null,
            formulaVersion = "v1",
            best = score("2026-07-15", 88, "v1"),
            scoreCount = 2,
        )
        val zone = ZoneId.of("Asia/Tehran")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(
            diary.nutritionTotalsByDate(userId, LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-22"))
        ).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, dates("2026-07-09") + dates("2026-07-16"))).thenReturn(emptyMap())
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today))
            .thenReturn(DailyScoreCoachAnalytics(current, previous, calorieComparison("-13.333333")))

        val observations = service().insightsFor(userId)

        assertTrue(observations.any { it.kind == DashboardInsightKind.CALORIE_ADHERENCE })
        assertEquals(false, observations.any { it.kind == DashboardInsightKind.SCORE_TREND })
        assertEquals(false, observations.any { it.kind == DashboardInsightKind.BEST_DAY })
    }

    @Test
    fun `weekend gap reuses the shared rolling evidence and reports target relative groups`() {
        val zone = ZoneId.of("Asia/Tehran")
        stubWeekendGapWindow(zone)

        val observations = service().insightsFor(userId)

        val weekendGap = assertIs<WeekendGapInsight>(
            observations.first { it.kind == DashboardInsightKind.WEEKEND_GAP },
        )
        assertEquals("OBS|WG|V1|2026-07-22|HIGHER|LARGE", weekendGap.impressionId)
        assertEquals(20, weekendGap.value)
        assertEquals(DashboardInsightBasis.TARGET_COMPARISON, weekendGap.basis)
        assertEquals(BigDecimal("20.000000"), weekendGap.weekendTargetDeltaPercent)
        assertEquals(BigDecimal("0.000000"), weekendGap.weekdayTargetDeltaPercent)
        assertEquals(4, weekendGap.weekendLoggedDayCount)
        assertEquals(10, weekendGap.weekdayLoggedDayCount)
        assertEquals(14, weekendGap.loggedDayCount)
        assertEquals(14, weekendGap.windowDays)
        assertEquals(LocalDate.parse("2026-07-09"), weekendGap.periodStart)
        assertEquals(LocalDate.parse("2026-07-22"), weekendGap.periodEnd)

        // One diary-totals read and one target read serve both observations.
        Mockito.verify(diary, Mockito.times(1))
            .nutritionTotalsByDate(userId, LocalDate.parse("2026-07-09"), LocalDate.parse("2026-07-22"))
        Mockito.verify(targets, Mockito.times(1))
            .load(userId, dates("2026-07-09") + dates("2026-07-16"))
    }

    @Test
    fun `weekend gap ranks below calorie adherence and above protein consistency`() {
        val zone = ZoneId.of("Asia/Tehran")
        stubWeekendGapWindow(zone)
        Mockito.`when`(scoreAnalytics.coachAnalytics(userId, today)).thenReturn(
            DailyScoreCoachAnalytics(
                analyticsSummary(
                    from = "2026-07-16",
                    to = "2026-07-22",
                    average = "82",
                    calorie = null,
                    formulaVersion = "v2",
                    best = score("2026-07-21", 94, "v2"),
                    scoreCount = 2,
                ).copy(formulaVersions = mapOf("v1" to 1, "v2" to 1)),
                null,
                calorieComparison(),
            )
        )

        val observations = service().insightsFor(userId)

        assertEquals(
            listOf(DashboardInsightKind.CALORIE_ADHERENCE, DashboardInsightKind.WEEKEND_GAP),
            observations.map(DashboardInsight::kind),
        )
    }

    @Test
    fun `a suppressed weekend gap is counted with a low cardinality reason`() {
        val zone = ZoneId.of("Asia/Tehran")
        val window = dates("2026-07-09") + dates("2026-07-16")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(diary.nutritionTotalsByDate(userId, window.first(), window.last())).thenReturn(emptyMap())
        Mockito.`when`(targets.load(userId, window)).thenReturn(emptyMap())

        service().insightsFor(userId)

        assertEquals(
            1.0,
            meterRegistry.get(CoachInsightImpressionMetrics.STATE_SUPPRESSED_METRIC)
                .tag("kind", DashboardInsightKind.WEEKEND_GAP.name)
                .tag("reason", WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS.name)
                .counter()
                .count(),
        )
    }

    private fun stubWeekendGapWindow(zone: ZoneId) {
        val window = dates("2026-07-09") + dates("2026-07-16")
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(time.today(zone)).thenReturn(today)
        Mockito.`when`(diary.loggedDatesBetween(userId, today.minusDays(366), today)).thenReturn(emptySet())
        Mockito.`when`(
            diary.nutritionTotalsByDate(userId, window.first(), window.last())
        ).thenReturn(
            window.associateWith { date ->
                val weekend = date.dayOfWeek == java.time.DayOfWeek.THURSDAY ||
                    date.dayOfWeek == java.time.DayOfWeek.FRIDAY
                DiaryNutritionTotals(
                    logged = true,
                    protein = BigDecimal("90"),
                    calories = if (weekend) BigDecimal("2400") else BigDecimal("2000"),
                )
            }
        )
        Mockito.`when`(targets.load(userId, window)).thenReturn(window.associateWith(::scheduledTarget))
    }

    private fun dates(start: String) = (0L..6L).map { LocalDate.parse(start).plusDays(it) }

    private fun calorieComparison(
        deltaPercent: String = "-10.000000",
    ) = DailyScoreCalorieTargetComparison(
        averageIntakeCalories = BigDecimal("1800.000000"),
        averageTargetCalories = BigDecimal("2000.000000"),
        deltaPercent = BigDecimal(deltaPercent),
        loggedDayCount = 2,
        periodStart = LocalDate.parse("2026-07-16"),
        periodEnd = LocalDate.parse("2026-07-22"),
    )

    private fun analyticsSummary(
        from: String,
        to: String,
        average: String,
        calorie: String?,
        formulaVersion: String,
        best: DailyScoreReadModel,
        scoreCount: Int,
    ) = DailyScoreAnalyticsSummary(
        from = LocalDate.parse(from),
        to = LocalDate.parse(to),
        scoreCount = scoreCount,
        loggedDayCount = scoreCount,
        averageScore = BigDecimal(average),
        averageCalorieScore = calorie?.let(::BigDecimal),
        averageProteinScore = null,
        averageLoggingConsistencyScore = BigDecimal("90"),
        formulaVersions = mapOf(formulaVersion to scoreCount),
        distribution = DailyScoreBand.entries.associateWith { 0 },
        weeklyAverages = listOf(
            DailyScorePeriodAverage(LocalDate.parse(from), LocalDate.parse(to), scoreCount, BigDecimal(average))
        ),
        monthlyAverages = emptyList(),
        bestDays = listOf(best),
        worstDays = listOf(best),
    )

    private fun score(date: String, value: Int, formulaVersion: String) = DailyScoreReadModel(
        id = UUID.randomUUID(),
        userId = userId,
        localDate = LocalDate.parse(date),
        score = value,
        mode = DailyScoreMode.GOAL_ADHERENCE,
        goalId = UUID.randomUUID(),
        goalType = null,
        formulaName = "nutrition_daily_score",
        formulaVersion = formulaVersion,
        breakdown = DailyScoreBreakdown(
            formulaName = "nutrition_daily_score",
            formulaVersion = formulaVersion,
            componentWeights = emptyMap(),
            calorieScore = 90,
            proteinScore = 90,
            fatScore = 90,
            carbohydrateScore = 90,
            loggingCompletenessScore = 90,
            loggedMealCount = 2,
            loggedMealTypes = listOf("BREAKFAST", "LUNCH"),
            missingMajorMeals = listOf("DINNER"),
            totalCalories = BigDecimal("1800"),
            targetCalories = BigDecimal("2000"),
            calorieDelta = BigDecimal("-200"),
            totalProtein = BigDecimal("90"),
            targetProtein = BigDecimal("100"),
            proteinDelta = BigDecimal("-10"),
            totalFat = BigDecimal("60"),
            targetFat = BigDecimal("70"),
            fatDelta = BigDecimal("-10"),
            totalCarbohydrates = BigDecimal("180"),
            targetCarbohydrates = BigDecimal("200"),
            carbohydrateDelta = BigDecimal("-20"),
        ),
        finalizedAt = Instant.parse("2026-07-23T00:00:00Z"),
        createdAt = Instant.parse("2026-07-23T00:00:00Z"),
    )
    private fun service() = DashboardInsightsService(
        diary,
        targets,
        scoreAnalytics,
        impressions,
        impressionMetrics,
        timezoneResolver,
        time,
        WeekendGapObservationFactory(),
    )
    private fun scheduledTarget(date: LocalDate) = ScheduleAwareDailyTarget(
        UUID.randomUUID(), null,
        DailyTargetReadModel(PlanScheduleSummaryReadModel(com.gyro.api.goal.domain.GoalScheduleType.FLAT, date, DailyTargetSource.BASE_PLAN),
            NutritionTargetsReadModel(BigDecimal("2000"), BigDecimal("100"), BigDecimal("200"), BigDecimal("70"), null)),
    )
}
