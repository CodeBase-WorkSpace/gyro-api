package com.gyro.api.goal.application.coach

import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.diary.application.DashboardInsightBasis
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.GoalForecast
import com.gyro.api.diary.application.GoalForecastCandidateOutcome
import com.gyro.api.diary.application.GoalForecastMilestoneState
import com.gyro.api.diary.application.GoalForecastStatus
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalForecastObservationFactoryTest {
    private val factory = GoalForecastObservationFactory()

    private val planStart: LocalDate = LocalDate.parse("2026-09-01")
    private val targetDate: LocalDate = LocalDate.parse("2027-01-01")

    private companion object {
        /** The pace that reaches 80 kg exactly on the saved target date. */
        const val ON_PLAN_KG_PER_DAY = "-0.07009"
    }

    @Test
    fun `the four blocks interpolate the saved plan weights and dates`() {
        val forecast = forecast(evidence(weights = emptyList(), today = planStart.plusDays(1)))

        assertEquals(listOf(25, 50, 75, 100), forecast.milestones.map { it.progressPercent })
        assertEquals(
            listOf("87.500", "85.000", "82.500", "80.000"),
            forecast.milestones.map { it.targetWeightKg.toPlainString() },
        )
        // September 1 to January 1 is 122 days: floor(30.5), floor(61), floor(91.5).
        assertEquals(
            listOf("2026-10-01", "2026-11-01", "2026-12-01", "2027-01-01"),
            forecast.milestones.map { it.plannedDate.toString() },
        )
        assertEquals(targetDate, forecast.milestones.last().plannedDate)
        assertEquals(targetDate, forecast.originalTargetDate)
    }

    @Test
    fun `a gain goal interpolates upward and keeps the saved target date`() {
        val forecast = forecast(
            evidence(
                startWeightKg = "60",
                targetWeightKg = "68",
                direction = GoalForecastDirection.GAIN,
                weights = emptyList(),
                today = planStart.plusDays(1),
            ),
        )

        assertEquals(
            listOf("62.000", "64.000", "66.000", "68.000"),
            forecast.milestones.map { it.targetWeightKg.toPlainString() },
        )
        assertEquals(targetDate, forecast.milestones.last().plannedDate)
    }

    @Test
    fun `an on-plan loss projects the goal date close to the saved deadline`() {
        // 88.13 kg on September 7, losing 0.49 kg/week, reaches 80 kg on January 1.
        val today = LocalDate.parse("2026-10-06")
        val forecast = forecast(
            evidence(
                weights = steadyWeights(
                    from = LocalDate.parse("2026-09-07"),
                    days = 29,
                    startKg = "88.13",
                    kgPerDay = ON_PLAN_KG_PER_DAY,
                ),
                today = today,
            ),
        )

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
        val delayDays = assertNotNull(forecast.delayDays)
        assertTrue(kotlin.math.abs(delayDays) <= 7, "expected an on-plan forecast, was $delayDays")
        assertEquals(forecast.forecastTargetDate, forecast.milestones.last().forecastDate)
    }

    @Test
    fun `slower progress moves every unfinished block later without touching the plan`() {
        val onPlan = forecast(slowerEvidence(kgPerDay = ON_PLAN_KG_PER_DAY))
        val slower = forecast(slowerEvidence(kgPerDay = "-0.0400"))

        assertEquals(GoalForecastStatus.AVAILABLE, slower.status)
        assertEquals(
            onPlan.milestones.map { it.plannedDate },
            slower.milestones.map { it.plannedDate },
        )
        assertEquals(targetDate, slower.originalTargetDate)
        onPlan.milestones.zip(slower.milestones).forEach { (fast, slow) ->
            if (fast.forecastDate != null && slow.forecastDate != null) {
                assertTrue(
                    !slow.forecastDate!!.isBefore(fast.forecastDate),
                    "slower progress must not move ${slow.progressPercent}% earlier",
                )
            }
        }
        assertTrue(assertNotNull(slower.delayDays) > assertNotNull(onPlan.delayDays))
    }

    @Test
    fun `faster progress may produce a forecast earlier than the saved date`() {
        val forecast = forecast(slowerEvidence(kgPerDay = "-0.1400"))

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
        assertTrue(assertNotNull(forecast.delayDays) < 0)
        assertTrue(assertNotNull(forecast.forecastTargetDate).isBefore(targetDate))
    }

    @Test
    fun `blocks already passed by the fitted weight are reached and carry no projection`() {
        val today = LocalDate.parse("2026-11-16")
        val forecast = forecast(
            evidence(
                weights = steadyWeights(
                    from = LocalDate.parse("2026-10-18"),
                    days = 29,
                    startKg = "86.00",
                    kgPerDay = "-0.0800",
                ),
                today = today,
            ),
        )

        // The fitted weight at the last weigh-in is 83.76 kg: past 87.5 and 85.0.
        val reached = forecast.milestones.filter { it.state == GoalForecastMilestoneState.REACHED }
        assertEquals(listOf(25, 50), reached.map { it.progressPercent })
        reached.forEach { assertNull(it.forecastDate) }
        assertEquals(
            GoalForecastMilestoneState.NEXT,
            forecast.milestones.first { it.progressPercent == 75 }.state,
        )
        assertEquals(
            GoalForecastMilestoneState.UPCOMING,
            forecast.milestones.first { it.progressPercent == 100 }.state,
        )
        forecast.milestones.filter { it.state != GoalForecastMilestoneState.REACHED }
            .forEach { assertNotNull(it.forecastDate) }
    }

    @Test
    fun `projected dates round upward and never land on the evidence end`() {
        val today = LocalDate.parse("2026-10-06")
        val forecast = forecast(
            evidence(
                weights = steadyWeights(
                    from = LocalDate.parse("2026-09-07"),
                    days = 29,
                    startKg = "88.13",
                    kgPerDay = "-0.0821",
                ),
                today = today,
            ),
        )

        val evidenceEnd = assertNotNull(forecast.evidenceEnd)
        forecast.milestones.mapNotNull { it.forecastDate }.forEach { date ->
            assertTrue(date.isAfter(evidenceEnd), "$date must fall after the evidence end")
        }
        val forecastDates = forecast.milestones.mapNotNull { it.forecastDate }
        assertEquals(forecastDates.sorted(), forecastDates)
    }

    @Test
    fun `a goal date already in the past still produces an ordered future forecast`() {
        val today = LocalDate.parse("2027-02-01")
        val forecast = forecast(
            evidence(
                weights = steadyWeights(
                    from = LocalDate.parse("2027-01-04"),
                    days = 29,
                    startKg = "84.00",
                    kgPerDay = "-0.0500",
                ),
                today = today,
            ),
        )

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
        assertEquals(targetDate, forecast.originalTargetDate)
        assertTrue(assertNotNull(forecast.forecastTargetDate).isAfter(today))
        assertTrue(assertNotNull(forecast.delayDays) > 0)
    }

    @Test
    fun `a projection exactly at the horizon is kept and one day beyond is rejected`() {
        // At 0.01 kg/day the line reaches 80 kg exactly 386 days after the anchor, which
        // is 365 days after the last weigh-in. One gram higher pushes it a day past.
        val atHorizon = forecast(horizonEvidence(startKg = "83.860"))
        val beyondHorizon = forecast(horizonEvidence(startKg = "83.861"))

        assertEquals(GoalForecastStatus.AVAILABLE, atHorizon.status)
        assertEquals(
            365L,
            java.time.temporal.ChronoUnit.DAYS.between(
                assertNotNull(atHorizon.evidenceEnd),
                assertNotNull(atHorizon.forecastTargetDate),
            ),
        )
        assertEquals(GoalForecastStatus.BEYOND_HORIZON, beyondHorizon.status)
        assertNull(beyondHorizon.forecastTargetDate)
        assertNull(beyondHorizon.delayDays)
        assertEquals(4, beyondHorizon.milestones.size)
        beyondHorizon.milestones.forEach { assertNull(it.forecastDate) }
    }

    @Test
    fun `four weigh-in days are insufficient and five are enough`() {
        val dates = listOf("2026-09-07", "2026-09-14", "2026-09-21", "2026-09-25", "2026-09-28")
        val weights = dates.mapIndexed { index, date ->
            TrendPoint(LocalDate.parse(date), BigDecimal("89.00").subtract(BigDecimal("0.30").multiply(BigDecimal(index))))
        }
        val today = LocalDate.parse("2026-09-29")

        assertEquals(
            GoalForecastStatus.INSUFFICIENT_EVIDENCE,
            forecast(evidence(weights = weights.take(4), today = today)).status,
        )
        assertEquals(
            GoalForecastStatus.AVAILABLE,
            forecast(evidence(weights = weights, today = today)).status,
        )
    }

    @Test
    fun `a thirteen day span is insufficient and fourteen days is enough`() {
        val today = LocalDate.parse("2026-09-29")
        val shortSpan = spanWeights(LocalDate.parse("2026-09-15"), spanDays = 13)
        val longSpan = spanWeights(LocalDate.parse("2026-09-14"), spanDays = 14)

        assertEquals(
            GoalForecastStatus.INSUFFICIENT_EVIDENCE,
            forecast(evidence(weights = shortSpan, today = today)).status,
        )
        assertEquals(
            GoalForecastStatus.AVAILABLE,
            forecast(evidence(weights = longSpan, today = today)).status,
        )
    }

    @Test
    fun `a last weigh-in older than seven days is stale`() {
        val weights = steadyWeights(
            from = LocalDate.parse("2026-09-07"),
            days = 21,
            startKg = "88.13",
            kgPerDay = "-0.0821",
        )
        val lastDate = weights.last().date

        assertEquals(
            GoalForecastStatus.AVAILABLE,
            forecast(evidence(weights = weights, today = lastDate.plusDays(7))).status,
        )
        assertEquals(
            GoalForecastStatus.STALE_EVIDENCE,
            forecast(evidence(weights = weights, today = lastDate.plusDays(8))).status,
        )
    }

    @Test
    fun `a flat trend keeps the blocks and reports no estimate`() {
        val forecast = forecast(
            evidence(
                weights = steadyWeights(
                    from = LocalDate.parse("2026-09-07"),
                    days = 21,
                    startKg = "88.000",
                    kgPerDay = "0",
                ),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.FLAT_TREND, forecast.status)
        assertEquals(4, forecast.milestones.size)
        assertNull(forecast.forecastTargetDate)
        assertNull(forecast.observedKgPerWeek)
        assertNull(forecast.fittedWeightKg)
        forecast.milestones.forEach { assertNull(it.forecastDate) }
    }

    @Test
    fun `a trend just below the directional floor is flat and just above it forecasts`() {
        val today = LocalDate.parse("2026-09-29")
        // 0.05 kg/week is 0.007142857 kg/day. The goal is a close one, so a pace at the
        // floor still lands inside the horizon and the floor alone decides the outcome.
        fun nearFloor(kgPerDay: String) = evidence(
            startWeightKg = "90",
            targetWeightKg = "88",
            weights = steadyWeights(days = 21, startKg = "89.500", kgPerDay = kgPerDay),
            today = today,
        )

        assertEquals(GoalForecastStatus.FLAT_TREND, forecast(nearFloor("-0.0071")).status)
        assertEquals(GoalForecastStatus.AVAILABLE, forecast(nearFloor("-0.0072")).status)
    }

    @Test
    fun `a trend moving away from the goal is reported as opposite`() {
        val forecast = forecast(
            evidence(
                weights = steadyWeights(days = 21, kgPerDay = "0.0400"),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.OPPOSITE_TREND, forecast.status)
        assertNull(forecast.forecastTargetDate)
        assertEquals(4, forecast.milestones.size)
    }

    @Test
    fun `a trend beyond the plausible weekly maximum is rejected as low quality`() {
        val today = LocalDate.parse("2026-09-29")
        // 1.50 kg/week is 0.214285714 kg/day.
        assertEquals(
            GoalForecastStatus.AVAILABLE,
            forecast(evidence(weights = steadyWeights(days = 21, kgPerDay = "-0.2142"), today = today)).status,
        )
        assertEquals(
            GoalForecastStatus.LOW_TREND_QUALITY,
            forecast(evidence(weights = steadyWeights(days = 21, kgPerDay = "-0.2143"), today = today)).status,
        )
    }

    @Test
    fun `a noisy series whose slope is buried in its own error is low quality`() {
        val start = LocalDate.parse("2026-09-08")
        val noisy = listOf("89.0", "86.5", "89.2", "86.0", "88.8", "86.4", "88.6")
            .mapIndexed { index, weight ->
                TrendPoint(start.plusDays(index * 3L), BigDecimal(weight))
            }

        val forecast = forecast(evidence(weights = noisy, today = LocalDate.parse("2026-09-29")))

        assertEquals(GoalForecastStatus.LOW_TREND_QUALITY, forecast.status)
        assertNull(forecast.forecastTargetDate)
    }

    @Test
    fun `an outlier that destroys the fit quality suppresses the estimate but not the blocks`() {
        val clean = steadyWeights(days = 21, kgPerDay = "-0.0821")
        val withOutlier = clean.dropLast(1) + TrendPoint(clean.last().date, BigDecimal("97.500"))

        assertEquals(GoalForecastStatus.AVAILABLE, forecast(evidence(weights = clean, today = LocalDate.parse("2026-09-29"))).status)
        val outlier = forecast(evidence(weights = withOutlier, today = LocalDate.parse("2026-09-29")))
        assertTrue(outlier.status != GoalForecastStatus.AVAILABLE, "an outlier-driven fit must not be presented as a date")
        assertEquals(4, outlier.milestones.size)
    }

    @Test
    fun `one anomalous weight that passes r-squared and standard error is still rejected`() {
        // A week of stable weights, then one isolated reading 3 kg lower. Its distance
        // from the cluster gives it enormous leverage: the line it produces looks like a
        // confident 1.1 kg/week loss and clears both statistical gates comfortably.
        val start = LocalDate.parse("2026-09-09")
        val stable = (0..6).map { offset ->
            TrendPoint(start.plusDays(offset.toLong()), BigDecimal("88.000"))
        }
        val withAnomaly = stable + TrendPoint(start.plusDays(20), BigDecimal("85.000"))
        val today = LocalDate.parse("2026-09-30")

        val fit = requireNotNull(com.gyro.api.common.trend.LinearTrend.fitDaily(withAnomaly))
        val kgPerWeek = fit.slopePerDay.multiply(BigDecimal(7)).abs()
        assertTrue(kgPerWeek >= BigDecimal("0.05"), "the anomaly must clear the directional floor")
        assertTrue(kgPerWeek <= BigDecimal("1.50"), "the anomaly must clear the plausibility ceiling")
        assertTrue(
            requireNotNull(fit.rSquared) >= BigDecimal("0.35"),
            "this fixture only proves anything if r-squared passes",
        )
        assertTrue(
            requireNotNull(fit.slopeStdError) <= fit.slopePerDay.abs().multiply(BigDecimal("0.5")),
            "this fixture only proves anything if the standard error passes",
        )

        val forecast = forecast(evidence(weights = withAnomaly, today = today))

        assertEquals(GoalForecastStatus.LOW_TREND_QUALITY, forecast.status)
        assertNull(forecast.forecastTargetDate)
        assertEquals(4, forecast.milestones.size)
    }

    @Test
    fun `the influence gate applies at exactly the minimum five weigh-in days`() {
        // The smallest accepted series is where one measurement carries the most weight,
        // so it is the last place the robustness check may be skipped. Four stable days
        // and one isolated reading 3 kg lower clear every statistical gate.
        val start = LocalDate.parse("2026-09-09")
        val driven = (0..3).map { offset ->
            TrendPoint(start.plusDays(offset.toLong()), BigDecimal("88.000"))
        } + TrendPoint(start.plusDays(20), BigDecimal("85.000"))
        val today = LocalDate.parse("2026-09-30")

        assertEquals(5, driven.size, "this test only means anything at exactly five days")
        val fit = requireNotNull(com.gyro.api.common.trend.LinearTrend.fitDaily(driven))
        val kgPerWeek = fit.slopePerDay.multiply(BigDecimal(7)).abs()
        assertTrue(kgPerWeek >= BigDecimal("0.05") && kgPerWeek <= BigDecimal("1.50"))
        assertTrue(requireNotNull(fit.rSquared) >= BigDecimal("0.35"))
        assertTrue(
            requireNotNull(fit.slopeStdError) <= fit.slopePerDay.abs().multiply(BigDecimal("0.5")),
        )

        val forecast = forecast(evidence(weights = driven, today = today))

        assertEquals(GoalForecastStatus.LOW_TREND_QUALITY, forecast.status)
        assertNull(forecast.forecastTargetDate)
        assertEquals(4, forecast.milestones.size)
    }

    @Test
    fun `a consistent five day series still forecasts`() {
        // The gate must reject a single-point-driven fit without rejecting the minimum
        // evidence case outright.
        val start = LocalDate.parse("2026-09-09")
        val consistent = listOf(0L, 5L, 10L, 15L, 20L).mapIndexed { index, offset ->
            TrendPoint(
                start.plusDays(offset),
                BigDecimal("89.000").subtract(BigDecimal("0.35").multiply(BigDecimal(index))),
            )
        }

        val forecast = forecast(
            evidence(weights = consistent, today = LocalDate.parse("2026-09-30")),
        )

        assertEquals(5, consistent.size)
        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
    }

    @Test
    fun `a steady series is not rejected by the influence gate`() {
        // The robustness gate must not suppress an ordinary consistent trend.
        val forecast = forecast(
            evidence(
                weights = steadyWeights(days = 21, kgPerDay = "-0.0700"),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
    }

    @Test
    fun `a milestone whose projection cannot be computed is never reported as reached`() {
        // Far beyond the horizon: the trend is real and forward, but no block the fitted
        // weight has not passed may come back as REACHED.
        val forecast = forecast(
            evidence(
                weights = steadyWeights(days = 21, startKg = "89.900", kgPerDay = "-0.0080"),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.BEYOND_HORIZON, forecast.status)
        assertEquals(
            listOf(
                GoalForecastMilestoneState.NEXT,
                GoalForecastMilestoneState.UPCOMING,
                GoalForecastMilestoneState.UPCOMING,
                GoalForecastMilestoneState.UPCOMING,
            ),
            forecast.milestones.map { it.state },
        )
        forecast.milestones.forEach { assertNull(it.forecastDate) }
    }

    @Test
    fun `an unavailable state keeps the reached blocks it can still determine`() {
        // One recent weigh-in past the 25% milestone, far too few to fit a trend.
        val forecast = forecast(
            evidence(
                weights = listOf(TrendPoint(LocalDate.parse("2026-09-28"), BigDecimal("87.000"))),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.INSUFFICIENT_EVIDENCE, forecast.status)
        assertEquals(
            GoalForecastMilestoneState.REACHED,
            forecast.milestones.first { it.progressPercent == 25 }.state,
        )
        assertEquals(
            GoalForecastMilestoneState.NEXT,
            forecast.milestones.first { it.progressPercent == 50 }.state,
        )
        forecast.milestones.forEach { assertNull(it.forecastDate) }
    }

    @Test
    fun `no weigh-in at all leaves every block unreached and progress at zero`() {
        val forecast = forecast(evidence(weights = emptyList(), today = LocalDate.parse("2026-09-29")))

        assertEquals(GoalForecastStatus.INSUFFICIENT_EVIDENCE, forecast.status)
        assertEquals(0, forecast.weighInDayCount)
        assertEquals(0, forecast.weightSpanDays)
        assertNull(forecast.evidenceStart)
        assertNull(forecast.evidenceEnd)
        assertEquals(BigDecimal("0.00"), forecast.progressPercent)
        assertEquals(
            GoalForecastMilestoneState.NEXT,
            forecast.milestones.first().state,
        )
    }

    @Test
    fun `a goal already reached produces no observation`() {
        val outcome = factory.create(
            evidence(
                weights = listOf(TrendPoint(LocalDate.parse("2026-09-28"), BigDecimal("79.500"))),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastCandidateOutcome.GOAL_ALREADY_REACHED, outcome.candidateOutcome)
        assertTrue(outcome is GoalForecastOutcome.Ineligible)
    }

    @Test
    fun `less than half a kilogram of remaining change produces no observation`() {
        val outcome = factory.create(
            evidence(
                weights = listOf(TrendPoint(LocalDate.parse("2026-09-28"), BigDecimal("80.400"))),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastCandidateOutcome.REMAINING_CHANGE_TOO_SMALL, outcome.candidateOutcome)
    }

    @Test
    fun `the observation carries the forecast kind, basis, and rounded progress`() {
        val candidate = eligible(
            factory.create(
                evidence(
                    weights = steadyWeights(days = 21, kgPerDay = "-0.0821"),
                    today = LocalDate.parse("2026-09-29"),
                ),
            ),
        )

        assertEquals(DashboardInsightKind.GOAL_FORECAST, candidate.insight.kind)
        assertEquals(DashboardInsightBasis.WEIGHT_FORECAST, candidate.insight.basis)
        assertEquals(
            candidate.insight.goalForecast.progressPercent
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toInt(),
            candidate.insight.value,
        )
        assertTrue(candidate.insight.impressionId.startsWith("OBS|GF|V1|"))
        assertTrue(
            candidate.insight.impressionId.length <= 80,
            "the impression key column stores 80 characters",
        )
    }

    @Test
    fun `a pounds goal already normalized to kilograms forecasts on the kilogram values`() {
        // 200 lb to 176 lb, normalized upstream to 90.718 kg and 79.832 kg.
        val forecast = forecast(
            evidence(
                startWeightKg = "90.718",
                targetWeightKg = "79.832",
                weights = steadyWeights(days = 21, startKg = "89.000", kgPerDay = "-0.0821"),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
        assertEquals("87.997", forecast.milestones.first().targetWeightKg.toPlainString())
        assertEquals("79.832", forecast.milestones.last().targetWeightKg.toPlainString())
    }

    @Test
    fun `the forecast never depends on food logging`() {
        // The evidence model carries weights only; there is no diary input to omit.
        val forecast = forecast(
            evidence(
                weights = steadyWeights(days = 21, kgPerDay = "-0.0821"),
                today = LocalDate.parse("2026-09-29"),
            ),
        )

        assertEquals(GoalForecastStatus.AVAILABLE, forecast.status)
    }

    private fun forecast(evidence: GoalForecastEvidence): GoalForecast =
        eligible(factory.create(evidence)).insight.goalForecast

    private fun eligible(outcome: GoalForecastOutcome) =
        (outcome as? GoalForecastOutcome.Eligible)?.candidate
            ?: error("expected an eligible candidate, was ${outcome.candidateOutcome}")

    private fun evidence(
        startWeightKg: String = "90",
        targetWeightKg: String = "80",
        direction: GoalForecastDirection = GoalForecastDirection.LOSS,
        weights: List<TrendPoint>,
        today: LocalDate,
    ) = GoalForecastEvidence(
        today = today,
        planStart = planStart,
        originalTargetDate = targetDate,
        direction = direction,
        startWeightKg = BigDecimal(startWeightKg),
        targetWeightKg = BigDecimal(targetWeightKg),
        windowStart = maxOf(planStart, today.minusDays(28)),
        windowEnd = today.minusDays(1),
        weights = weights,
    )

    private fun slowerEvidence(kgPerDay: String) = evidence(
        weights = steadyWeights(
            from = LocalDate.parse("2026-09-07"),
            days = 29,
            startKg = "88.13",
            kgPerDay = kgPerDay,
        ),
        today = LocalDate.parse("2026-10-06"),
    )

    private fun horizonEvidence(startKg: String) = evidence(
        weights = steadyWeights(
            from = LocalDate.parse("2026-09-08"),
            days = 22,
            startKg = startKg,
            kgPerDay = "-0.010",
        ),
        today = LocalDate.parse("2026-09-30"),
    )

    /** One weigh-in per day on an exact line, so the fit is deterministic. */
    private fun steadyWeights(
        from: LocalDate = LocalDate.parse("2026-09-08"),
        days: Int,
        startKg: String = "88.000",
        kgPerDay: String,
    ): List<TrendPoint> = (0 until days).map { offset ->
        TrendPoint(
            date = from.plusDays(offset.toLong()),
            value = BigDecimal(startKg).add(BigDecimal(kgPerDay).multiply(BigDecimal(offset))),
        )
    }

    /** Five weigh-ins spread evenly across [spanDays] calendar days. */
    private fun spanWeights(from: LocalDate, spanDays: Long): List<TrendPoint> =
        (0..4).map { index ->
            TrendPoint(
                date = from.plusDays(spanDays * index / 4),
                value = BigDecimal("89.000")
                    .subtract(BigDecimal("0.25").multiply(BigDecimal(index))),
            )
        }
}
