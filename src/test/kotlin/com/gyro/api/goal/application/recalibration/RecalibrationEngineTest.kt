package com.gyro.api.goal.application.recalibration

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecalibrationEngineTest {
    private val start: LocalDate = LocalDate.of(2026, 7, 1)

    private fun steadyWeights(startKg: Double, dailyChangeKg: Double, days: Int): List<RecalibrationWeightPoint> {
        return (0 until days).map { day ->
            RecalibrationWeightPoint(
                date = start.plusDays(day.toLong()),
                weightKg = BigDecimal.valueOf(startKg + dailyChangeKg * day),
            )
        }
    }

    private fun input(
        weights: List<RecalibrationWeightPoint>,
        loggedDays: Int = 12,
        averageLoggedCalories: String = "1800",
        currentCalories: String = "1800.00",
        intendedDelta: String = "-500.00",
    ) = RecalibrationInput(
        weights = weights,
        loggedDays = loggedDays,
        windowDays = 14,
        averageLoggedCalories = BigDecimal(averageLoggedCalories),
        currentCalories = BigDecimal(currentCalories),
        currentProtein = BigDecimal("140.000"),
        currentCarbs = BigDecimal("200.000"),
        currentFat = BigDecimal("60.000"),
        intendedDailyEnergyDelta = BigDecimal(intendedDelta),
    )

    @Test
    fun `stalled loss suggests a lower target, clamped to 200 kcal`() {
        // Weight flat at 90kg while eating at target: actual TDEE == intake,
        // ideal target = 1800 - 500 = 1300 -> raw -500, clamped to -200.
        val outcome = RecalibrationEngine.evaluate(input(steadyWeights(90.0, 0.0, 14)))

        val suggestion = outcome as RecalibrationOutcome.Suggestion
        assertEquals(0, BigDecimal("1600.00").compareTo(suggestion.suggestedCalories))
        assertEquals("-200.00", suggestion.basis["clampedAdjustment"])
        // Macros scale by 1600/1800.
        assertEquals(0, BigDecimal("124.444").compareTo(suggestion.suggestedProtein))
    }

    @Test
    fun `faster-than-intended loss suggests eating more`() {
        // Losing ~0.13 kg/day (~1000 kcal/day deficit) while eating 1800:
        // TDEE ~= 1800 + 1000 = 2800, ideal = 2300 -> raw +500, clamped +200.
        val outcome = RecalibrationEngine.evaluate(
            input(steadyWeights(90.0, -0.13, 14)),
        )

        val suggestion = outcome as RecalibrationOutcome.Suggestion
        assertEquals(0, BigDecimal("2000.00").compareTo(suggestion.suggestedCalories))
    }

    @Test
    fun `on-track progress produces no suggestion`() {
        // -0.0649351 kg/day is exactly the plan's own 500 kcal/day deficit (500/7700).
        // Least squares reads it exactly, so there is nothing to correct.
        //
        // This fixture used to be -0.079, chosen because the EMA lag read it as ~-0.065.
        // That is now a real +108 kcal correction, pinned by the test below.
        val outcome = RecalibrationEngine.evaluate(
            input(steadyWeights(90.0, -0.0649351, 14)),
        )

        assertTrue(outcome is RecalibrationOutcome.NoSuggestion)
        outcome as RecalibrationOutcome.NoSuggestion
        assertEquals("ADJUSTMENT_TOO_SMALL", outcome.reason)
        assertEquals(0, BigDecimal("2300.00").compareTo(outcome.estimatedTdee))
    }

    @Test
    fun `least squares reads the full trend the EMA was attenuating`() {
        // The executable record of the estimator change. Losing 0.079 kg/day on 1800 kcal
        // means maintenance is ~2408, so the plan's -500 wants 1908: this user should be
        // eating MORE. Endpoint-differencing an EMA read the rate as ~-0.044 and asked
        // for a further cut, i.e. the wrong direction, not merely a smaller correction.
        val outcome = RecalibrationEngine.evaluate(input(steadyWeights(90.0, -0.079, 14)))

        val suggestion = outcome as RecalibrationOutcome.Suggestion
        assertEquals(0, BigDecimal("1908.30").compareTo(suggestion.suggestedCalories))
        assertEquals("-0.553", suggestion.basis["observedKgPerWeek"])
        assertEquals("OLS", suggestion.basis["trendMethod"])
        assertEquals("HIGH", suggestion.basis["confidence"])
    }

    @Test
    fun `insufficient data gates fire before any math`() {
        // Two days: below the three-day LOW threshold. Four would now clear it.
        val fewWeighIns = RecalibrationEngine.evaluate(input(steadyWeights(90.0, 0.0, 2)))
        assertEquals("INSUFFICIENT_WEIGH_INS", (fewWeighIns as RecalibrationOutcome.NoSuggestion).reason)

        // Six consecutive days spans 5, short of the seven-day LOW threshold.
        val narrowSpan = RecalibrationEngine.evaluate(input(steadyWeights(90.0, 0.0, 6)))
        assertEquals("INSUFFICIENT_WEIGHT_SPAN", (narrowSpan as RecalibrationOutcome.NoSuggestion).reason)

        // 5/14 is under the 50% LOW coverage threshold.
        val fewLogs = RecalibrationEngine.evaluate(input(steadyWeights(90.0, 0.0, 14), loggedDays = 5))
        assertEquals("INSUFFICIENT_LOGGED_DAYS", (fewLogs as RecalibrationOutcome.NoSuggestion).reason)
    }

    @Test
    fun `repeated weigh-ins on one day do not clear the weigh-in gate`() {
        val sameMorning = (0 until 4).map {
            RecalibrationWeightPoint(start, BigDecimal.valueOf(90.0 + it * 0.1))
        }
        val plusOneLaterDay = sameMorning + RecalibrationWeightPoint(start.plusDays(8), BigDecimal("89.0"))

        assertEquals(
            "INSUFFICIENT_WEIGH_INS",
            (RecalibrationEngine.evaluate(input(sameMorning)) as RecalibrationOutcome.NoSuggestion).reason,
        )
        // Four rows plus one more day is still only two observed days.
        assertEquals(
            "INSUFFICIENT_WEIGH_INS",
            (RecalibrationEngine.evaluate(input(plusOneLaterDay)) as RecalibrationOutcome.NoSuggestion).reason,
        )
    }

    @Test
    fun `LOW confidence may restore calories but never restrict further`() {
        // The named product policy. Three weigh-in days spanning 8 is LOW on the
        // weigh-in axis. The dominant systematic error here is calorie under-reporting,
        // which biases the estimate toward cuts, so LOW declines to cut.
        val losingFast = listOf(0L to "90.0", 4L to "89.6", 8L to "89.2").map {
            RecalibrationWeightPoint(start.plusDays(it.first), BigDecimal(it.second))
        }
        val increase = RecalibrationEngine.evaluate(input(losingFast)) as RecalibrationOutcome.Suggestion
        assertEquals("LOW", increase.basis["confidence"])
        assertEquals("75", increase.basis["maxAdjustment"])
        assertEquals(0, BigDecimal("1875.00").compareTo(increase.suggestedCalories))

        val gaining = losingFast.mapIndexed { index, point ->
            RecalibrationWeightPoint(point.date, BigDecimal.valueOf(90.0 + index * 0.4))
        }
        val withheld = RecalibrationEngine.evaluate(input(gaining)) as RecalibrationOutcome.NoSuggestion
        assertEquals("LOW_CONFIDENCE_DECREASE_WITHHELD", withheld.reason)
    }

    @Test
    fun `a trivial correction reads as nothing to do, not as a withheld cut`() {
        // Pins the ordering: MIN_ADJUSTMENT is checked before the LOW direction rule, so
        // a LOW user with a -20 correction is told there is nothing to do rather than
        // being given a reason that implies something was hidden.
        val nearlyOnPlan = listOf(0L, 4L, 8L).mapIndexed { index, day ->
            RecalibrationWeightPoint(
                start.plusDays(day),
                BigDecimal.valueOf(90.0 - 0.0649351 * (index * 4)),
            )
        }
        val outcome = RecalibrationEngine.evaluate(input(nearlyOnPlan)) as RecalibrationOutcome.NoSuggestion
        assertEquals("ADJUSTMENT_TOO_SMALL", outcome.reason)
    }

    @Test
    fun `MEDIUM confidence caps corrections at 150 and may cut`() {
        // Five weigh-in days spanning 12 with full coverage: MEDIUM. Flat weight on
        // 1800 against a -500 plan wants -500, capped to -150 rather than HIGH's -200.
        val fiveDays = listOf(0L, 3L, 6L, 9L, 12L).map {
            RecalibrationWeightPoint(start.plusDays(it), BigDecimal("90.0"))
        }
        val outcome = RecalibrationEngine.evaluate(
            input(fiveDays, loggedDays = 14),
        ) as RecalibrationOutcome.Suggestion

        assertEquals("MEDIUM", outcome.basis["confidence"])
        assertEquals(0, BigDecimal("1650.00").compareTo(outcome.suggestedCalories))
    }

    @Test
    fun `a plateau records an undefined r-squared rather than omitting the key`() {
        // Flat series: zero total sum of squares, so r-squared is undefined. The key is
        // present and null, which distinguishes "computed, undefined" from a pre-OLS row
        // that never recorded it.
        val suggestion = RecalibrationEngine.evaluate(
            input(steadyWeights(90.0, 0.0, 14)),
        ) as RecalibrationOutcome.Suggestion

        assertTrue(suggestion.basis.containsKey("trendRSquared"))
        assertEquals(null, suggestion.basis["trendRSquared"])
        assertEquals("0.000000", suggestion.basis["trendSlopeKgPerDay"])
    }

    @Test
    fun `suggestions never drop below the calorie floor`() {
        val outcome = RecalibrationEngine.evaluate(
            input(
                steadyWeights(60.0, 0.0, 14),
                averageLoggedCalories = "1300",
                currentCalories = "1300.00",
                intendedDelta = "-500.00",
            ),
        )

        val suggestion = outcome as RecalibrationOutcome.Suggestion
        assertEquals(0, BigDecimal("1200.00").compareTo(suggestion.suggestedCalories))
        assertEquals("1200.00", suggestion.basis["flooredTo"])
    }
}
