package com.gyro.api.daily_score.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
class DailyScoreAnalyticsService(
    private val dailyScoreService: DailyScoreService,
) {
    @Transactional
    fun summary(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): DailyScoreAnalyticsSummary {
        val scores = dailyScoreService.finalizedScoresForRange(
            userId = userId,
            from = from,
            to = to,
        )
        return summary(from, to, scores)
    }

    @Transactional
    fun coachAnalytics(
        userId: UUID,
        today: LocalDate,
    ): DailyScoreCoachAnalytics {
        val yesterday = today.minusDays(1)
        val currentWindowStart = yesterday.minusDays(COACH_WINDOW_DAYS - 1L)
        val previousWindowEnd = currentWindowStart.minusDays(1)
        val previousWindowStart = previousWindowEnd.minusDays(COACH_WINDOW_DAYS - 1L)

        val scores = dailyScoreService.finalizedScoresForRange(
            userId = userId,
            from = previousWindowStart,
            to = yesterday,
        )
        // Coach observations use only eligible logged days inside fixed calendar
        // windows. Sparse logging must not pull older records into the period.
        val eligibleScores = scores.filter { it.breakdown.loggedMealCount > 0 }
        val previousScores = eligibleScores.filter { !it.localDate.isAfter(previousWindowEnd) }
        val currentScores = eligibleScores.filter { !it.localDate.isBefore(currentWindowStart) }
        return DailyScoreCoachAnalytics(
            currentWindow = currentScores
                .takeIf(List<DailyScoreReadModel>::isNotEmpty)
                ?.let { summary(currentWindowStart, yesterday, it) },
            previousWindow = previousScores
                .takeIf(List<DailyScoreReadModel>::isNotEmpty)
                ?.let { summary(previousWindowStart, previousWindowEnd, it) },
            currentWindowCalorieComparison = calorieTargetComparison(
                scores = currentScores,
                periodStart = currentWindowStart,
                periodEnd = yesterday,
            ),
        )
    }

    private fun calorieTargetComparison(
        scores: List<DailyScoreReadModel>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): DailyScoreCalorieTargetComparison? {
        val eligible = scores.filter {
            it.breakdown.loggedMealCount > 0 &&
                it.breakdown.targetCalories != null &&
                it.breakdown.targetCalories > BigDecimal.ZERO
        }
        if (eligible.size < MIN_COACH_COMPARISON_DAYS) {
            return null
        }

        val sumIntake = eligible.fold(BigDecimal.ZERO) { total, score ->
            total.add(score.breakdown.totalCalories)
        }
        val sumTarget = eligible.fold(BigDecimal.ZERO) { total, score ->
            total.add(requireNotNull(score.breakdown.targetCalories))
        }
        val count = BigDecimal(eligible.size)
        val deltaPercent = sumIntake
            .subtract(sumTarget)
            .multiply(ONE_HUNDRED)
            .divide(sumTarget, COACH_COMPARISON_SCALE, RoundingMode.HALF_UP)

        return DailyScoreCalorieTargetComparison(
            averageIntakeCalories = sumIntake.divide(count, COACH_COMPARISON_SCALE, RoundingMode.HALF_UP),
            averageTargetCalories = sumTarget.divide(count, COACH_COMPARISON_SCALE, RoundingMode.HALF_UP),
            deltaPercent = deltaPercent,
            loggedDayCount = eligible.size,
            periodStart = periodStart,
            periodEnd = periodEnd,
        )
    }

    private fun summary(
        from: LocalDate,
        to: LocalDate,
        scores: List<DailyScoreReadModel>,
    ): DailyScoreAnalyticsSummary {
        val distribution = DailyScoreBand.entries.associateWith { band ->
            scores.count { it.band == band }
        }
        return DailyScoreAnalyticsSummary(
            from = from,
            to = to,
            scoreCount = scores.size,
            loggedDayCount = scores.count { it.breakdown.loggedMealCount > 0 },
            averageScore = scores.map { BigDecimal(it.score) }.average(),
            averageCalorieScore = scores.mapNotNull { it.breakdown.calorieScore?.let(::BigDecimal) }.average(),
            averageProteinScore = scores.mapNotNull { it.breakdown.proteinScore?.let(::BigDecimal) }.average(),
            averageLoggingConsistencyScore = scores.map { BigDecimal(it.breakdown.loggingCompletenessScore) }.average(),
            formulaVersions = scores.groupingBy { it.formulaVersion }.eachCount().toSortedMap(),
            distribution = distribution,
            weeklyAverages = scores.periodAverages(
                keySelector = { it.localDate.saturdayWeekStart() },
                endSelector = { it.plusDays(6).coerceAtMost(to) },
            ),
            monthlyAverages = scores.periodAverages(
                keySelector = { YearMonth.from(it.localDate).atDay(1) },
                endSelector = { YearMonth.from(it).atEndOfMonth().coerceAtMost(to) },
            ),
            bestDays = scores.sortedWith(
                compareByDescending<DailyScoreReadModel> { it.score }
                    .thenByDescending { it.breakdown.loggedMealCount > 0 }
                    .thenBy { it.localDate }
            ).take(5),
            worstDays = scores.sortedWith(compareBy<DailyScoreReadModel> { it.score }.thenBy { it.localDate }).take(5),
        )
    }

    private fun List<BigDecimal>.average(): BigDecimal? {
        if (isEmpty()) {
            return null
        }
        return reduce(BigDecimal::add).divide(BigDecimal(size), 2, RoundingMode.HALF_UP)
    }

    private fun List<DailyScoreReadModel>.periodAverages(
        keySelector: (DailyScoreReadModel) -> LocalDate,
        endSelector: (LocalDate) -> LocalDate,
    ): List<DailyScorePeriodAverage> {
        return groupBy(keySelector)
            .toSortedMap()
            .map { (periodStart, periodScores) ->
                DailyScorePeriodAverage(
                    from = periodStart,
                    to = endSelector(periodStart),
                    scoreCount = periodScores.size,
                    averageScore = periodScores.map { BigDecimal(it.score) }.average() ?: BigDecimal.ZERO,
                )
            }
    }

    internal fun LocalDate.saturdayWeekStart(): LocalDate {
        val daysSinceSaturday = (dayOfWeek.value + 1) % 7
        return minusDays(daysSinceSaturday.toLong())
    }

    private companion object {
        const val COACH_WINDOW_DAYS = 7L
        const val MIN_COACH_COMPARISON_DAYS = 2
        const val COACH_COMPARISON_SCALE = 6
        val ONE_HUNDRED: BigDecimal = BigDecimal(100)
    }
}
