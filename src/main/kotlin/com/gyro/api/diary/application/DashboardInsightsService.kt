package com.gyro.api.diary.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.daily_score.application.DailyScoreAnalyticsService
import com.gyro.api.daily_score.application.DailyScoreAnalyticsSummary
import com.gyro.api.diary.infrastructure.CoachInsightImpressionRepository
import com.gyro.api.diary.infrastructure.CoachInsightImpression
import com.gyro.api.diary.infrastructure.DiaryNutritionTotals
import com.gyro.api.diary.infrastructure.DiaryRepository
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTarget
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.domain.NutritionSuccessPolicy
import com.gyro.api.user.application.UserTimezoneResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Diary-owned summaries used by Coach without depending on recalibration state. */
@Service
class DashboardInsightsService(
    private val diaryRepository: DiaryRepository,
    private val dailyTargetLoader: ScheduleAwareDailyTargetLoader,
    private val dailyScoreAnalyticsService: DailyScoreAnalyticsService,
    private val impressionRepository: CoachInsightImpressionRepository,
    private val impressionMetrics: CoachInsightImpressionMetrics,
    private val userTimezoneResolver: UserTimezoneResolver,
    private val timeProvider: TimeProvider,
    private val weekendGapObservationFactory: WeekendGapObservationFactory,
) {
    @Transactional(readOnly = true)
    fun insightsFor(userId: UUID): List<DashboardInsight> {
        return insightsFor(userId, userTimezoneResolver.resolve(userId))
    }

    @Transactional(readOnly = true)
    fun insightsFor(
        userId: UUID,
        zone: ZoneId,
        excludedKinds: Set<DashboardInsightKind> = emptySet(),
        asOfDate: LocalDate? = null,
    ): List<DashboardInsight> = insightsFor(
        userId = userId,
        zone = zone,
        excludedKinds = excludedKinds,
        asOfDate = asOfDate,
        supplementalCandidates = emptyList(),
    )

    internal fun insightsFor(
        userId: UUID,
        zone: ZoneId,
        excludedKinds: Set<DashboardInsightKind>,
        asOfDate: LocalDate?,
        supplementalCandidates: List<DashboardInsightCandidate<DashboardInsight>> = emptyList(),
    ): List<DashboardInsight> {
        val today = asOfDate ?: timeProvider.today(zone)
        val recentImpressions = recentImpressions(userId, today)
        val candidates = mutableListOf<DashboardInsightCandidate<DashboardInsight>>()
        val streak = loggingStreak(userId, today)
        if (streak.value > 0) {
            candidates += DashboardInsightCandidate(
                insight = LoggingStreakInsight(
                    impressionId = CoachObservationFingerprintPolicy.loggingStreak(streak.value),
                    value = streak.value,
                    capped = streak.capped,
                ),
                magnitude = (streak.value.toDouble() / 14.0).coerceIn(0.25, 1.0),
            )
        }

        // One diary-totals read and one schedule-aware target read back every
        // observation over the shared rolling window.
        val evidence = rollingEvidence(userId, today)
        proteinConsistency(evidence)?.let { insight ->
            candidates += DashboardInsightCandidate(
                insight = insight,
                magnitude = (kotlin.math.abs(100 - insight.value).toDouble() / 100.0)
                    .coerceAtLeast(0.35),
            )
        }
        when (
            val weekendGap = weekendGapObservationFactory.create(
                periodStart = evidence.dates.first(),
                periodEnd = evidence.dates.last(),
                evidence = evidence.weekendGapDays(),
            )
        ) {
            is WeekendGapOutcome.Eligible -> candidates += weekendGap.candidate
            is WeekendGapOutcome.Suppressed -> impressionMetrics.stateObservationSuppressed(
                kind = DashboardInsightKind.WEEKEND_GAP,
                reason = weekendGap.reason.name,
            )
        }
        candidates += dailyScoreCandidates(userId, today)
        candidates += supplementalCandidates

        val eligibleCandidates = candidates.filter { it.insight.kind !in excludedKinds }
        val selected = if (eligibleCandidates.isEmpty()) {
            impressionMetrics.stateTipFallback()
            listOf(ObservationSelection(tipCandidate(today, recentImpressions)))
        } else {
            ObservationSaliencePolicy.rank(
                candidates = eligibleCandidates,
                impressions = recentImpressions,
                today = today,
            )
        }
        selected.forEach { selection ->
            impressionMetrics.stateObservationReturned(selection.candidate.insight.kind)
            selection.rotationOutcome?.let(impressionMetrics::stateRotation)
        }
        return selected.map { it.candidate.insight }
    }

    private fun dailyScoreCandidates(
        userId: UUID,
        today: LocalDate,
    ): List<DashboardInsightCandidate<DashboardInsight>> {
        val analytics = dailyScoreAnalyticsService.coachAnalytics(userId, today)
        val current = analytics.currentWindow ?: return emptyList()
        if (current.loggedDayCount == 0) {
            return emptyList()
        }

        val candidates = mutableListOf<DashboardInsightCandidate<DashboardInsight>>()
        analytics.currentWindowCalorieComparison?.let { comparison ->
            val roundedDelta = comparison.deltaPercent.setScale(0, RoundingMode.HALF_UP).toInt()
            candidates += DashboardInsightCandidate(
                insight = CalorieAdherenceInsight(
                    impressionId = CoachObservationFingerprintPolicy.calorieComparison(
                        comparison.periodEnd,
                        comparison.deltaPercent,
                    ),
                    value = roundedDelta,
                    averageIntakeCalories = comparison.averageIntakeCalories
                        .setScale(0, RoundingMode.HALF_UP)
                        .toInt(),
                    averageTargetCalories = comparison.averageTargetCalories
                        .setScale(0, RoundingMode.HALF_UP)
                        .toInt(),
                    deltaPercent = comparison.deltaPercent,
                    loggedDayCount = comparison.loggedDayCount,
                    periodStart = comparison.periodStart,
                    periodEnd = comparison.periodEnd,
                ),
                // The raw scale-6 delta, rather than the API-rounded value, drives
                // salience so close boundary values rank deterministically.
                magnitude = (0.5 + comparison.deltaPercent.abs().toDouble() / 50.0)
                    .coerceAtMost(1.0),
            )
        }

        if (current.formulaVersions.size == 1) {
            scoreTrend(current, analytics.previousWindow)?.let { trend ->
                candidates += DashboardInsightCandidate(
                    insight = trend,
                    magnitude = (kotlin.math.abs(trend.value).toDouble() / 20.0)
                        .coerceIn(0.35, 1.0),
                )
            }

            current.bestDays
                .firstOrNull { it.breakdown.loggedMealCount > 0 }
                ?.let { best ->
                    candidates += DashboardInsightCandidate(
                        insight = BestDayInsight(
                            impressionId = CoachObservationFingerprintPolicy.bestDay(
                                current.to,
                                best.localDate,
                                best.score,
                            ),
                            value = best.score,
                            date = best.localDate,
                            loggedDayCount = current.loggedDayCount,
                            periodStart = current.from,
                            periodEnd = current.to,
                        ),
                        magnitude = (best.score.toDouble() / 100.0).coerceAtLeast(0.35),
                    )
                }
        }
        return candidates
    }

    private fun scoreTrend(
        current: DailyScoreAnalyticsSummary,
        previous: DailyScoreAnalyticsSummary?,
    ): ScoreTrendInsight? {
        previous ?: return null
        if (
            current.formulaVersions.keys != previous.formulaVersions.keys ||
            current.loggedDayCount < 2 ||
            previous.loggedDayCount < 2
        ) {
            return null
        }
        val currentAverage = current.averageScore ?: return null
        val previousAverage = previous.averageScore ?: return null
        val difference = currentAverage.subtract(previousAverage)
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
        if (kotlin.math.abs(difference) < MIN_SCORE_TREND_POINTS) {
            return null
        }
        return ScoreTrendInsight(
            impressionId = CoachObservationFingerprintPolicy.scoreTrend(current.to, difference),
            value = difference,
            trend = if (difference > 0) DashboardInsightTrend.UP else DashboardInsightTrend.DOWN,
            loggedDayCount = current.loggedDayCount,
            periodStart = current.from,
            periodEnd = current.to,
            previousLoggedDayCount = previous.loggedDayCount,
        )
    }

    private fun recentImpressions(
        userId: UUID,
        today: LocalDate,
    ): List<CoachInsightImpression> =
        try {
            impressionRepository.shownSince(
                userId = userId,
                sinceInclusive = today.minusDays(ObservationSaliencePolicy.NOVELTY_DAYS - 1L),
            )
        } catch (exception: Exception) {
            impressionMetrics.lookupFailed()
            logger.warn(
                "Coach insight impression lookup failed for user {}.",
                userId,
                exception,
            )
            emptyList()
        }

    private fun tipCandidate(
        today: LocalDate,
        recentImpressions: List<CoachInsightImpression>,
    ): DashboardInsightCandidate<CoachTipInsight> {
        val recentKeys = recentImpressions.mapTo(mutableSetOf()) { it.key }
        val start = Math.floorMod(today.toEpochDay(), COACH_TIP_COUNT.toLong()).toInt()
        val pinnedIndex = recentImpressions.firstOrNull {
            it.shownOn == today && it.key.startsWith(COACH_TIP_KEY_PREFIX)
        }?.key?.removePrefix(COACH_TIP_KEY_PREFIX)?.toIntOrNull()
        val index = pinnedIndex ?: (0 until COACH_TIP_COUNT)
            .map { offset -> (start + offset) % COACH_TIP_COUNT }
            .firstOrNull { "$COACH_TIP_KEY_PREFIX$it" !in recentKeys }
            ?: start
        return DashboardInsightCandidate(
            insight = CoachTipInsight(
                impressionId = "$COACH_TIP_KEY_PREFIX$index",
                value = index,
            ),
            magnitude = 1.0,
        )
    }

    private fun loggingStreak(userId: UUID, today: LocalDate): LoggingStreak {
        val loggedDates = diaryRepository.loggedDatesBetween(
            userId,
            today.minusDays((MAX_STREAK_DAYS + 1).toLong()),
            today,
        )
        var cursor = if (today in loggedDates) today else today.minusDays(1)
        var value = 0
        while (value < MAX_STREAK_DAYS && cursor in loggedDates) {
            value += 1
            cursor = cursor.minusDays(1)
        }
        return LoggingStreak(value, capped = value == MAX_STREAK_DAYS && cursor in loggedDates)
    }

    /**
     * The rolling 14 completed user-local days ending yesterday, split into the
     * two 7-day halves the protein trend compares.
     */
    private fun rollingEvidence(userId: UUID, today: LocalDate): RollingEvidence {
        val currentDates = completedDatesEndingBefore(today, 7)
        val previousDates = completedDatesEndingBefore(today.minusDays(7), 7)
        val allDates = previousDates + currentDates
        return RollingEvidence(
            currentDates = currentDates,
            previousDates = previousDates,
            totals = diaryRepository.nutritionTotalsByDate(userId, allDates.first(), allDates.last()),
            targets = dailyTargetLoader.load(userId, allDates),
        )
    }

    private fun proteinConsistency(evidence: RollingEvidence): ProteinConsistencyInsight? {
        val currentDates = evidence.currentDates
        val totals = evidence.totals
        val targets = evidence.targets
        val current = proteinRate(currentDates, totals, targets)
        if (current == null) return null
        val previous = proteinRate(evidence.previousDates, totals, targets)
        val trend = previous?.let {
            when {
                current.percentage - it.percentage >= 5 -> DashboardInsightTrend.UP
                current.percentage - it.percentage <= -5 -> DashboardInsightTrend.DOWN
                else -> DashboardInsightTrend.STABLE
            }
        }
        return ProteinConsistencyInsight(
            impressionId = CoachObservationFingerprintPolicy.proteinConsistency(
                currentDates.last(),
                current.percentage,
                trend,
            ),
            value = current.percentage,
            trend = trend,
            loggedDayCount = current.loggedDayCount,
            periodStart = currentDates.first(),
            periodEnd = currentDates.last(),
        )
    }

    private fun proteinRate(
        dates: List<LocalDate>,
        totals: Map<LocalDate, DiaryNutritionTotals>,
        targets: Map<LocalDate, ScheduleAwareDailyTarget>,
    ): ProteinRate? {
        val eligible = dates.mapNotNull { date ->
            val logged = totals[date] ?: return@mapNotNull null
            val target = targets[date]?.target?.protein ?: return@mapNotNull null
            if (!logged.logged || target.signum() <= 0) return@mapNotNull null
            logged.protein >= target.multiply(NutritionSuccessPolicy.PROTEIN_TARGET_RATIO)
        }
        if (eligible.isEmpty()) return null
        val percentage = BigDecimal(eligible.count { it })
            .multiply(BigDecimal(100))
            .divide(BigDecimal(eligible.size), 0, RoundingMode.HALF_UP)
            .toInt()
        return ProteinRate(percentage, eligible.size)
    }

    private fun completedDatesEndingBefore(endExclusive: LocalDate, days: Long): List<LocalDate> {
        val first = endExclusive.minusDays(days)
        return generateSequence(first) { it.plusDays(1).takeUnless { next -> !next.isBefore(endExclusive) } }.toList()
    }

    private data class LoggingStreak(val value: Int, val capped: Boolean)
    private data class ProteinRate(val percentage: Int, val loggedDayCount: Int)

    private data class RollingEvidence(
        val currentDates: List<LocalDate>,
        val previousDates: List<LocalDate>,
        val totals: Map<LocalDate, DiaryNutritionTotals>,
        val targets: Map<LocalDate, ScheduleAwareDailyTarget>,
    ) {
        val dates: List<LocalDate> = previousDates + currentDates

        /**
         * Days that carry both a diary entry and a historical calorie target.
         * A pre-plan date, or one whose plan resolved no target, is dropped
         * rather than compared against a target it never had.
         */
        fun weekendGapDays(): List<WeekendGapDayEvidence> = dates.mapNotNull { date ->
            val logged = totals[date]?.takeIf { it.logged } ?: return@mapNotNull null
            val target = targets[date]?.target?.calories ?: return@mapNotNull null
            if (target.signum() <= 0) return@mapNotNull null
            WeekendGapDayEvidence(date, logged.calories, target)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DashboardInsightsService::class.java)
        const val MAX_STREAK_DAYS = 365
        const val MIN_SCORE_TREND_POINTS = 3
        const val COACH_TIP_COUNT = 40
        const val COACH_TIP_KEY_PREFIX = "COACH_TIP_"
    }
}

internal data class DashboardInsightCandidate<out T : DashboardInsight>(
    val insight: T,
    val magnitude: Double,
)

internal enum class ObservationRotationOutcome {
    MATERIAL_CHANGE,
    REPEATED_FINGERPRINT,
}

internal data class ObservationSelection(
    val candidate: DashboardInsightCandidate<DashboardInsight>,
    val rotationOutcome: ObservationRotationOutcome? = null,
)

/**
 * Central salience policy for coach observations.
 *
 * Ranking follows the product policy lexicographically: valid observations shown
 * today stay pinned, meaningful changes and unseen fingerprints lead, and a
 * recently shown kind receives a short rotation penalty without becoming
 * ineligible. Magnitude and actionability break ties inside each novelty tier.
 */
internal object ObservationSaliencePolicy {
    const val NOVELTY_DAYS = 7L
    private const val KIND_COOLDOWN_DAYS = 2L
    private const val MAX_OBSERVATIONS = 2
    private val actionability = mapOf(
        DashboardInsightKind.CALORIE_ADHERENCE to 1.0,
        DashboardInsightKind.SCORE_TREND to 0.9,
        DashboardInsightKind.PROTEIN_CONSISTENCY to 0.8,
        DashboardInsightKind.WEEKEND_GAP to 0.75,
        DashboardInsightKind.BEST_DAY to 0.7,
        DashboardInsightKind.LOGGING_STREAK to 0.55,
        DashboardInsightKind.COACH_TIP to 0.2,
        DashboardInsightKind.MEASURED_TDEE to 0.65,
        DashboardInsightKind.TREND_EXPLANATION to 0.80,
        // Below the direct adherence patterns, which name something the person can act
        // on today, and above generic tips and logging streaks.
        DashboardInsightKind.GOAL_FORECAST to 0.70,
    )

    fun rank(
        candidates: List<DashboardInsightCandidate<DashboardInsight>>,
        impressions: List<CoachInsightImpression>,
        today: LocalDate,
    ): List<ObservationSelection> {
        val ranked = candidates.map { candidate ->
            val fingerprint = candidate.insight.impressionId
            val kind = candidate.insight.kind
            val kindHistory = impressions.filter {
                CoachObservationFingerprintPolicy.kindOf(it.key) == kind
            }
            val shownToday = kindHistory.any { it.key == fingerprint && it.shownOn == today }
            val shownRecently = kindHistory.any { it.key == fingerprint }
            val latestMaterialSignature = kindHistory.firstNotNullOfOrNull {
                CoachObservationFingerprintPolicy.materialSignature(it.key)
            }
            val materialChange = latestMaterialSignature != null &&
                latestMaterialSignature != CoachObservationFingerprintPolicy.materialSignature(fingerprint)
            val kindOnCooldown = kindHistory.any {
                it.shownOn >= today.minusDays(KIND_COOLDOWN_DAYS - 1L)
            }

            RankedCandidate(
                candidate = candidate,
                materialChange = materialChange,
                repeatedFingerprint = shownRecently,
                noveltyTier = when {
                    shownToday -> 5
                    materialChange -> 4
                    !shownRecently && !kindOnCooldown -> 3
                    !shownRecently -> 2
                    !kindOnCooldown -> 1
                    else -> 0
                },
            )
        }

        return ranked.sortedWith(
            compareByDescending<RankedCandidate> { it.noveltyTier }
                .thenByDescending {
                    it.candidate.magnitude * actionability.getValue(it.candidate.insight.kind)
                }
                .thenBy { it.candidate.insight.kind.ordinal }
        ).take(MAX_OBSERVATIONS)
            .map {
                ObservationSelection(
                    candidate = it.candidate,
                    rotationOutcome = when {
                        it.materialChange -> ObservationRotationOutcome.MATERIAL_CHANGE
                        it.repeatedFingerprint -> ObservationRotationOutcome.REPEATED_FINGERPRINT
                        else -> null
                    },
                )
            }
    }

    private data class RankedCandidate(
        val candidate: DashboardInsightCandidate<DashboardInsight>,
        val noveltyTier: Int,
        val materialChange: Boolean,
        val repeatedFingerprint: Boolean,
    )
}
