package com.gyro.api.daily_score.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyScoreAnalyticsServiceTest {
    private val userId = UUID.randomUUID()
    private val today = LocalDate.parse("2026-07-23")
    private val dailyScores = Mockito.mock(DailyScoreService::class.java)

    @Test
    fun `coach calorie comparison uses sums and historical targets at scale six`() {
        Mockito.`when`(
            dailyScores.finalizedScoresForRange(
                userId,
                LocalDate.parse("2026-07-09"),
                LocalDate.parse("2026-07-22"),
            )
        ).thenReturn(
            listOf(
                score("2026-07-15", "1500", "1500", loggedMeals = 1),
                score("2026-07-18", "1200", "1400", loggedMeals = 2),
                score("2026-07-19", "1400", "1600", loggedMeals = 1),
                score("2026-07-20", "900", "1500", loggedMeals = 0),
                score("2026-07-21", "1300", null, loggedMeals = 2),
            )
        )

        val comparison = DailyScoreAnalyticsService(dailyScores)
            .coachAnalytics(userId, today)
            .also { analytics ->
                assertEquals(LocalDate.parse("2026-07-16"), analytics.currentWindow?.from)
                assertEquals(LocalDate.parse("2026-07-22"), analytics.currentWindow?.to)
                assertEquals(LocalDate.parse("2026-07-09"), analytics.previousWindow?.from)
                assertEquals(LocalDate.parse("2026-07-15"), analytics.previousWindow?.to)
            }
            .currentWindowCalorieComparison!!

        assertEquals(BigDecimal("1300.000000"), comparison.averageIntakeCalories)
        assertEquals(BigDecimal("1500.000000"), comparison.averageTargetCalories)
        assertEquals(BigDecimal("-13.333333"), comparison.deltaPercent)
        assertEquals(2, comparison.loggedDayCount)
        assertEquals(LocalDate.parse("2026-07-16"), comparison.periodStart)
        assertEquals(LocalDate.parse("2026-07-22"), comparison.periodEnd)
    }

    @Test
    fun `coach calorie comparison requires two eligible logged days`() {
        Mockito.`when`(
            dailyScores.finalizedScoresForRange(
                userId,
                LocalDate.parse("2026-07-09"),
                LocalDate.parse("2026-07-22"),
            )
        ).thenReturn(listOf(score("2026-07-18", "1300", "1500", loggedMeals = 2)))

        assertNull(
            DailyScoreAnalyticsService(dailyScores)
                .coachAnalytics(userId, today)
                .currentWindowCalorieComparison
        )
    }

    private fun score(
        date: String,
        calories: String,
        target: String?,
        loggedMeals: Int,
    ) = DailyScoreReadModel(
        id = UUID.randomUUID(),
        userId = userId,
        localDate = LocalDate.parse(date),
        score = 80,
        mode = DailyScoreMode.GOAL_ADHERENCE,
        goalId = UUID.randomUUID(),
        goalType = null,
        formulaName = "nutrition_daily_score",
        formulaVersion = "v2",
        breakdown = DailyScoreBreakdown(
            formulaName = "nutrition_daily_score",
            formulaVersion = "v2",
            componentWeights = emptyMap(),
            calorieScore = 80,
            proteinScore = 80,
            fatScore = 80,
            carbohydrateScore = 80,
            loggingCompletenessScore = 80,
            loggedMealCount = loggedMeals,
            loggedMealTypes = emptyList(),
            missingMajorMeals = emptyList(),
            totalCalories = BigDecimal(calories),
            targetCalories = target?.let(::BigDecimal),
            calorieDelta = null,
            totalProtein = BigDecimal.ZERO,
            targetProtein = null,
            proteinDelta = null,
            totalFat = BigDecimal.ZERO,
            targetFat = null,
            fatDelta = null,
            totalCarbohydrates = BigDecimal.ZERO,
            targetCarbohydrates = null,
            carbohydrateDelta = null,
        ),
        finalizedAt = Instant.parse("2026-07-23T00:00:00Z"),
        createdAt = Instant.parse("2026-07-23T00:00:00Z"),
    )
}
