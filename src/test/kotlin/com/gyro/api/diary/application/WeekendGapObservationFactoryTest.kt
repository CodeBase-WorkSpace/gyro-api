package com.gyro.api.diary.application

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeekendGapObservationFactoryTest {
    private val factory = WeekendGapObservationFactory()

    /** Sunday-started window: Thu 07-23, Fri 07-24, Thu 07-30, Fri 07-31. */
    private val periodStart = LocalDate.parse("2026-07-19")
    private val periodEnd = LocalDate.parse("2026-08-01")

    @Test
    fun `weekend and weekday groups are compared against the targets active on each date`() {
        val evidence = window().map { date ->
            if (date.isWeekend()) day(date, intake = "2200", target = "2000")
            else day(date, intake = "1920", target = "2000")
        }

        val insight = eligible(factory.create(periodStart, periodEnd, evidence)).insight

        assertEquals(DashboardInsightKind.WEEKEND_GAP, insight.kind)
        assertEquals(DashboardInsightBasis.TARGET_COMPARISON, insight.basis)
        assertEquals(BigDecimal("10.000000"), insight.weekendTargetDeltaPercent)
        assertEquals(BigDecimal("-4.000000"), insight.weekdayTargetDeltaPercent)
        assertEquals(14, insight.value)
        assertEquals(4, insight.weekendLoggedDayCount)
        assertEquals(10, insight.weekdayLoggedDayCount)
        assertEquals(14, insight.loggedDayCount)
        assertEquals(14, insight.windowDays)
        assertEquals(periodStart, insight.periodStart)
        assertEquals(periodEnd, insight.periodEnd)
        assertEquals("OBS|WG|V1|2026-08-01|HIGHER|MODERATE", insight.impressionId)
    }

    @Test
    fun `sparse but eligible coverage reports the days that were actually compared`() {
        val insight = eligible(
            factory.create(
                periodStart,
                periodEnd,
                // Three weekend days at +8.25% and eight weekdays at -5.60%.
                weekendDates().take(3).map { day(it, intake = "2165", target = "2000") } +
                    weekdayDates().take(8).map { day(it, intake = "1888", target = "2000") },
            )
        ).insight

        assertEquals(BigDecimal("8.250000"), insight.weekendTargetDeltaPercent)
        assertEquals(BigDecimal("-5.600000"), insight.weekdayTargetDeltaPercent)
        assertEquals(14, insight.value)
        assertEquals(3, insight.weekendLoggedDayCount)
        assertEquals(8, insight.weekdayLoggedDayCount)
        assertEquals(11, insight.loggedDayCount)
    }

    @Test
    fun `every rolling window start weekday classifies four weekend and ten weekday days`() {
        (0L..6L).forEach { offset ->
            val start = periodStart.plusDays(offset)
            val end = start.plusDays(13)
            val evidence = generateSequence(start) { it.plusDays(1).takeIf { next -> !next.isAfter(end) } }
                .map { date ->
                    if (date.isWeekend()) day(date, intake = "2400", target = "2000")
                    else day(date, intake = "2000", target = "2000")
                }
                .toList()

            val insight = eligible(
                factory.create(start, end, evidence),
                "start weekday ${start.dayOfWeek}",
            ).insight

            assertEquals(4, insight.weekendLoggedDayCount, "start weekday ${start.dayOfWeek}")
            assertEquals(10, insight.weekdayLoggedDayCount, "start weekday ${start.dayOfWeek}")
            assertEquals(20, insight.value, "start weekday ${start.dayOfWeek}")
        }
    }

    @Test
    fun `exactly three weekend and six weekday days remain eligible`() {
        val insight = eligible(
            factory.create(
                periodStart,
                periodEnd,
                weekendDates().take(3).map { day(it, intake = "2400", target = "2000") } +
                    weekdayDates().take(6).map { day(it, intake = "2000", target = "2000") },
            )
        ).insight

        assertEquals(3, insight.weekendLoggedDayCount)
        assertEquals(6, insight.weekdayLoggedDayCount)
    }

    @Test
    fun `insufficient group coverage suppresses the observation and names the group`() {
        val twoWeekendDays = weekendDates().take(2).map { day(it, intake = "2400", target = "2000") }
        assertSuppressed(
            factory.create(periodStart, periodEnd, twoWeekendDays + weekendGapWeekdays(6)),
            WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS,
        )

        val threeWeekendDays = weekendDates().take(3).map { day(it, intake = "2400", target = "2000") }
        assertSuppressed(
            factory.create(periodStart, periodEnd, threeWeekendDays + weekendGapWeekdays(5)),
            WeekendGapSuppressionReason.INSUFFICIENT_WEEKDAY_DAYS,
        )
    }

    /**
     * Each weekday name occurs exactly twice in a 14-day window, so evidence for
     * only one of Thursday or Friday can never reach three weekend days. The
     * dedicated day-name gate stays in the policy as stated intent, but the
     * coverage gate is what actually fires, and the metric must say so.
     */
    @Test
    fun `evidence from only one weekend day name is not a recurring pattern`() {
        val fridaysOnly = weekendDates()
            .filter { it.dayOfWeek == DayOfWeek.FRIDAY }
            .map { day(it, intake = "2400", target = "2000") }
        assertSuppressed(
            factory.create(periodStart, periodEnd, fridaysOnly + weekendGapWeekdays(10)),
            WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS,
        )

        val thursdaysOnly = weekendDates()
            .filter { it.dayOfWeek == DayOfWeek.THURSDAY }
            .map { day(it, intake = "2400", target = "2000") }
        assertSuppressed(
            factory.create(periodStart, periodEnd, thursdaysOnly + weekendGapWeekdays(10)),
            WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS,
        )
    }

    @Test
    fun `evidence confined to a single Saturday started week is rejected`() {
        // Sat 2026-07-25 through Fri 2026-07-31 is one Saturday-started week, and
        // therefore carries only one Thursday and one Friday.
        val singleWeek = generateSequence(LocalDate.parse("2026-07-25")) {
            it.plusDays(1).takeIf { next -> !next.isAfter(LocalDate.parse("2026-07-31")) }
        }.map { day(it, intake = if (it.isWeekend()) "2400" else "2000", target = "2000") }.toList()

        assertSuppressed(
            factory.create(periodStart, periodEnd, singleWeek),
            WeekendGapSuppressionReason.INSUFFICIENT_WEEKEND_DAYS,
        )
    }

    @Test
    fun `a repeated date is rejected rather than silently resolved by list order`() {
        val duplicated = weekendDates().first()
        val base = window().map { date ->
            if (date.isWeekend()) day(date, intake = "2400", target = "2000")
            else day(date, intake = "2000", target = "2000")
        }

        // Whichever record won would change the deltas, so neither may win.
        assertSuppressed(
            factory.create(periodStart, periodEnd, base + day(duplicated, intake = "100", target = "2000")),
            WeekendGapSuppressionReason.DUPLICATE_DATES,
        )
        assertSuppressed(
            factory.create(periodStart, periodEnd, listOf(day(duplicated, intake = "100", target = "2000")) + base),
            WeekendGapSuppressionReason.DUPLICATE_DATES,
        )
        // A duplicate outside the period is still invalid input, not a filtered row.
        assertSuppressed(
            factory.create(
                periodStart,
                periodEnd,
                base + day(periodEnd.plusDays(1), intake = "2000", target = "2000") +
                    day(periodEnd.plusDays(1), intake = "9000", target = "2000"),
            ),
            WeekendGapSuppressionReason.DUPLICATE_DATES,
        )
    }

    @Test
    fun `days without a positive historical target are excluded rather than compared`() {
        val preplan = weekdayDates().take(4)
        val evidence = weekendDates().map { day(it, intake = "2400", target = "2000") } +
            preplan.map { day(it, intake = "9000", target = "0") } +
            weekdayDates().drop(4).map { day(it, intake = "2000", target = "2000") }

        val insight = eligible(factory.create(periodStart, periodEnd, evidence)).insight

        assertEquals(6, insight.weekdayLoggedDayCount)
        assertEquals(BigDecimal("0.000000"), insight.weekdayTargetDeltaPercent)
        assertEquals(20, insight.value)
    }

    @Test
    fun `dates outside the period never enter the comparison`() {
        val evidence = window().map { date ->
            if (date.isWeekend()) day(date, intake = "2400", target = "2000")
            else day(date, intake = "2000", target = "2000")
        } + day(periodEnd.plusDays(1), intake = "9000", target = "2000")

        val insight = eligible(factory.create(periodStart, periodEnd, evidence)).insight

        assertEquals(14, insight.loggedDayCount)
    }

    @Test
    fun `equal target relative adherence produces no gap even when raw calories differ`() {
        val evidence = window().map { date ->
            // An Advanced schedule with higher weekend targets. Weekend intake is
            // far larger in raw calories, yet both groups sit 10% above target.
            if (date.isWeekend()) day(date, intake = "2750", target = "2500")
            else day(date, intake = "1980", target = "1800")
        }

        assertSuppressed(
            factory.create(periodStart, periodEnd, evidence),
            WeekendGapSuppressionReason.BELOW_MATERIALITY_THRESHOLD,
        )
    }

    @Test
    fun `both groups may sit on the same side of target`() {
        val above = eligible(
            factory.create(
                periodStart,
                periodEnd,
                window().map { date ->
                    if (date.isWeekend()) day(date, intake = "2600", target = "2000")
                    else day(date, intake = "2200", target = "2000")
                },
            )
        ).insight
        assertEquals(BigDecimal("30.000000"), above.weekendTargetDeltaPercent)
        assertEquals(BigDecimal("10.000000"), above.weekdayTargetDeltaPercent)
        assertEquals(20, above.value)

        val below = eligible(
            factory.create(
                periodStart,
                periodEnd,
                window().map { date ->
                    if (date.isWeekend()) day(date, intake = "1800", target = "2000")
                    else day(date, intake = "1400", target = "2000")
                },
            )
        ).insight
        assertEquals(BigDecimal("-10.000000"), below.weekendTargetDeltaPercent)
        assertEquals(BigDecimal("-30.000000"), below.weekdayTargetDeltaPercent)
        assertEquals(20, below.value)
    }

    @Test
    fun `a lower weekend records a negative value and a LOWER fingerprint`() {
        val insight = eligible(
            factory.create(
                periodStart,
                periodEnd,
                window().map { date ->
                    if (date.isWeekend()) day(date, intake = "1600", target = "2000")
                    else day(date, intake = "2000", target = "2000")
                },
            )
        ).insight

        assertEquals(-20, insight.value)
        assertEquals("OBS|WG|V1|2026-08-01|LOWER|LARGE", insight.impressionId)
    }

    @Test
    fun `the materiality threshold is inclusive at ten percentage points`() {
        assertSuppressed(
            factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake = "2199.99998")),
            WeekendGapSuppressionReason.BELOW_MATERIALITY_THRESHOLD,
        )
        val eligible = eligible(
            factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake = "2200"))
        ).insight
        assertEquals(10, eligible.value)
        assertEquals("OBS|WG|V1|2026-08-01|HIGHER|MODERATE", eligible.impressionId)
    }

    @Test
    fun `bands follow the approved percentage point boundaries`() {
        assertEquals(
            "OBS|WG|V1|2026-08-01|HIGHER|MODERATE",
            fingerprintFor(weekendIntake = "2399.99998"),
        )
        assertEquals("OBS|WG|V1|2026-08-01|HIGHER|LARGE", fingerprintFor(weekendIntake = "2400"))
        assertEquals(
            "OBS|WG|V1|2026-08-01|HIGHER|LARGE",
            fingerprintFor(weekendIntake = "2699.99998"),
        )
        assertEquals("OBS|WG|V1|2026-08-01|HIGHER|VERY_LARGE", fingerprintFor(weekendIntake = "2700"))
    }

    @Test
    fun `the exact fingerprint rotates with the period end while its meaning does not`() {
        val later = eligible(
            factory.create(
                periodStart.plusDays(7),
                periodEnd.plusDays(7),
                shiftedWindow(days = 7, weekendIntake = "2400"),
            )
        ).insight

        assertEquals("OBS|WG|V1|2026-08-08|HIGHER|LARGE", later.impressionId)
        assertEquals(
            CoachObservationFingerprintPolicy.materialSignature(fingerprintFor(weekendIntake = "2400")),
            CoachObservationFingerprintPolicy.materialSignature(later.impressionId),
        )
    }

    @Test
    fun `display value rounds half up while salience keeps the unrounded gap`() {
        // Weekend +14.500000 points against a weekday group exactly on target.
        val candidate = eligible(
            factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake = "2290"))
        )

        assertEquals(15, candidate.insight.value)
        assertEquals(14.5 / 30.0, candidate.magnitude, 1e-9)
    }

    @Test
    fun `magnitude stays inside the approved bounds`() {
        assertEquals(
            0.35,
            eligible(factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake = "2200"))).magnitude,
            1e-9,
        )
        assertEquals(
            1.0,
            eligible(factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake = "3000"))).magnitude,
            1e-9,
        )
    }

    @Test
    fun `a period that is not exactly fourteen days is rejected`() {
        val evidence = flatWeekdaysWith(weekendIntake = "2400")
        assertSuppressed(
            factory.create(periodStart, periodEnd.minusDays(1), evidence),
            WeekendGapSuppressionReason.INVALID_PERIOD,
        )
        assertSuppressed(
            factory.create(periodStart, periodEnd.plusDays(1), evidence),
            WeekendGapSuppressionReason.INVALID_PERIOD,
        )
        assertSuppressed(
            factory.create(periodEnd, periodStart, evidence),
            WeekendGapSuppressionReason.INVALID_PERIOD,
        )
    }

    private fun eligible(
        outcome: WeekendGapOutcome,
        message: String = "expected an eligible candidate",
    ): DashboardInsightCandidate<WeekendGapInsight> =
        (outcome as? WeekendGapOutcome.Eligible)?.candidate ?: error("$message but was $outcome")

    private fun assertSuppressed(
        outcome: WeekendGapOutcome,
        reason: WeekendGapSuppressionReason,
    ) = assertEquals(WeekendGapOutcome.Suppressed(reason), outcome)

    private fun fingerprintFor(weekendIntake: String) =
        eligible(factory.create(periodStart, periodEnd, flatWeekdaysWith(weekendIntake)))
            .insight
            .impressionId

    /** Full coverage with every weekday exactly on target, isolating the weekend delta. */
    private fun flatWeekdaysWith(weekendIntake: String) = window().map { date ->
        if (date.isWeekend()) day(date, intake = weekendIntake, target = "2000")
        else day(date, intake = "2000", target = "2000")
    }

    private fun shiftedWindow(days: Long, weekendIntake: String) = window().map { date ->
        val shifted = date.plusDays(days)
        if (shifted.isWeekend()) day(shifted, intake = weekendIntake, target = "2000")
        else day(shifted, intake = "2000", target = "2000")
    }

    private fun weekendGapWeekdays(count: Int) =
        weekdayDates().take(count).map { day(it, intake = "2000", target = "2000") }

    private fun window() = generateSequence(periodStart) {
        it.plusDays(1).takeIf { next -> !next.isAfter(periodEnd) }
    }.toList()

    private fun weekendDates() = window().filter { it.isWeekend() }

    private fun weekdayDates() = window().filterNot { it.isWeekend() }

    private fun LocalDate.isWeekend() =
        dayOfWeek == DayOfWeek.THURSDAY || dayOfWeek == DayOfWeek.FRIDAY

    private fun day(date: LocalDate, intake: String, target: String) =
        WeekendGapDayEvidence(date, BigDecimal(intake), BigDecimal(target))
}
