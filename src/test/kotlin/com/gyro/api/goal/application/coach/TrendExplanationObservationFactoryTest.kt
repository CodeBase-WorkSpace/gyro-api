package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.DashboardInsightBasis
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.TrendExplanationCandidateOutcome
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTarget
import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.goal.application.nutrition_plan.PlanScheduleSummaryReadModel
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.ObservedEnergyWindowEvidence
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendExplanationObservationFactoryTest {
    private val factory = TrendExplanationObservationFactory()
    private val windowEnd = LocalDate.parse("2026-08-01")
    private val defaultPlan = UUID.randomUUID()

    @Test
    fun `sufficient below-target intake with a positive trend creates the structured candidate`() {
        val eligible = eligibleOutcome(intake = "1720", target = "2100", days = 12)
        val candidate = eligible.candidate

        assertEquals(DashboardInsightKind.TREND_EXPLANATION, candidate.insight.kind)
        assertEquals("OBS|TX|V1|2026-08-01|W_MEDIUM|I_15_25", candidate.insight.impressionId)
        assertEquals(-18, candidate.insight.value)
        assertEquals(DashboardInsightBasis.TARGET_COMPARISON, candidate.insight.basis)
        assertEquals(BigDecimal("0.550"), candidate.insight.weightTrendKgPerWeek)
        assertEquals(1720, candidate.insight.averageIntakeCalories)
        assertEquals(2100, candidate.insight.averageTargetCalories)
        assertEquals(12, candidate.insight.loggedDayCount)
        assertEquals(5, candidate.insight.weighInDayCount)
        assertEquals(12, candidate.insight.weightSpanDays)
        assertEquals(14, candidate.insight.windowDays)
        assertEquals(LocalDate.parse("2026-07-19"), candidate.insight.periodStart)
        assertEquals(windowEnd, candidate.insight.periodEnd)
        assertEquals(RecalibrationConfidence.MEDIUM, candidate.insight.confidence)
        // The subtype deliberately has no generic trend or TDEE estimator fields.
    }

    @Test
    fun `weight rise boundary includes exactly 0_20 and excludes 0_199 kg per week`() {
        assertEquals(
            TrendExplanationCandidateOutcome.ELIGIBLE,
            outcome(observedKgPerWeek = BigDecimal("0.200")).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.WEIGHT_RISE_NOT_MATERIAL,
            outcome(observedKgPerWeek = BigDecimal("0.199")).candidateOutcome,
        )
    }

    @Test
    fun `flat and decreasing trends are not a material rise`() {
        assertEquals(
            TrendExplanationCandidateOutcome.WEIGHT_RISE_NOT_MATERIAL,
            outcome(observedKgPerWeek = BigDecimal.ZERO).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.WEIGHT_RISE_NOT_MATERIAL,
            outcome(observedKgPerWeek = BigDecimal("-0.400")).candidateOutcome,
        )
    }

    @Test
    fun `noisy and implausibly large trends are implausible evidence`() {
        assertEquals(
            TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE,
            outcome(trendStdErrorKgPerDay = BigDecimal("0.16")).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE,
            outcome(observedKgPerWeek = BigDecimal("1.510")).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE,
            outcome(trendStdErrorKgPerDay = null).candidateOutcome,
        )
    }

    @Test
    fun `upper trend boundary of 1_50 kg per week stays eligible`() {
        assertEquals(
            TrendExplanationCandidateOutcome.ELIGIBLE,
            outcome(observedKgPerWeek = BigDecimal("1.500")).candidateOutcome,
        )
    }

    @Test
    fun `intake gap boundaries of 10 and 40 percent below target are inclusive`() {
        // -10% exactly.
        assertEquals(-10, eligibleOutcome(intake = "1800", target = "2000", days = 10).candidate.insight.value)
        // -40% exactly.
        assertEquals(-40, eligibleOutcome(intake = "1200", target = "2000", days = 10).candidate.insight.value)
    }

    @Test
    fun `intake at, above, or barely below target is not a material gap`() {
        assertEquals(
            TrendExplanationCandidateOutcome.INTAKE_GAP_NOT_MATERIAL,
            outcomeFor(intake = "2100", target = "2000").candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.INTAKE_GAP_NOT_MATERIAL,
            outcomeFor(intake = "1990", target = "2000").candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.INTAKE_GAP_NOT_MATERIAL,
            outcomeFor(intake = "1820", target = "2000").candidateOutcome,
        )
    }

    @Test
    fun `intake more than 40 percent below target reads as implausibly incomplete`() {
        assertEquals(
            TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE,
            outcomeFor(intake = "1180", target = "2000").candidateOutcome,
        )
    }

    @Test
    fun `deviation uses ratio of sums rather than an average of daily percentages`() {
        // Six days 20% below at a 2000 target and six days 5% below at a 3000 target.
        // The average of daily percentages is -12.5%, the ratio of sums is -11%.
        val intakeByDate = mutableMapOf<LocalDate, BigDecimal>()
        val targets = mutableMapOf<LocalDate, ScheduleAwareDailyTarget>()
        for (index in 0 until 12) {
            val date = LocalDate.parse("2026-07-19").plusDays(index.toLong())
            if (index < 6) {
                intakeByDate[date] = BigDecimal("1600")
                targets[date] = scheduleTarget(date, BigDecimal("2000"))
            } else {
                intakeByDate[date] = BigDecimal("2850")
                targets[date] = scheduleTarget(date, BigDecimal("3000"))
            }
        }
        val candidate = eligible(factory.create(listOf(evidence(analysis(loggedDays = 12), intakeByDate)), targets))
        assertEquals(-11, candidate.insight.value)
    }

    @Test
    fun `insufficient food and weight evidence report their own outcomes`() {
        assertEquals(
            TrendExplanationCandidateOutcome.INSUFFICIENT_FOOD,
            outcome(status = ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.INSUFFICIENT_WEIGHT,
            outcome(status = ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_DAYS).candidateOutcome,
        )
        assertEquals(
            TrendExplanationCandidateOutcome.INSUFFICIENT_WEIGHT,
            outcome(status = ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_SPAN).candidateOutcome,
        )
    }

    @Test
    fun `a logged day without a positive historical target has no compatible targets`() {
        val intakeByDate = intakeMap(intake = "1720", days = 12)
        val targets = targetMap(target = "2100", days = 12).toMutableMap()
        // Drop one day's target, as a pre-plan date would resolve to nothing.
        targets.remove(intakeByDate.keys.first())
        assertEquals(
            TrendExplanationCandidateOutcome.NO_COMPATIBLE_TARGETS,
            factory.create(listOf(evidence(analysis(), intakeByDate)), targets).candidateOutcome,
        )

        val zeroTargets = targetMap(target = "0", days = 12)
        assertEquals(
            TrendExplanationCandidateOutcome.NO_COMPATIBLE_TARGETS,
            factory.create(listOf(evidence(analysis(), intakeByDate)), zeroTargets).candidateOutcome,
        )
    }

    @Test
    fun `a plan or regime boundary inside the window is suppressed`() {
        val intakeByDate = intakeMap(intake = "1720", days = 12)
        val crossedPlan = intakeByDate.keys.mapIndexed { index, date ->
            date to scheduleTarget(date, BigDecimal("2100"), planId = if (index < 6) defaultPlan else UUID.randomUUID())
        }.toMap()
        assertEquals(
            TrendExplanationCandidateOutcome.TARGET_REGIME_CROSSED,
            factory.create(listOf(evidence(analysis(), intakeByDate)), crossedPlan).candidateOutcome,
        )

        val crossedRegime = intakeByDate.keys.mapIndexed { index, date ->
            date to scheduleTarget(
                date,
                BigDecimal("2100"),
                goalType = if (index < 6) GoalType.LOSE_WEIGHT else GoalType.MAINTAIN_WEIGHT,
            )
        }.toMap()
        assertEquals(
            TrendExplanationCandidateOutcome.TARGET_REGIME_CROSSED,
            factory.create(listOf(evidence(analysis(), intakeByDate)), crossedRegime).candidateOutcome,
        )
    }

    @Test
    fun `manual plans with no goal type stay eligible under one regime`() {
        val candidate = eligibleOutcome(intake = "1720", target = "2100", days = 12, goalType = null).candidate
        assertEquals(DashboardInsightKind.TREND_EXPLANATION, candidate.insight.kind)
    }

    @Test
    fun `the first eligible window wins even when a larger window shows a stronger mismatch`() {
        val smallStart = LocalDate.parse("2026-07-19")
        val largeStart = LocalDate.parse("2026-07-05")
        val targets = mutableMapOf<LocalDate, ScheduleAwareDailyTarget>()
        val smallIntake = mutableMapOf<LocalDate, BigDecimal>()
        val largeIntake = mutableMapOf<LocalDate, BigDecimal>()
        for (index in 0 until 12) {
            val date = smallStart.plusDays(index.toLong())
            smallIntake[date] = BigDecimal("1760") // -12% of 2000
            targets[date] = scheduleTarget(date, BigDecimal("2000"))
        }
        for (index in 0 until 24) {
            val date = largeStart.plusDays(index.toLong())
            largeIntake[date] = BigDecimal("1300") // -35% of 2000
            targets.putIfAbsent(date, scheduleTarget(date, BigDecimal("2000")))
        }
        val outcome = factory.create(
            listOf(
                evidence(analysis(windowDays = 14, windowStart = smallStart, loggedDays = 12), smallIntake),
                evidence(analysis(windowDays = 28, windowStart = largeStart, loggedDays = 24), largeIntake),
            ),
            targets,
        )
        val candidate = eligible(outcome)
        assertEquals(14, candidate.insight.windowDays)
        assertEquals(-12, candidate.insight.value)
    }

    @Test
    fun `a later window is selected when an earlier one lacks evidence`() {
        val start21 = LocalDate.parse("2026-07-12")
        val targets = mutableMapOf<LocalDate, ScheduleAwareDailyTarget>()
        val intake21 = mutableMapOf<LocalDate, BigDecimal>()
        for (index in 0 until 15) {
            val date = start21.plusDays(index.toLong())
            intake21[date] = BigDecimal("1720")
            targets[date] = scheduleTarget(date, BigDecimal("2100"))
        }
        val outcome = factory.create(
            listOf(
                evidence(analysis(status = ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD), emptyMap()),
                evidence(analysis(windowDays = 21, windowStart = start21, loggedDays = 15), intake21),
            ),
            targets,
        )
        assertEquals(21, eligible(outcome).insight.windowDays)
    }

    @Test
    fun `a trial-clipped window cannot masquerade as a complete supported window`() {
        val clippedStart = LocalDate.parse("2026-07-23")
        val intakeByDate = intakeMap(intake = "1720", days = 9, start = clippedStart)
        val targets = targetMap(target = "2100", days = 9, start = clippedStart)

        val outcome = factory.create(
            listOf(
                evidence(
                    analysis(
                        windowDays = 14,
                        windowStart = clippedStart,
                        loggedDays = 9,
                        weightSpanDays = 8,
                    ),
                    intakeByDate,
                ),
            ),
            targets,
        )

        assertEquals(TrendExplanationCandidateOutcome.IMPLAUSIBLE_EVIDENCE, outcome.candidateOutcome)
    }

    // --- helpers -------------------------------------------------------------

    private fun eligibleOutcome(
        intake: String,
        target: String,
        days: Int,
        goalType: GoalType? = null,
    ): TrendExplanationOutcome.Eligible {
        val intakeByDate = intakeMap(intake, days)
        val targets = targetMap(target, days, goalType)
        return TrendExplanationOutcome.Eligible(
            eligible(factory.create(listOf(evidence(analysis(loggedDays = days), intakeByDate)), targets)),
        )
    }

    /** Outcome for the default eligible fixture with one field overridden on the analysis. */
    private fun outcome(
        status: ObservedEnergyEvidenceStatus = ObservedEnergyEvidenceStatus.SUFFICIENT,
        observedKgPerWeek: BigDecimal? = BigDecimal("0.550"),
        trendStdErrorKgPerDay: BigDecimal? = BigDecimal("0.100"),
    ): TrendExplanationOutcome {
        val intakeByDate = intakeMap(intake = "1720", days = 12)
        val targets = targetMap(target = "2100", days = 12)
        return factory.create(
            listOf(
                evidence(
                    analysis(
                        status = status,
                        observedKgPerWeek = observedKgPerWeek,
                        trendStdErrorKgPerDay = trendStdErrorKgPerDay,
                        loggedDays = 12,
                    ),
                    intakeByDate,
                ),
            ),
            targets,
        )
    }

    /** Outcome varying only the intake/target so materiality gates can be probed. */
    private fun outcomeFor(intake: String, target: String): TrendExplanationOutcome {
        val intakeByDate = intakeMap(intake, days = 12)
        val targets = targetMap(target, days = 12)
        return factory.create(listOf(evidence(analysis(loggedDays = 12), intakeByDate)), targets)
    }

    private fun eligible(outcome: TrendExplanationOutcome) =
        (outcome as? TrendExplanationOutcome.Eligible)?.candidate
            ?: error("expected an eligible candidate but got $outcome")

    private fun intakeMap(intake: String, days: Int, start: LocalDate = LocalDate.parse("2026-07-19")) =
        (0 until days).associate { start.plusDays(it.toLong()) to BigDecimal(intake) }

    private fun targetMap(
        target: String,
        days: Int,
        goalType: GoalType? = null,
        start: LocalDate = LocalDate.parse("2026-07-19"),
    ) = (0 until days).associate {
        val date = start.plusDays(it.toLong())
        date to scheduleTarget(date, BigDecimal(target), goalType = goalType)
    }

    private fun evidence(analysis: ObservedEnergyAnalysis, intakeByDate: Map<LocalDate, BigDecimal>) =
        ObservedEnergyWindowEvidence(analysis, intakeByDate)

    private fun scheduleTarget(
        date: LocalDate,
        calories: BigDecimal,
        planId: UUID = defaultPlan,
        goalType: GoalType? = null,
    ) = ScheduleAwareDailyTarget(
        planId,
        goalType,
        DailyTargetReadModel(
            PlanScheduleSummaryReadModel(GoalScheduleType.FLAT, date, DailyTargetSource.BASE_PLAN),
            NutritionTargetsReadModel(calories, BigDecimal("100"), BigDecimal("200"), BigDecimal("70"), null),
        ),
    )

    private fun analysis(
        status: ObservedEnergyEvidenceStatus = ObservedEnergyEvidenceStatus.SUFFICIENT,
        windowDays: Int = 14,
        windowStart: LocalDate = LocalDate.parse("2026-07-19"),
        loggedDays: Int = 12,
        weighInDays: Int = 5,
        weightSpanDays: Long = 12,
        observedKgPerWeek: BigDecimal? = BigDecimal("0.550"),
        trendRSquared: BigDecimal? = BigDecimal("0.30"),
        trendStdErrorKgPerDay: BigDecimal? = BigDecimal("0.100"),
        confidence: RecalibrationConfidence? = RecalibrationConfidence.MEDIUM,
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
        averageLoggedCalories = BigDecimal("1720"),
        observedKgPerWeek = observedKgPerWeek,
        estimatedTdee = BigDecimal("2200"),
        confidence = confidence,
        trendRSquared = trendRSquared,
        trendStdErrorKgPerDay = trendStdErrorKgPerDay,
    )
}
