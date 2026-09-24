package com.gyro.api.goal.application.coach

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsight
import com.gyro.api.diary.application.DashboardInsightCandidate
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.diary.application.DashboardInsightsService
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysisService
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.ObservedEnergyWindowEvidence
import com.gyro.api.goal.application.recalibration.RecalibrationDataLoader
import com.gyro.api.goal.application.recalibration.RecalibrationEngine
import com.gyro.api.goal.application.recalibration.RecalibrationOutcome
import com.gyro.api.goal.application.recalibration.RecalibrationService
import com.gyro.api.goal.application.recalibration.RecalibrationWindowPolicy
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.goal.domain.hasCompleteCalculatorProvenance
import com.gyro.api.goal.domain.hasTrustedDailyEnergyDelta
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import com.gyro.api.goal.infrastructure.PlanRecalibrationSuggestionRepository
import com.gyro.api.goal.web.RecalibrationSuggestionResponse
import com.gyro.api.goal.web.toResponse
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.trial.TrialService
import com.gyro.api.user.application.UserTimezoneResolver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class NutritionCoachMode { FULL, INSIGHTS_ONLY }
enum class NutritionCoachAnalysisScope { ROLLING, TRIAL_HISTORY }
enum class NutritionCoachState {
    RECOMMENDATION, RECOMMENDATION_LOCKED, RECOMMENDATION_PREPARING, WAITING, LEARNING,
    COLLECTING_DATA, NEEDS_ATTENTION, ON_TRACK, NO_CHANGE_RECOMMENDED, OBSERVED_PROGRESS, INSIGHTS,
}
data class NutritionCoachObservedProgress(
    val status: ObservedEnergyEvidenceStatus,
    val windowDays: Int,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val loggedDays: Int,
    val loggedDaysRequired: Int,
    val weighInDays: Int,
    val weightSpanDays: Long,
    val averageIntakeCalories: BigDecimal?,
    val observedKgPerWeek: BigDecimal?,
    val estimatedTdee: BigDecimal?,
)
data class NutritionCoachAdvancedScheduleAccess(
    val degraded: Boolean,
    val degradedFrom: LocalDate?,
    val preserved: Boolean,
)
data class NutritionCoachWaiting(val appliedAt: Instant, val nextEvaluationAt: Instant)
data class NutritionCoachStateResult(
    val mode: NutritionCoachMode?,
    val state: NutritionCoachState?,
    val asOf: Instant,
    val collecting: NutritionCoachCollecting? = null,
    val recommendation: RecalibrationSuggestionResponse? = null,
    val waiting: NutritionCoachWaiting? = null,
    val measuredTdee: BigDecimal? = null,
    val analysisScope: NutritionCoachAnalysisScope? = null,
    val observedProgress: NutritionCoachObservedProgress? = null,
    val calculatorProvenanceComplete: Boolean = false,
    val advancedScheduleAccess: NutritionCoachAdvancedScheduleAccess? = null,
    val insights: List<DashboardInsight>,
)

@Service
class NutritionCoachStateService(
    private val cache: NutritionCoachStateCache,
    private val resolver: NutritionCoachStateResolver,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    private val issuedImpressionRegistry: CoachIssuedImpressionRegistry,
) {
    private val meterRegistry: MeterRegistry? by lazy { meterRegistryProvider.getIfAvailable() }

    fun stateFor(userId: UUID): NutritionCoachStateResult {
        val startedAt = System.nanoTime()
        try {
            val lookup = cache.get(userId)
            recordCacheOutcome(lookup.outcome)
            val state = lookup.value ?: computeAndCache(userId, lookup.generation)
            issuedImpressionRegistry.issue(
                userId = userId,
                impressions = state.insights.associate { it.impressionId to it.kind },
            )
            recordResponseState(state.state)
            return state
        } finally {
            recordDuration("gyro.goal.coach.request.duration", startedAt)
        }
    }

    private fun computeAndCache(userId: UUID, generation: Long): NutritionCoachStateResult {
        val startedAt = System.nanoTime()
        return try {
            resolver.resolve(userId).also { cache.put(userId, it, generation) }
        } finally {
            recordDuration("gyro.goal.coach.compute.duration", startedAt)
        }
    }

    private fun recordCacheOutcome(outcome: NutritionCoachCacheOutcome) {
        meterRegistry
            ?.counter("gyro.goal.coach.cache", "outcome", outcome.name.lowercase())
            ?.increment()
    }

    private fun recordResponseState(state: NutritionCoachState?) {
        meterRegistry
            ?.counter("gyro.goal.coach.responses", "state", state?.name ?: "NO_ACTIVE_PLAN")
            ?.increment()
    }

    private fun recordDuration(metric: String, startedAt: Long) {
        meterRegistry
            ?.timer(metric)
            ?.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
    }
}

@Service
class NutritionCoachStateResolver(
    private val dataLoader: RecalibrationDataLoader,
    private val recalibrationService: RecalibrationService,
    private val suggestionRepository: PlanRecalibrationSuggestionRepository,
    private val entitlementGateService: EntitlementGateService,
    private val dashboardInsightsService: DashboardInsightsService,
    private val userTimezoneResolver: UserTimezoneResolver,
    private val timeProvider: TimeProvider,
    private val observedEnergyAnalysisService: ObservedEnergyAnalysisService,
    private val planScheduleService: PlanScheduleService,
    private val trialService: TrialService,
    @Value("\${app.nutrition-coach.locked-recommendation-enabled:true}") private val lockedRecommendationEnabled: Boolean,
    @Value("\${app.nutrition-coach.no-change-state-enabled:false}") private val noChangeStateEnabled: Boolean,
    private val measuredTdeeCandidateResolver: MeasuredTdeeCoachCandidateResolver,
    private val trendExplanationObservationFactory: TrendExplanationObservationFactory,
    private val scheduleAwareDailyTargetLoader: ScheduleAwareDailyTargetLoader,
    private val impressionMetrics: CoachInsightImpressionMetrics,
    private val goalForecastEvidenceService: GoalForecastEvidenceService,
    private val goalForecastObservationFactory: GoalForecastObservationFactory,
) {
    @Transactional(readOnly = true)
    fun resolve(userId: UUID): NutritionCoachStateResult {
        val now = timeProvider.now()
        val zone = userTimezoneResolver.resolve(userId)
        val today = now.atZone(zone).toLocalDate()
        val plan = dataLoader.activePlanFor(userId, zone)
            ?: return result(
                mode = null,
                state = null,
                asOf = now,
                insights = dashboardInsightsService.insightsFor(userId, zone),
            )
        val goalRecalibrationEntitled by lazy {
            entitlementGateService.hasFeatureAccess(userId, RECALIBRATION_FEATURE)
        }
        val rollingWindows by lazy {
            observedEnergyAnalysisService.windowEvidence(userId, today.minusDays(1))
        }
        val rollingEvidence by lazy {
            observedEnergyAnalysisService.selectAnalysis(rollingWindows)
        }
        // The trend-explanation observation is available to every active plan, including
        // manual plans, and does not depend on recalibration entitlement or state. It uses
        // the completed rolling window ending yesterday unless a fixed cutoff is supplied.
        val rollingTrendCandidate: DashboardInsightCandidate<DashboardInsight>? by lazy {
            trendExplanationCandidate(userId, rollingWindows)
        }
        // The goal forecast reads only the saved goal and recent weights, so it is
        // available to every tier and does not require recalibration entitlement. It is
        // withheld from the fixed trial-history summary, where a current forecast would
        // be mixed into a frozen historical report.
        val goalForecastCandidate: DashboardInsightCandidate<DashboardInsight>? by lazy {
            goalForecastCandidate(userId, plan, today)
        }
        fun insightsForState(
            state: NutritionCoachState?,
            excludedKinds: Set<DashboardInsightKind> = emptySet(),
            asOfDate: LocalDate? = null,
            evidence: ObservedEnergyAnalysis? = null,
            allowMeasuredTdee: Boolean = true,
            trendCandidate: DashboardInsightCandidate<DashboardInsight>? = rollingTrendCandidate,
            forecastCandidate: DashboardInsightCandidate<DashboardInsight>? = goalForecastCandidate,
        ): List<DashboardInsight> {
            val candidate = measuredTdeeCandidateResolver.resolve(
                state = state,
                allowMeasuredTdee = allowMeasuredTdee,
                entitlementProvider = { goalRecalibrationEntitled },
                analysisProvider = { evidence ?: rollingEvidence },
            )
            val supplemental = listOfNotNull(candidate, trendCandidate, forecastCandidate)
            return if (supplemental.isEmpty()) {
                dashboardInsightsService.insightsFor(
                    userId = userId,
                    zone = zone,
                    excludedKinds = excludedKinds,
                    asOfDate = asOfDate,
                )
            } else {
                dashboardInsightsService.insightsFor(
                    userId = userId,
                    zone = zone,
                    excludedKinds = excludedKinds,
                    asOfDate = asOfDate,
                    supplementalCandidates = supplemental,
                )
            }
        }
        val scheduleAccess = planScheduleService.advancedScheduleAccess(userId, plan)
        fun planResult(
            mode: NutritionCoachMode?,
            state: NutritionCoachState?,
            excludedKinds: Set<DashboardInsightKind> = emptySet(),
            asOfDate: LocalDate? = null,
            evidence: ObservedEnergyAnalysis? = null,
            allowMeasuredTdee: Boolean = true,
            trendCandidate: DashboardInsightCandidate<DashboardInsight>? = rollingTrendCandidate,
            forecastCandidate: DashboardInsightCandidate<DashboardInsight>? = goalForecastCandidate,
            collecting: NutritionCoachCollecting? = null,
            recommendation: RecalibrationSuggestionResponse? = null,
            waiting: NutritionCoachWaiting? = null,
            measuredTdee: BigDecimal? = null,
            analysisScope: NutritionCoachAnalysisScope? = null,
            observedProgress: NutritionCoachObservedProgress? = null,
        ) = result(
            mode = mode,
            state = state,
            asOf = now,
            insights = insightsForState(
                state = state,
                excludedKinds = excludedKinds,
                asOfDate = asOfDate,
                evidence = evidence,
                allowMeasuredTdee = allowMeasuredTdee,
                trendCandidate = trendCandidate,
                forecastCandidate = forecastCandidate,
            ),
            collecting = collecting,
            recommendation = recommendation,
            waiting = waiting,
            measuredTdee = measuredTdee,
            analysisScope = analysisScope,
            observedProgress = observedProgress,
            calculatorProvenanceComplete = plan.hasCompleteCalculatorProvenance(),
            advancedScheduleAccess = scheduleAccess?.toCoachAccess(),
        )
        val redeemedTrial = scheduleAccess?.degradedFrom?.let {
            trialService.redeemedTrialPeriod(userId)
        }
        val trialDegradeDate = redeemedTrial?.expiresAt
            ?.atZone(zone)
            ?.toLocalDate()
        val expiredTrial = redeemedTrial != null &&
            !redeemedTrial.expiresAt.isAfter(now) &&
            scheduleAccess?.degradedFrom == trialDegradeDate
        if (expiredTrial) {
            val finalEntitledDate = scheduleAccess.degradedFrom.minusDays(1)
            val trialStartDate = redeemedTrial.grantedAt.atZone(zone).toLocalDate()
            val trialEarliestDate = maxOf(scheduleAccess.activeFrom, trialStartDate)
            val trialWindows = observedEnergyAnalysisService.windowEvidence(
                userId = userId,
                windowEnd = finalEntitledDate,
                earliestDate = trialEarliestDate,
            )
            val evidence = observedEnergyAnalysisService.selectAnalysis(trialWindows)
            return planResult(
                mode = NutritionCoachMode.INSIGHTS_ONLY,
                state = if (evidence.hasAnyEvidence()) NutritionCoachState.OBSERVED_PROGRESS else NutritionCoachState.INSIGHTS,
                asOfDate = scheduleAccess.degradedFrom,
                allowMeasuredTdee = false,
                // Fixed trial-history evidence: the same cutoff and earliest date, so no
                // post-expiry day enters the comparison.
                trendCandidate = trendExplanationCandidate(userId, trialWindows),
                // A frozen historical summary must not carry a live forecast.
                forecastCandidate = null,
                analysisScope = NutritionCoachAnalysisScope.TRIAL_HISTORY,
                observedProgress = evidence.toCoachProgress(includeTdee = false),
            )
        }
        if (!plan.hasTrustedDailyEnergyDelta()) {
            // Keep the existing manual-plan progress state while ensuring entitlement
            // is decided before this legacy evidence read can be reused as a numeric
            // observation.
            goalRecalibrationEntitled
            val evidence = rollingEvidence
            val collecting = evidence.toCollecting()
            return planResult(
                mode = NutritionCoachMode.INSIGHTS_ONLY,
                state = if (evidence.sufficient) NutritionCoachState.OBSERVED_PROGRESS else NutritionCoachState.COLLECTING_DATA,
                evidence = evidence,
                collecting = collecting,
                analysisScope = NutritionCoachAnalysisScope.ROLLING,
                observedProgress = evidence.toCoachProgress(includeTdee = false),
            )
        }

        val pending = recalibrationService.pendingFor(userId)
        if (pending != null) {
            if (goalRecalibrationEntitled) {
                return planResult(
                    NutritionCoachMode.FULL,
                    NutritionCoachState.RECOMMENDATION,
                    excludedKinds = setOf(DashboardInsightKind.CALORIE_ADHERENCE),
                    recommendation = pending.toResponse(),
                )
            }
            if (lockedRecommendationEnabled) {
                return planResult(NutritionCoachMode.FULL, NutritionCoachState.RECOMMENDATION_LOCKED)
            }
        }

        val latestSuggestion = suggestionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
        latestSuggestion
            ?.takeIf { it.status == RecalibrationSuggestionStatus.ACCEPTED }
            ?.decidedAt
            ?.takeIf { it.isAfter(now.minus(RecalibrationService.MIN_INTERVAL)) }
            ?.let { decidedAt ->
                return planResult(NutritionCoachMode.FULL, NutritionCoachState.WAITING,
                    waiting = NutritionCoachWaiting(decidedAt, decidedAt.plus(RecalibrationService.MIN_INTERVAL)))
            }

        val completedPlanDays = ChronoUnit.DAYS.between(plan.startDate, today)
            .coerceAtLeast(0)
            .toInt()
        if (completedPlanDays < NutritionCoachReadinessPolicy.READINESS_CHECK_DAY) {
            return planResult(NutritionCoachMode.FULL, NutritionCoachState.LEARNING)
        }
        val data = if (completedPlanDays < RecalibrationWindowPolicy.FOURTEEN_DAYS.days) {
            dataLoader.loadReadinessForPlan(userId, plan, zone)
        } else {
            dataLoader.loadForPlan(userId, plan, zone)
        }
            ?: return planResult(
                NutritionCoachMode.FULL,
                if (completedPlanDays < RecalibrationWindowPolicy.FOURTEEN_DAYS.days) {
                    NutritionCoachState.LEARNING
                } else {
                    NutritionCoachState.INSIGHTS
                },
            )
        val readiness = NutritionCoachReadinessPolicy.evaluate(data, completedPlanDays)
        if (completedPlanDays < RecalibrationWindowPolicy.FOURTEEN_DAYS.days) {
            return if (readiness == null) {
                planResult(NutritionCoachMode.FULL, NutritionCoachState.LEARNING)
            } else {
                planResult(
                    NutritionCoachMode.FULL,
                    NutritionCoachState.COLLECTING_DATA,
                    collecting = readiness,
                )
            }
        }
        when (val outcome = RecalibrationEngine.evaluate(data.input())) {
            is RecalibrationOutcome.NoSuggestion -> {
                if (outcome.reason !in NON_BLOCKING_REASONS) {
                    return planResult(NutritionCoachMode.FULL, NutritionCoachState.COLLECTING_DATA,
                        collecting = requireNotNull(readiness) {
                            "Blocking recalibration reason ${outcome.reason} must identify missing evidence."
                        })
                }
                val recentCoverage = dataLoader.loggedDays(userId, data.today.minusDays(7), data.today)
                if (recentCoverage < 7 * RECENT_ENGAGEMENT_RATIO) {
                    return planResult(NutritionCoachMode.FULL, NutritionCoachState.NEEDS_ATTENTION)
                }
                if (outcome.reason == "ADJUSTMENT_TOO_SMALL" && outcome.estimatedTdee != null) {
                    return planResult(
                        NutritionCoachMode.FULL,
                        NutritionCoachState.ON_TRACK,
                        measuredTdee = outcome.estimatedTdee.takeIf { goalRecalibrationEntitled },
                    )
                }
                return planResult(
                    NutritionCoachMode.FULL,
                    if (noChangeStateEnabled) {
                        NutritionCoachState.NO_CHANGE_RECOMMENDED
                    } else {
                        // Keep older PWA bundles on a recognized, neutral state until
                        // frontend support for NO_CHANGE_RECOMMENDED has rolled out.
                        NutritionCoachState.INSIGHTS
                    },
                )
            }
            is RecalibrationOutcome.Suggestion -> {
                val recentCoverage = dataLoader.loggedDays(userId, data.today.minusDays(7), data.today)
                if (recentCoverage < 7 * RECENT_ENGAGEMENT_RATIO) {
                    return planResult(NutritionCoachMode.FULL, NutritionCoachState.NEEDS_ATTENTION)
                }
                if (!goalRecalibrationEntitled) {
                    if (lockedRecommendationEnabled) {
                        return planResult(NutritionCoachMode.FULL, NutritionCoachState.RECOMMENDATION_LOCKED)
                    }
                    return planResult(NutritionCoachMode.FULL, NutritionCoachState.INSIGHTS)
                }
                // Entitled. Only promise a suggestion when the producer is free to create
                // one: it refuses for MIN_INTERVAL after ANY suggestion, including a
                // dismissed or expired one, while the ACCEPTED short-circuit above does not.
                // Claiming "preparing" in that gap would be false for up to seven days.
                val producerIsFree = latestSuggestion == null ||
                    !latestSuggestion.createdAt.isAfter(now.minus(RecalibrationService.MIN_INTERVAL))
                if (producerIsFree) {
                    return planResult(NutritionCoachMode.FULL, NutritionCoachState.RECOMMENDATION_PREPARING)
                }
                return planResult(NutritionCoachMode.FULL, NutritionCoachState.INSIGHTS)
            }
        }
    }

    /**
     * Produces the trend-explanation candidate for one evidence window, recording the
     * single terminal outcome. Schedule-aware targets are loaded once for the union of
     * candidate intake dates, so the ratio-of-sums comparison sees each date's own
     * historical target.
     */
    private fun trendExplanationCandidate(
        userId: UUID,
        windows: List<ObservedEnergyWindowEvidence>,
    ): DashboardInsightCandidate<DashboardInsight>? {
        val intakeDates = windows.flatMapTo(sortedSetOf()) { it.intakeByDate.keys }.toList()
        val targets = if (intakeDates.isEmpty()) {
            emptyMap()
        } else {
            scheduleAwareDailyTargetLoader.load(userId, intakeDates)
        }
        val outcome = trendExplanationObservationFactory.create(windows, targets)
        impressionMetrics.stateTrendExplanationOutcome(outcome.candidateOutcome)
        return (outcome as? TrendExplanationOutcome.Eligible)?.candidate
    }

    /**
     * Produces the goal-forecast candidate for the active plan, recording the single
     * terminal outcome. An unforecastable trend still produces an observation carrying
     * the four planned blocks; only an incompatible goal produces nothing.
     */
    private fun goalForecastCandidate(
        userId: UUID,
        plan: NutritionPlanEntity,
        today: LocalDate,
    ): DashboardInsightCandidate<DashboardInsight>? {
        val outcome = when (val evidence = goalForecastEvidenceService.evidenceFor(userId, plan, today)) {
            is GoalForecastEvidenceOutcome.Ineligible ->
                GoalForecastOutcome.Ineligible(evidence.outcome)
            is GoalForecastEvidenceOutcome.Eligible ->
                goalForecastObservationFactory.create(evidence.evidence)
        }
        impressionMetrics.stateGoalForecastOutcome(outcome.candidateOutcome)
        return (outcome as? GoalForecastOutcome.Eligible)?.candidate
    }

    private fun result(
        mode: NutritionCoachMode?, state: NutritionCoachState?, asOf: Instant, insights: List<DashboardInsight>,
        collecting: NutritionCoachCollecting? = null,
        recommendation: RecalibrationSuggestionResponse? = null,
        waiting: NutritionCoachWaiting? = null,
        measuredTdee: BigDecimal? = null,
        analysisScope: NutritionCoachAnalysisScope? = null,
        observedProgress: NutritionCoachObservedProgress? = null,
        calculatorProvenanceComplete: Boolean = false,
        advancedScheduleAccess: NutritionCoachAdvancedScheduleAccess? = null,
    ): NutritionCoachStateResult =
        NutritionCoachStateResult(
            mode = mode,
            state = state,
            asOf = asOf,
            collecting = collecting,
            recommendation = recommendation,
            waiting = waiting,
            measuredTdee = measuredTdee,
            analysisScope = analysisScope,
            observedProgress = observedProgress,
            calculatorProvenanceComplete = calculatorProvenanceComplete,
            advancedScheduleAccess = advancedScheduleAccess,
            insights = insights,
        )

    private fun ObservedEnergyAnalysis.hasAnyEvidence(): Boolean = loggedDays > 0 || weighInDays > 0

    private fun ObservedEnergyAnalysis.toCoachProgress(includeTdee: Boolean) = NutritionCoachObservedProgress(
        status = status,
        windowDays = windowDays,
        windowStart = windowStart,
        windowEnd = windowEnd,
        loggedDays = loggedDays,
        loggedDaysRequired = loggedDaysRequired,
        weighInDays = weighInDays,
        weightSpanDays = weightSpanDays,
        averageIntakeCalories = averageLoggedCalories,
        observedKgPerWeek = observedKgPerWeek,
        estimatedTdee = estimatedTdee.takeIf { includeTdee },
    )

    private fun ObservedEnergyAnalysis.toCollecting(): NutritionCoachCollecting = NutritionCoachCollecting(
        reason = status.name,
        weighIns = weighInDays,
        spanDays = weightSpanDays.toInt(),
        coveragePercent = loggedDays * 100 / windowDays.coerceAtLeast(1),
        foodEvidenceDays = loggedDays,
        foodEvidenceDaysRequired = loggedDaysRequired,
        weighInDays = weighInDays,
        weightSpanDays = weightSpanDays.toInt(),
        weighedInToday = false,
        readinessReason = when (status) {
            ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD -> NutritionCoachReadinessReason.INSUFFICIENT_FOOD_EVIDENCE
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_DAYS -> NutritionCoachReadinessReason.INSUFFICIENT_WEIGH_IN_DAYS
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_SPAN -> NutritionCoachReadinessReason.INSUFFICIENT_WEIGHT_SPAN
            ObservedEnergyEvidenceStatus.SUFFICIENT -> NutritionCoachReadinessReason.INSUFFICIENT_FOOD_EVIDENCE
        },
        nextUsefulAction = when (status) {
            ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD -> NutritionCoachNextUsefulAction.LOG_FOOD
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_DAYS,
            ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_SPAN,
            -> NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY
            ObservedEnergyEvidenceStatus.SUFFICIENT -> NutritionCoachNextUsefulAction.LOG_FOOD
        },
    )

    private fun PlanScheduleService.AdvancedScheduleAccess.toCoachAccess() = NutritionCoachAdvancedScheduleAccess(
        degraded = degradedFrom != null,
        degradedFrom = degradedFrom,
        preserved = preserved,
    )

    private companion object {
        const val RECALIBRATION_FEATURE = "goal_recalibration"

        /**
         * Whether the user is still engaged, which is a different question from whether
         * the estimate is good. It deliberately keeps 0.6 while the estimate-quality
         * threshold relaxed to the LOW tier.
         */
        const val RECENT_ENGAGEMENT_RATIO = 0.6

        /**
         * Reasons that do NOT mean "waiting for more data". Anything outside this set
         * renders the collecting card, whose three progress bars would be a lie for a
         * user who cleared every gate and was simply not given a suggestion. Adding a
         * reason therefore forces a decision here rather than defaulting to that card.
         */
        val NON_BLOCKING_REASONS = setOf(
            "ADJUSTMENT_TOO_SMALL",
            "LOW_CONFIDENCE_DECREASE_WITHHELD",
        )
    }
}
