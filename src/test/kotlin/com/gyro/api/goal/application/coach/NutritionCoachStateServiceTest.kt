package com.gyro.api.goal.application.coach

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsight
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.DashboardInsightsService
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.GoalForecastCandidateOutcome
import com.gyro.api.diary.application.MeasuredTdeeCandidateOutcome
import com.gyro.api.diary.application.MeasuredTdeeInsight
import com.gyro.api.goal.application.recalibration.RecalibrationDataLoader
import com.gyro.api.goal.application.recalibration.RecalibrationData
import com.gyro.api.goal.application.recalibration.RecalibrationWeightPoint
import com.gyro.api.goal.application.recalibration.RecalibrationService
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysisService
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.ObservedEnergyWindowEvidence
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.domain.DailyEnergyDeltaSource
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.goal.infrastructure.PlanRecalibrationSuggestionRepository
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.trial.TrialService
import com.gyro.api.subscription.application.trial.RedeemedTrialPeriod
import com.gyro.api.user.application.UserTimezoneResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NutritionCoachStateServiceTest {
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-07-23T10:00:00Z")
    private val loader = Mockito.mock(RecalibrationDataLoader::class.java)
    private val recalibration = Mockito.mock(RecalibrationService::class.java)
    private val suggestions = Mockito.mock(PlanRecalibrationSuggestionRepository::class.java)
    private val entitlement = Mockito.mock(EntitlementGateService::class.java)
    /**
     * Records the supplemental candidates the resolver offered to ranking. Capturing
     * through the default answer keeps this free of argument matchers, which cannot
     * express Kotlin's non-null parameters.
     */
    private val suppliedCandidates = mutableListOf<DashboardInsightCandidate<DashboardInsight>>()
    private val insights = Mockito.mock(DashboardInsightsService::class.java) { invocation ->
        (invocation.arguments.lastOrNull() as? List<*>)?.let { supplemental ->
            @Suppress("UNCHECKED_CAST")
            suppliedCandidates += supplemental as List<DashboardInsightCandidate<DashboardInsight>>
        }
        emptyList<DashboardInsight>()
    }
    private val timezoneResolver = Mockito.mock(UserTimezoneResolver::class.java)
    private val time = Mockito.mock(TimeProvider::class.java)
    private val observedEnergy = Mockito.mock(ObservedEnergyAnalysisService::class.java)
    private val planSchedule = Mockito.mock(PlanScheduleService::class.java)
    private val trial = Mockito.mock(TrialService::class.java)
    private val scheduleTargetLoader = Mockito.mock(ScheduleAwareDailyTargetLoader::class.java)
    private val impressionMetrics = Mockito.mock(CoachInsightImpressionMetrics::class.java)
    /** These fixtures carry no target weight or date, so no forecast is ever offered. */
    private val goalForecastEvidence = Mockito.mock(GoalForecastEvidenceService::class.java) {
        GoalForecastEvidenceOutcome.Ineligible(GoalForecastCandidateOutcome.MISSING_TARGET)
    }
    private val zone = ZoneId.of("America/Los_Angeles")

    @BeforeEach
    fun setUp() {
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(time.today(zone)).thenReturn(LocalDate.parse("2026-07-23"))
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(insights.insightsFor(userId, zone)).thenReturn(emptyList())
        Mockito.`when`(
            insights.insightsFor(
                userId,
                zone,
                setOf(DashboardInsightKind.CALORIE_ADHERENCE),
            )
        ).thenReturn(emptyList())
        Mockito.`when`(suggestions.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(null)
        stubRollingEvidence(insufficientObservedEvidence())
    }

    @Test
    fun `no active plan has null state and mode`() {
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(null)
        val state = service(locked = true).resolve(userId)
        assertNull(state.mode)
        assertNull(state.state)
    }

    @Test
    fun `entitled quiet state computes live evidence once and passes a supplemental candidate`() {
        val plan = plan()
        val evidence = sufficientObservedEvidence()
        val candidate = DashboardInsightCandidate(
            insight = MeasuredTdeeInsight(
                impressionId = "OBS|MT|V1|2026-07-22|LOW|B2300",
                value = 2310,
                confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.LOW,
                estimatorVersion = "OLS_7700_V1",
                displayPolicyVersion = "V1",
                windowDays = 14,
                loggedDayCount = 10,
                weighInDayCount = 4,
                weightSpanDays = 10,
                periodStart = LocalDate.parse("2026-07-09"),
                periodEnd = LocalDate.parse("2026-07-22"),
            ),
            magnitude = 0.60,
        )
        val factory = Mockito.mock(MeasuredTdeeObservationFactory::class.java)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(null)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        stubRollingEvidence(evidence)
        Mockito.`when`(factory.evaluate(evidence))
            .thenReturn(MeasuredTdeeObservationOutcome.Eligible(candidate))

        service(locked = true, factory = factory).resolve(userId)

        Mockito.verify(observedEnergy).windowEvidence(userId, LocalDate.parse("2026-07-22"), null)
        Mockito.verify(factory).evaluate(evidence)
        Mockito.verify(insights).insightsFor(
            userId,
            zone,
            emptySet(),
            null,
            listOf(candidate),
        )
    }

    @Test
    fun `free quiet state does not query or expose measured TDEE`() {
        val plan = plan()
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(null)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(false)

        val state = service(locked = true).resolve(userId)

        assertNull(state.measuredTdee)
        // The measured-TDEE estimate is gated out before its evidence is read; the
        // trend-explanation candidate may still read the observed-energy windows, which
        // never expose a TDEE value.
        Mockito.verify(impressionMetrics).stateCandidateOutcome(MeasuredTdeeCandidateOutcome.NOT_ENTITLED)
        Mockito.verify(insights).insightsFor(userId, zone)
    }

    @Test
    fun `recommendation state excludes measured TDEE before evaluating evidence`() {
        val plan = plan()
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(pending())
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)

        val state = service(locked = true).resolve(userId)

        assertEquals(NutritionCoachState.RECOMMENDATION, state.state)
        // Measured TDEE is excluded by state before its evidence is read; the
        // trend-explanation candidate may still read the observed-energy windows.
        Mockito.verify(impressionMetrics).stateCandidateOutcome(MeasuredTdeeCandidateOutcome.STATE_EXCLUDED)
        Mockito.verify(insights).insightsFor(
            userId,
            zone,
            setOf(DashboardInsightKind.CALORIE_ADHERENCE),
            null,
        )
    }

    @Test
    fun `profile timezone drives both plan selection and observation window`() {
        val plan = plan().also { it.timezone = "Asia/Tehran" }
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(null)

        service(locked = true).resolve(userId)

        Mockito.verify(loader).activePlanFor(userId, zone)
        Mockito.verify(loader).loadForPlan(userId, plan, zone)
        Mockito.verify(insights).insightsFor(userId, zone)
    }

    @Test
    fun `manual plan only exposes insights and suppresses pending recommendation`() {
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan(delta = null))
        val state = service(locked = true).resolve(userId)
        assertEquals(NutritionCoachMode.INSIGHTS_ONLY, state.mode)
        assertEquals(NutritionCoachState.COLLECTING_DATA, state.state)
        Mockito.verifyNoInteractions(recalibration)
    }

    @Test
    fun `manual plan reports sufficient historical evidence without evaluating goal intent`() {
        val plan = plan(delta = null)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        stubRollingEvidence(sufficientObservedEvidence())

        val state = service(locked = true).resolve(userId)

        assertEquals(NutritionCoachMode.INSIGHTS_ONLY, state.mode)
        assertEquals(NutritionCoachState.OBSERVED_PROGRESS, state.state)
        assertEquals(NutritionCoachAnalysisScope.ROLLING, state.analysisScope)
        assertNull(state.observedProgress?.estimatedTdee)
        Mockito.verifyNoInteractions(recalibration)
    }

    @Test
    fun `audit recovered intent enables evaluation while calculator recovery remains available`() {
        val plan = plan(start = "2026-07-20").also {
            it.dailyEnergyDeltaSource = DailyEnergyDeltaSource.RECALIBRATION_AUDIT
        }
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)

        val state = service(locked = true).resolve(userId)

        assertEquals(NutritionCoachMode.FULL, state.mode)
        assertEquals(NutritionCoachState.LEARNING, state.state)
        assertEquals(false, state.calculatorProvenanceComplete)
    }

    @Test
    fun `expired trial uses a fixed evidence window bounded by trial and schedule`() {
        val plan = plan(delta = null)
        val access = PlanScheduleService.AdvancedScheduleAccess(
            activeFrom = LocalDate.parse("2026-06-25"),
            degradedFrom = LocalDate.parse("2026-07-23"),
            preserved = true,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(planSchedule.advancedScheduleAccess(userId, plan)).thenReturn(access)
        Mockito.`when`(trial.redeemedTrialPeriod(userId)).thenReturn(
            RedeemedTrialPeriod(
                grantedAt = Instant.parse("2026-07-01T08:00:00Z"),
                expiresAt = Instant.parse("2026-07-23T08:00:00Z"),
            ),
        )
        val trialEvidence = sufficientObservedEvidence()
        val trialWindows = listOf(ObservedEnergyWindowEvidence(trialEvidence, emptyMap()))
        Mockito.`when`(
            observedEnergy.windowEvidence(
                userId,
                LocalDate.parse("2026-07-22"),
                LocalDate.parse("2026-07-01"),
            ),
        ).thenReturn(trialWindows)
        Mockito.`when`(observedEnergy.selectAnalysis(trialWindows)).thenReturn(trialEvidence)
        Mockito.`when`(
            insights.insightsFor(userId, zone, emptySet(), LocalDate.parse("2026-07-23")),
        ).thenReturn(emptyList())

        val state = service(locked = true).resolve(userId)

        assertEquals(NutritionCoachAnalysisScope.TRIAL_HISTORY, state.analysisScope)
        assertEquals(NutritionCoachState.OBSERVED_PROGRESS, state.state)
        assertEquals(true, state.advancedScheduleAccess?.degraded)
        assertNull(state.observedProgress?.estimatedTdee)
    }

    @Test
    fun `the goal forecast reaches the rolling pool but never a frozen trial summary`() {
        val plan = plan(delta = null)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(goalForecastEvidence.evidenceFor(userId, plan, LocalDate.parse("2026-07-23")))
            .thenReturn(GoalForecastEvidenceOutcome.Eligible(forecastEvidence()))

        service(locked = true).resolve(userId)

        assertTrue(
            DashboardInsightKind.GOAL_FORECAST in suppliedKinds(),
            "an active plan must be offered the forecast",
        )

        // The same user, now reading a fixed trial-history summary.
        suppliedCandidates.clear()
        Mockito.`when`(planSchedule.advancedScheduleAccess(userId, plan)).thenReturn(
            PlanScheduleService.AdvancedScheduleAccess(
                activeFrom = LocalDate.parse("2026-06-25"),
                degradedFrom = LocalDate.parse("2026-07-23"),
                preserved = true,
            ),
        )
        Mockito.`when`(trial.redeemedTrialPeriod(userId)).thenReturn(
            RedeemedTrialPeriod(
                grantedAt = Instant.parse("2026-07-01T08:00:00Z"),
                expiresAt = Instant.parse("2026-07-23T08:00:00Z"),
            ),
        )
        val trialWindows = listOf(ObservedEnergyWindowEvidence(sufficientObservedEvidence(), emptyMap()))
        Mockito.`when`(
            observedEnergy.windowEvidence(userId, LocalDate.parse("2026-07-22"), LocalDate.parse("2026-07-01")),
        ).thenReturn(trialWindows)
        Mockito.`when`(observedEnergy.selectAnalysis(trialWindows)).thenReturn(sufficientObservedEvidence())

        val trialState = service(locked = true).resolve(userId)

        assertEquals(NutritionCoachAnalysisScope.TRIAL_HISTORY, trialState.analysisScope)
        assertTrue(
            DashboardInsightKind.GOAL_FORECAST !in suppliedKinds(),
            "a live forecast must never be mixed into a frozen historical summary",
        )
    }

    /** The observation kinds the resolver offered to ranking since the last reset. */
    private fun suppliedKinds(): Set<DashboardInsightKind> =
        suppliedCandidates.mapTo(mutableSetOf()) { it.insight.kind }

    /** Twenty-eight daily weigh-ins on an exact line toward an active 90 kg to 80 kg goal. */
    private fun forecastEvidence(): GoalForecastEvidence {
        val firstWeighIn = LocalDate.parse("2026-06-25")
        return GoalForecastEvidence(
            today = LocalDate.parse("2026-07-23"),
            planStart = LocalDate.parse("2026-06-01"),
            originalTargetDate = LocalDate.parse("2026-10-31"),
            direction = GoalForecastDirection.LOSS,
            startWeightKg = BigDecimal("90.000"),
            targetWeightKg = BigDecimal("80.000"),
            windowStart = LocalDate.parse("2026-06-25"),
            windowEnd = LocalDate.parse("2026-07-22"),
            weights = (0..27).map { offset ->
                com.gyro.api.common.trend.TrendPoint(
                    firstWeighIn.plusDays(offset.toLong()),
                    BigDecimal("88.100").subtract(BigDecimal("0.070").multiply(BigDecimal(offset))),
                )
            },
        )
    }

    @Test
    fun `free pending recommendation is locked without target numbers when enabled`() {
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan())
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(pending())
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(false)
        val state = service(locked = true).resolve(userId)
        assertEquals(NutritionCoachState.RECOMMENDATION_LOCKED, state.state)
        assertNull(state.recommendation)
    }

    @Test
    fun `entitled pending recommendation returns mapped targets`() {
        val plan = plan()
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(pending())
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        val state = service(locked = true).resolve(userId)
        assertEquals(NutritionCoachState.RECOMMENDATION, state.state)
        assertEquals(BigDecimal("1800"), state.recommendation?.suggested?.calories)
        Mockito.verify(insights).insightsFor(
            userId,
            zone,
            setOf(DashboardInsightKind.CALORIE_ADHERENCE),
        )
        Mockito.verify(insights, Mockito.never()).insightsFor(userId, zone)
        Mockito.verify(loader, Mockito.never()).loadReadinessForPlan(userId, plan, zone)
        Mockito.verify(loader, Mockito.never()).loadForPlan(userId, plan, zone)
        Mockito.verifyNoInteractions(suggestions)
    }

    @Test
    fun `free suggestion without a persisted row is locked when teaser is enabled`() {
        val plan = plan()
        val data = data(plan, weights = listOf(0L, 3L, 6L, 9L, 12L).map { RecalibrationWeightPoint(LocalDate.parse("2026-07-01").plusDays(it), BigDecimal("80")) })
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(false)

        val state = service(locked = true).resolve(userId)
        assertEquals(NutritionCoachMode.FULL, state.mode)
        assertEquals(NutritionCoachState.RECOMMENDATION_LOCKED, state.state)
        assertNull(state.recommendation)
    }

    @Test
    fun `collecting state reports exact progress fields`() {
        val plan = plan()
        val data = data(
            plan,
            weights = listOf(
                RecalibrationWeightPoint(LocalDate.parse("2026-07-15"), BigDecimal("80")),
                RecalibrationWeightPoint(LocalDate.parse("2026-07-15"), BigDecimal("79.8")),
            ),
            loggedDays = 3,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)

        // The required values are the LOW tier: what it now takes to get any suggestion
        // at all, not what it takes to get a full-strength one. This is a live frontend
        // contract — the card renders these as "N of M" progress.
        val collecting = service(locked = true).resolve(userId).collecting!!
        assertEquals(1, collecting.weighIns)
        assertEquals(3, collecting.weighInsRequired)
        assertEquals(0, collecting.spanDays)
        assertEquals(7, collecting.spanDaysRequired)
        assertEquals(21, collecting.coveragePercent)
        assertEquals(50, collecting.coverageRequired)
        assertEquals(3, collecting.foodEvidenceDays)
        assertEquals(7, collecting.foodEvidenceDaysRequired)
        assertEquals(1, collecting.weighInDays)
        assertEquals(3, collecting.weighInDaysRequired)
        assertEquals(0, collecting.weightSpanDays)
        assertEquals(7, collecting.weightSpanDaysRequired)
        assertEquals(false, collecting.weighedInToday)
        assertEquals(
            NutritionCoachReadinessReason.INSUFFICIENT_FOOD_AND_WEIGHT_EVIDENCE,
            collecting.readinessReason,
        )
        assertEquals(NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY, collecting.nextUsefulAction)
    }

    @Test
    fun `recent acceptance returns applied and next evaluation timestamps`() {
        val plan = plan()
        val accepted = pending().also { it.status = RecalibrationSuggestionStatus.ACCEPTED; it.decidedAt = now.minusSeconds(60) }
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(suggestions.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(accepted)
        val waiting = service(locked = true).resolve(userId).waiting!!
        assertEquals(accepted.decidedAt, waiting.appliedAt)
        assertEquals(accepted.decidedAt!!.plusSeconds(7 * 24 * 60 * 60), waiting.nextEvaluationAt)
    }

    @Test
    fun `unavailable recalibration data stays neutral after a dismissed suggestion`() {
        val plan = plan()
        val dismissed = pending().also { it.status = RecalibrationSuggestionStatus.DISMISSED; it.decidedAt = now.minusSeconds(10) }
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(suggestions.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(dismissed)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(null)
        val state = service(locked = true).resolve(userId)
        assertEquals(NutritionCoachState.INSIGHTS, state.state)
        assertNull(state.collecting)
    }

    @Test
    fun `new automatic plan stays neutral before seven completed days`() {
        val plan = plan(start = "2026-07-20")
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        assertEquals(NutritionCoachState.LEARNING, service(true).resolve(userId).state)
        Mockito.verify(loader, Mockito.never()).loadReadinessForPlan(userId, plan, zone)
        Mockito.verify(loader, Mockito.never()).loadForPlan(userId, plan, zone)
    }

    @Test
    fun `day seven with fewer than four food days identifies food as the next action`() {
        val plan = plan(start = "2026-07-16")
        val data = data(
            plan,
            weights = weightsOn("2026-07-16", "2026-07-19", "2026-07-23"),
            loggedDays = 3,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadReadinessForPlan(userId, plan, zone)).thenReturn(data)

        val state = service(true).resolve(userId)

        assertEquals(NutritionCoachState.COLLECTING_DATA, state.state)
        assertEquals(
            NutritionCoachReadinessReason.INSUFFICIENT_FOOD_EVIDENCE,
            state.collecting?.readinessReason,
        )
        assertEquals(4, state.collecting?.foodEvidenceDaysRequired)
        assertEquals(NutritionCoachNextUsefulAction.LOG_FOOD, state.collecting?.nextUsefulAction)
    }

    @Test
    fun `day seven with adequate food and too few weigh in days identifies weight`() {
        val plan = plan(start = "2026-07-16")
        val data = data(
            plan,
            weights = weightsOn("2026-07-16", "2026-07-23"),
            loggedDays = 4,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadReadinessForPlan(userId, plan, zone)).thenReturn(data)

        val state = service(true).resolve(userId)

        assertEquals(NutritionCoachState.COLLECTING_DATA, state.state)
        assertEquals(
            NutritionCoachReadinessReason.INSUFFICIENT_WEIGH_IN_DAYS,
            state.collecting?.readinessReason,
        )
        assertEquals(true, state.collecting?.weighedInToday)
        assertEquals(
            NutritionCoachNextUsefulAction.WAIT_FOR_ANOTHER_WEIGHT_DAY,
            state.collecting?.nextUsefulAction,
        )
    }

    @Test
    fun `day seven reports weight span when distinct day count is sufficient`() {
        val plan = plan(start = "2026-07-16")
        val data = data(
            plan,
            weights = weightsOn("2026-07-18", "2026-07-20", "2026-07-23"),
            loggedDays = 4,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadReadinessForPlan(userId, plan, zone)).thenReturn(data)

        val state = service(true).resolve(userId)

        assertEquals(
            NutritionCoachReadinessReason.INSUFFICIENT_WEIGHT_SPAN,
            state.collecting?.readinessReason,
        )
        assertEquals(5, state.collecting?.weightSpanDays)
        assertEquals(NutritionCoachNextUsefulAction.EXTEND_WEIGHT_SPAN, state.collecting?.nextUsefulAction)
    }

    @Test
    fun `day seven stays learning when food and weight evidence are progressing`() {
        val plan = plan(start = "2026-07-16")
        val data = data(
            plan,
            weights = weightsOn("2026-07-16", "2026-07-19", "2026-07-23"),
            loggedDays = 4,
        )
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadReadinessForPlan(userId, plan, zone)).thenReturn(data)

        val state = service(true).resolve(userId)

        assertEquals(NutritionCoachState.LEARNING, state.state)
        assertNull(state.collecting)
    }

    @Test
    fun `recent coverage gate needs attention after fourteen day engine gates pass`() {
        val plan = plan()
        val data = data(plan, flatWeights(), 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(4)
        assertEquals(NutritionCoachState.NEEDS_ATTENTION, service(true).resolve(userId).state)
    }

    @Test
    fun `small correction with adequate coverage is on track`() {
        val plan = plan(delta = BigDecimal.ZERO)
        val data = data(plan, flatWeights(), 14).copy(averageLoggedCalories = BigDecimal("2000"))
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        val state = service(true).resolve(userId)
        assertEquals(NutritionCoachState.ON_TRACK, state.state)
        assertEquals(0, BigDecimal("2000.00").compareTo(state.measuredTdee))
    }

    @Test
    fun `a withheld low-confidence cut does not render the collecting card`() {
        // The collecting card shows three progress bars. For this user every gate passed
        // and the engine simply declined to act, so those bars would all read full and
        // the card would be claiming the system is waiting for data it already has.
        val plan = plan()
        // Three weigh-in days spanning 8, gaining: LOW confidence wanting a cut.
        val gaining = listOf(0L, 4L, 8L).mapIndexed { index, day ->
            RecalibrationWeightPoint(
                LocalDate.parse("2026-07-01").plusDays(day),
                BigDecimal.valueOf(80.0 + index * 0.4),
            )
        }
        val data = data(plan, gaining, 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)

        val state = service(locked = true, noChangeState = true).resolve(userId)
        assertEquals(NutritionCoachState.NO_CHANGE_RECOMMENDED, state.state)
        assertNull(state.measuredTdee)
    }

    @Test
    fun `withheld change stays on a legacy-safe neutral state until rollout is enabled`() {
        val plan = plan()
        val gaining = listOf(0L, 4L, 8L).mapIndexed { index, day ->
            RecalibrationWeightPoint(
                LocalDate.parse("2026-07-01").plusDays(day),
                BigDecimal.valueOf(80.0 + index * 0.4),
            )
        }
        val data = data(plan, gaining, 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)

        assertEquals(
            NutritionCoachState.INSIGHTS,
            service(locked = true, noChangeState = false).resolve(userId).state,
        )
    }

    @Test
    fun `entitled suggestion without pending producer row is preparing`() {
        val plan = plan()
        val data = data(plan, flatWeights(), 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        assertEquals(NutritionCoachState.RECOMMENDATION_PREPARING, service(true).resolve(userId).state)
    }

    @Test
    fun `entitled suggestion stays neutral while the producer is inside MIN_INTERVAL`() {
        val plan = plan()
        val data = data(plan, flatWeights(), 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        // Dismissed two days ago: the resolver's ACCEPTED short-circuit does not fire, but
        // RecalibrationService.evaluateAndSuggest still refuses for another five days.
        // Promising a suggestion here would be a lie, so the card stays neutral.
        val dismissed = pending(createdAt = now.minusSeconds(2 * 24 * 60 * 60))
            .also { it.status = RecalibrationSuggestionStatus.DISMISSED; it.decidedAt = now.minusSeconds(60) }
        Mockito.`when`(suggestions.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(dismissed)
        assertEquals(NutritionCoachState.INSIGHTS, service(true).resolve(userId).state)
    }

    @Test
    fun `disabled locked teaser leaves a free suggestion neutral`() {
        val plan = plan()
        val data = data(plan, flatWeights(), 14)
        Mockito.`when`(loader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(recalibration.pendingFor(userId)).thenReturn(null)
        Mockito.`when`(loader.loadForPlan(userId, plan, zone)).thenReturn(data)
        Mockito.`when`(loader.loggedDays(userId, data.today.minusDays(7), data.today)).thenReturn(7)
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(false)
        assertEquals(NutritionCoachState.INSIGHTS, service(false).resolve(userId).state)
    }

    private fun service(
        locked: Boolean,
        noChangeState: Boolean = true,
        factory: MeasuredTdeeObservationFactory = MeasuredTdeeObservationFactory(),
    ) = NutritionCoachStateResolver(
        loader,
        recalibration,
        suggestions,
        entitlement,
        insights,
        timezoneResolver,
        time,
        observedEnergy,
        planSchedule,
        trial,
        locked,
        noChangeState,
        MeasuredTdeeCoachCandidateResolver(factory, impressionMetrics),
        TrendExplanationObservationFactory(),
        scheduleTargetLoader,
        impressionMetrics,
        goalForecastEvidence,
        GoalForecastObservationFactory(),
    )

    private fun stubRollingEvidence(evidence: ObservedEnergyAnalysis) {
        val windows = listOf(ObservedEnergyWindowEvidence(evidence, emptyMap()))
        Mockito.`when`(observedEnergy.windowEvidence(userId, LocalDate.parse("2026-07-22"), null))
            .thenReturn(windows)
        Mockito.`when`(observedEnergy.selectAnalysis(windows)).thenReturn(evidence)
    }

    private fun plan(delta: BigDecimal? = BigDecimal("-500"), start: String = "2026-06-01") = NutritionPlanEntity(
        id = UUID.randomUUID(), userId = userId, startDate = LocalDate.parse(start), timezone = "Asia/Tehran",
        calories = BigDecimal("2000"), protein = BigDecimal("100"), carbs = BigDecimal("200"), fat = BigDecimal("70"), dailyEnergyDelta = delta,
        dailyEnergyDeltaSource = delta?.let { DailyEnergyDeltaSource.FORMULA_WIZARD },
    )

    private fun insufficientObservedEvidence() = ObservedEnergyAnalysis(
        status = ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD,
        windowDays = 28,
        windowStart = LocalDate.parse("2026-06-25"),
        windowEnd = LocalDate.parse("2026-07-22"),
        loggedDays = 0,
        loggedDaysRequired = 17,
        recentLoggedDays = 0,
        weighInDays = 0,
        weightSpanDays = 0,
        averageLoggedCalories = null,
        observedKgPerWeek = null,
        estimatedTdee = null,
        confidence = null,
        trendRSquared = null,
        trendStdErrorKgPerDay = null,
    )

    private fun sufficientObservedEvidence() = ObservedEnergyAnalysis(
        status = ObservedEnergyEvidenceStatus.SUFFICIENT,
        windowDays = 14,
        windowStart = LocalDate.parse("2026-07-09"),
        windowEnd = LocalDate.parse("2026-07-22"),
        loggedDays = 12,
        loggedDaysRequired = 9,
        recentLoggedDays = 6,
        weighInDays = 4,
        weightSpanDays = 10,
        averageLoggedCalories = BigDecimal("2200"),
        observedKgPerWeek = BigDecimal("-0.100"),
        estimatedTdee = BigDecimal("2310"),
        confidence = com.gyro.api.goal.application.recalibration.RecalibrationConfidence.LOW,
        trendRSquared = BigDecimal("0.8"),
        trendStdErrorKgPerDay = BigDecimal("0.01"),
    )

    private fun pending(createdAt: Instant = now) = PlanRecalibrationSuggestion(
        id = UUID.randomUUID(), userId = userId, nutritionPlanId = UUID.randomUUID(),
        suggestedCalories = BigDecimal("1800"), suggestedProtein = BigDecimal("90"), suggestedCarbs = BigDecimal("180"), suggestedFat = BigDecimal("63"),
        previousCalories = BigDecimal("2000"), previousProtein = BigDecimal("100"), previousCarbs = BigDecimal("200"), previousFat = BigDecimal("70"),
        createdAt = createdAt,
        expiresAt = now.plusSeconds(3600),
    )

    private fun data(
        plan: NutritionPlanEntity,
        weights: List<RecalibrationWeightPoint>,
        loggedDays: Int = 14,
    ) = RecalibrationData(
        plan = plan,
        today = LocalDate.parse("2026-07-23"),
        windowStart = LocalDate.parse("2026-07-09"),
        intakeThrough = LocalDate.parse("2026-07-22"),
        weightThrough = LocalDate.parse("2026-07-23"),
        weights = weights,
        loggedDays = loggedDays,
        averageLoggedCalories = BigDecimal("2200"),
        averageHistoricalTargetCalories = BigDecimal("2000"),
    )

    private fun flatWeights() = listOf(0L, 3L, 6L, 9L, 12L).map { RecalibrationWeightPoint(LocalDate.parse("2026-07-01").plusDays(it), BigDecimal("80")) }

    private fun weightsOn(vararg dates: String) = dates.map {
        RecalibrationWeightPoint(LocalDate.parse(it), BigDecimal("80"))
    }
}
