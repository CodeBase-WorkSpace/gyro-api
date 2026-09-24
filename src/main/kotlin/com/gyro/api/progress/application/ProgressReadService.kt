package com.gyro.api.progress.application

import com.gyro.api.common.error.InvalidProgressRangeException
import com.gyro.api.common.trend.LinearTrend
import com.gyro.api.common.trend.LinearTrendFit
import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.progress.infrastructure.ProgressReadRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*

data class ProgressDateRange(
    val from: LocalDate,
    val to: LocalDate,
)

data class NutritionProgressReadModel(
    val range: ProgressDateRange,
    val loggedDayCount: Int,
    val missingDayCount: Int,
    val totals: NutritionProgressTotals,
    val averagePerDay: NutritionProgressTotals,
    val averagePerLoggedDay: NutritionProgressTotals?,
    val minDailyTotals: NutritionProgressTotals?,
    val maxDailyTotals: NutritionProgressTotals?,
    val dailyPoints: List<NutritionProgressDailyPoint>,
)

data class NutritionBatchProgressReadModel(
    val requestId: String,
    val period: NutritionProgressPeriod,
    val timezone: String,
    val progress: NutritionProgressReadModel,
)

data class NutritionProgressTotals(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
) {
    companion object {
        fun zero(): NutritionProgressTotals {
            return NutritionProgressTotals(
                calories = BigDecimal("0.00"),
                protein = BigDecimal("0.000"),
                carbs = BigDecimal("0.000"),
                fat = BigDecimal("0.000"),
                fiber = BigDecimal("0.000"),
                sugar = BigDecimal("0.000"),
                sodium = BigDecimal("0.000"),
            )
        }
    }
}

data class NutritionProgressDailyPoint(
    val date: LocalDate,
    val logged: Boolean,
    val totals: NutritionProgressTotals,
    val goal: NutritionProgressDailyGoal?,
)

data class NutritionProgressDailyGoal(
    val configured: Boolean,
    val targets: NutritionTargetsReadModel?,
    val adherence: NutritionProgressGoalAdherence?,
)

data class NutritionProgressGoalAdherence(
    val caloriesDelta: BigDecimal,
    val proteinDelta: BigDecimal,
    val carbsDelta: BigDecimal,
    val fatDelta: BigDecimal,
    val fiberDelta: BigDecimal?,
)

data class WeightProgressReadModel(
    val range: ProgressDateRange,
    val measurements: List<WeightProgressPoint>,
    val latestMeasurementDate: LocalDate?,
    val startWeightKg: BigDecimal?,
    val endWeightKg: BigDecimal?,
    val absoluteChangeKg: BigDecimal?,
    val percentageChange: BigDecimal?,
    val trend: WeightTrendDirection,
    val missingDayCount: Int,
    /**
     * Fitted line over the requested range, or null below
     * [ProgressReadService.MIN_TREND_OBSERVED_DAYS] observed days. Independent of
     * [trend], which stays a first-to-last endpoint comparison.
     */
    val trendFit: LinearTrendFit?,
)

data class WeightProgressPoint(
    val date: LocalDate,
    val weightKg: BigDecimal,
)

enum class WeightTrendDirection {
    UP,
    DOWN,
    FLAT,
    INSUFFICIENT_DATA,
}

@Service
class ProgressReadService(
    private val progressReadRepository: ProgressReadRepository,
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val planScheduleService: PlanScheduleService,
) {

    @Transactional(readOnly = true)
    fun nutritionProgress(
        userId: UUID,
        range: ProgressDateRange,
    ): NutritionProgressReadModel {
        validateRange(range)
        val dailyPoints = progressReadRepository.loadNutritionDailyTotals(
            userId = userId,
            from = range.from,
            to = range.to,
        )
        val dates = range.dates()

        return buildNutritionProgress(
            range = range,
            dailyPointsByDate = dailyPoints.associateBy { it.date },
            dailyTargets = dailyTargets(
                userId = userId,
                dates = dates,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun nutritionProgressBatch(
        userId: UUID,
        requests: List<ResolvedNutritionBatchProgressRequest>,
    ): List<NutritionBatchProgressReadModel> {
        requests.forEach { validateRange(it.range) }
        if (requests.isEmpty()) {
            return emptyList()
        }
        val ranges = requests.map { it.range }
        val dailyPointsByDate = progressReadRepository.loadNutritionDailyTotals(
            userId = userId,
            ranges = ranges,
        ).associateBy { it.date }
        val dailyTargets = dailyTargets(
            userId = userId,
            dates = ranges.flatMap { it.dates() }.distinct(),
        )

        return requests.map { request ->
            NutritionBatchProgressReadModel(
                requestId = request.requestId,
                period = request.period,
                timezone = request.timezone,
                progress = buildNutritionProgress(
                    range = request.range,
                    dailyPointsByDate = dailyPointsByDate,
                    dailyTargets = dailyTargets,
                ),
            )
        }
    }

    private fun buildNutritionProgress(
        range: ProgressDateRange,
        dailyPointsByDate: Map<LocalDate, NutritionProgressDailyPoint>,
        dailyTargets: Map<LocalDate, DailyTargetReadModel>,
    ): NutritionProgressReadModel {
        val completePoints = range.dates().map { date ->
            dailyPointsByDate[date] ?: NutritionProgressDailyPoint(
                date = date,
                logged = false,
                totals = NutritionProgressTotals.zero(),
                goal = null,
            )
        }
        val pointsWithGoals = completePoints.map { point ->
            point.copy(goal = dailyGoal(point = point, target = dailyTargets[point.date]))
        }
        val loggedTotals = pointsWithGoals.filter { it.logged }.map { it.totals }
        val calendarTotals = pointsWithGoals.map { it.totals }
        val totals = pointsWithGoals.map { it.totals }.sum()

        return NutritionProgressReadModel(
            range = range,
            loggedDayCount = pointsWithGoals.count { it.logged },
            missingDayCount = range.dayCount() - pointsWithGoals.count { it.logged },
            totals = totals,
            averagePerDay = totals.divideBy(range.dayCount()),
            averagePerLoggedDay = loggedTotals.takeIf { it.isNotEmpty() }?.sum()?.divideBy(loggedTotals.size),
            minDailyTotals = calendarTotals.minByOrNull { it.calories },
            maxDailyTotals = calendarTotals.maxByOrNull { it.calories },
            dailyPoints = pointsWithGoals,
        )
    }

    @Transactional(readOnly = true)
    fun weightProgress(
        userId: UUID,
        range: ProgressDateRange,
    ): WeightProgressReadModel {
        validateRange(range)
        val measurements = progressReadRepository.loadWeightPoints(
            userId = userId,
            from = range.from,
            to = range.to,
        )
        val start = measurements.firstOrNull()?.weightKg
        val end = measurements.lastOrNull()?.weightKg
        val absoluteChange = if (start != null && end != null) {
            end.subtract(start).setScale(3, RoundingMode.HALF_UP)
        } else {
            null
        }
        val percentageChange = if (start != null && absoluteChange != null && start.signum() != 0) {
            absoluteChange.multiply(BigDecimal("100"))
                .divide(start, 3, RoundingMode.HALF_UP)
        } else {
            null
        }

        return WeightProgressReadModel(
            range = range,
            measurements = measurements,
            latestMeasurementDate = progressReadRepository.loadLatestWeightMeasurementDate(userId),
            startWeightKg = start,
            endWeightKg = end,
            absoluteChangeKg = absoluteChange,
            percentageChange = percentageChange,
            trend = absoluteChange.toTrend(measurements.size),
            missingDayCount = range.dayCount() - measurements.map { it.date }.toSet().size,
            trendFit = fitTrend(measurements),
        )
    }

    /**
     * Fits the already-loaded measurements, so this adds no queries.
     *
     * Two observed days are withheld deliberately: a line through two points carries
     * exactly the same information as the endpoint change the summary already reports,
     * so presenting it as a trend would imply evidence that does not exist.
     */
    private fun fitTrend(measurements: List<WeightProgressPoint>): LinearTrendFit? {
        val fit = LinearTrend.fitDaily(measurements.map { TrendPoint(it.date, it.weightKg) })
        return fit?.takeIf { it.observedDayCount >= MIN_TREND_OBSERVED_DAYS }
    }

    private fun validateRange(range: ProgressDateRange) {
        if (range.to.isBefore(range.from)) {
            throw InvalidProgressRangeException("to must be on or after from.")
        }
        if (range.dayCount() > MAX_PROGRESS_DAYS) {
            throw InvalidProgressRangeException("Progress range cannot exceed $MAX_PROGRESS_DAYS days.")
        }
    }

    private fun ProgressDateRange.dayCount(): Int {
        return ChronoUnit.DAYS.between(from, to).toInt() + 1
    }

    private fun ProgressDateRange.dates(): List<LocalDate> {
        return generateSequence(from) { date ->
            date.plusDays(1).takeIf { !it.isAfter(to) }
        }.toList()
    }

    private fun dailyTargets(
        userId: UUID,
        dates: List<LocalDate>,
    ): Map<LocalDate, DailyTargetReadModel> {
        val latestDate = dates.maxOrNull() ?: return emptyMap()
        val plans = nutritionPlanRepository.findByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(
            userId = userId,
            startDate = latestDate,
        )
        if (plans.isEmpty()) {
            return emptyMap()
        }

        return dates
            .mapNotNull { date ->
                plans.firstOrNull { plan -> !plan.startDate.isAfter(date) }
                    ?.let { plan -> date to plan }
            }
            .groupBy(
                keySelector = { (_, plan) -> requireNotNull(plan.id) },
                valueTransform = { (date, plan) -> date to plan },
            )
            .values
            .flatMap { datePlans ->
                val plan = datePlans.first().second
                planScheduleService.resolveDailyTargets(
                    userId = userId,
                    plan = plan,
                    activeDates = datePlans.map { it.first },
                ).entries
            }
            .associate { it.key to it.value }
    }

    private fun dailyGoal(
        point: NutritionProgressDailyPoint,
        target: DailyTargetReadModel?,
    ): NutritionProgressDailyGoal? {
        if (target == null) {
            return null
        }
        val adherence = if (point.logged) {
            NutritionProgressGoalAdherence(
                caloriesDelta = point.totals.calories.subtract(target.calories).setScale(2, RoundingMode.HALF_UP),
                proteinDelta = point.totals.protein.subtract(target.protein).setScale(3, RoundingMode.HALF_UP),
                carbsDelta = point.totals.carbs.subtract(target.carbs).setScale(3, RoundingMode.HALF_UP),
                fatDelta = point.totals.fat.subtract(target.fat).setScale(3, RoundingMode.HALF_UP),
                fiberDelta = target.fiber?.let { point.totals.fiber.subtract(it).setScale(3, RoundingMode.HALF_UP) },
            )
        } else {
            null
        }

        return NutritionProgressDailyGoal(
            configured = true,
            targets = target.targets,
            adherence = adherence,
        )
    }

    private fun BigDecimal?.toTrend(measurementCount: Int): WeightTrendDirection {
        if (measurementCount < 2 || this == null) {
            return WeightTrendDirection.INSUFFICIENT_DATA
        }
        return when (signum()) {
            1 -> WeightTrendDirection.UP
            -1 -> WeightTrendDirection.DOWN
            else -> WeightTrendDirection.FLAT
        }
    }

    companion object {
        private const val MAX_PROGRESS_DAYS = 366

        /** Distinct measured days required before a fitted line is reported. */
        const val MIN_TREND_OBSERVED_DAYS = 3
    }
}

private fun List<NutritionProgressTotals>.sum(): NutritionProgressTotals {
    return NutritionProgressTotals(
        calories = sumOf { it.calories }.setScale(2, RoundingMode.HALF_UP),
        protein = sumOf { it.protein }.setScale(3, RoundingMode.HALF_UP),
        carbs = sumOf { it.carbs }.setScale(3, RoundingMode.HALF_UP),
        fat = sumOf { it.fat }.setScale(3, RoundingMode.HALF_UP),
        fiber = sumOf { it.fiber }.setScale(3, RoundingMode.HALF_UP),
        sugar = sumOf { it.sugar }.setScale(3, RoundingMode.HALF_UP),
        sodium = sumOf { it.sodium }.setScale(3, RoundingMode.HALF_UP),
    )
}

private fun NutritionProgressTotals.divideBy(divisor: Int): NutritionProgressTotals {
    return NutritionProgressTotals(
        calories = calories.divide(BigDecimal(divisor), 2, RoundingMode.HALF_UP),
        protein = protein.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
        carbs = carbs.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
        fat = fat.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
        fiber = fiber.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
        sugar = sugar.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
        sodium = sodium.divide(BigDecimal(divisor), 3, RoundingMode.HALF_UP),
    )
}
