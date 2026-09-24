package com.gyro.api.goal.application.recalibration

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdaptiveRecalibrationSelectorTest {
    private val start = LocalDate.of(2026, 7, 1)

    @Test
    fun `a valid fourteen day recommendation is selected when no longer evidence is available`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(input(14, loggedDays = 7, weights = weights(14, -0.13))),
        )

        val suggestion = assertIs<RecalibrationOutcome.Suggestion>(outcome)
        assertEquals(14, suggestion.basis["windowDays"])
    }

    @Test
    fun `insufficient fourteen day evidence can select twenty one days`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 6, weights = weights(14, -0.13)),
                input(21, loggedDays = 13, recentLoggedDays = 3, weights = weights(21, -0.13)),
            ),
        )

        val suggestion = assertIs<RecalibrationOutcome.Suggestion>(outcome)
        assertEquals(21, suggestion.basis["windowDays"])
    }

    @Test
    fun `insufficient twenty one day evidence can select twenty eight days`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 6, weights = weights(14, -0.13)),
                input(21, loggedDays = 12, recentLoggedDays = 3, weights = weights(21, -0.13)),
                input(28, loggedDays = 20, recentLoggedDays = 3, weights = weights(28, -0.13)),
            ),
        )

        val suggestion = assertIs<RecalibrationOutcome.Suggestion>(outcome)
        assertEquals(28, suggestion.basis["windowDays"])
    }

    @Test
    fun `sparse twenty eight day logging fails the percentage and absolute gates`() {
        val outcome = RecalibrationEngine.evaluate(
            input(28, loggedDays = 19, recentLoggedDays = 3, weights = weights(28, -0.13)),
        )

        assertEquals("INSUFFICIENT_LOGGED_DAYS", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `adjustment too small never triggers expansion`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 14, weights = weights(14, -500.0 / 7700)),
                input(21, loggedDays = 21, recentLoggedDays = 7, weights = weights(21, 0.0)),
            ),
        )

        assertEquals("ADJUSTMENT_TOO_SMALL", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `consistent longer evidence may turn a withheld low decrease into a medium correction`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 7, weights = sparseWeights(0.13)),
                input(21, loggedDays = 13, recentLoggedDays = 3, weights = weights(21, 0.13)),
            ),
        )

        val suggestion = assertIs<RecalibrationOutcome.Suggestion>(outcome)
        assertEquals(21, suggestion.basis["windowDays"])
        assertEquals("MEDIUM", suggestion.basis["confidence"])
    }

    @Test
    fun `materially opposite window trends suppress a recommendation`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 7, weights = sparseWeights(0.20)),
                input(21, loggedDays = 13, recentLoggedDays = 3, weights = weights(21, -0.20)),
            ),
        )

        assertEquals("CONFLICTING_TRENDS", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `actionable fourteen day increase is suppressed by a materially opposite twenty one day trend`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 14, weights = weights(14, -0.13)),
                input(21, loggedDays = 21, recentLoggedDays = 7, weights = weights(21, 0.13)),
            ),
        )

        assertEquals("CONFLICTING_TRENDS", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `actionable fourteen day decrease is suppressed by a materially opposite twenty one day trend`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 14, weights = weights(14, 0.13)),
                input(21, loggedDays = 21, recentLoggedDays = 7, weights = weights(21, -0.13)),
            ),
        )

        assertEquals("CONFLICTING_TRENDS", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `a flat longer trend does not suppress an actionable fourteen day suggestion`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 14, weights = weights(14, -0.13)),
                input(21, loggedDays = 21, recentLoggedDays = 7, weights = weights(21, 0.01)),
            ),
        )

        assertEquals(14, assertIs<RecalibrationOutcome.Suggestion>(outcome).basis["windowDays"])
    }

    @Test
    fun `a sufficient twenty eight day trend can suppress fourteen day suggestion when twenty one is insufficient`() {
        val outcome = AdaptiveRecalibrationSelector.evaluate(
            listOf(
                input(14, loggedDays = 14, weights = weights(14, -0.13)),
                input(21, loggedDays = 12, recentLoggedDays = 3, weights = weights(21, 0.13)),
                input(28, loggedDays = 20, recentLoggedDays = 3, weights = weights(28, 0.13)),
            ),
        )

        assertEquals("CONFLICTING_TRENDS", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    @Test
    fun `unsupported windows produce a stable domain outcome`() {
        val outcome = RecalibrationEngine.evaluate(input(15, loggedDays = 15, weights = weights(15, -0.13)))

        assertEquals("UNSUPPORTED_WINDOW", assertIs<RecalibrationOutcome.NoSuggestion>(outcome).reason)
    }

    private fun input(
        windowDays: Int,
        loggedDays: Int,
        weights: List<RecalibrationWeightPoint>,
        recentLoggedDays: Int = 0,
    ) = RecalibrationInput(
        weights = weights,
        loggedDays = loggedDays,
        recentLoggedDays = recentLoggedDays,
        windowDays = windowDays,
        averageLoggedCalories = BigDecimal("1800"),
        currentCalories = BigDecimal("1800"),
        currentProtein = BigDecimal("140"),
        currentCarbs = BigDecimal("200"),
        currentFat = BigDecimal("60"),
        intendedDailyEnergyDelta = BigDecimal("-500"),
    )

    private fun weights(days: Int, changePerDay: Double): List<RecalibrationWeightPoint> =
        (0 until days).map { day ->
            RecalibrationWeightPoint(start.plusDays(day.toLong()), BigDecimal.valueOf(90 + changePerDay * day))
        }

    private fun sparseWeights(changePerDay: Double): List<RecalibrationWeightPoint> =
        listOf(0L, 4L, 8L).map { day ->
            RecalibrationWeightPoint(start.plusDays(day), BigDecimal.valueOf(90 + changePerDay * day))
        }
}
