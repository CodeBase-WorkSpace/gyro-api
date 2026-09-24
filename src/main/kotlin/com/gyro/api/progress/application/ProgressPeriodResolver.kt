package com.gyro.api.progress.application

import com.gyro.api.common.error.InvalidProgressRangeException
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.*

enum class WeightProgressPeriod {
    WEEK,
    PHASE,
}

enum class NutritionProgressPeriod {
    WEEK,
    MONTH,
    PHASE,
}

data class ResolvedWeightProgressPeriod(
    val period: WeightProgressPeriod,
    val timezone: String,
    val range: ProgressDateRange,
)

data class ResolvedNutritionProgressPeriod(
    val period: NutritionProgressPeriod,
    val timezone: String,
    val range: ProgressDateRange,
)

data class WeightBatchProgressRequest(
    val requestId: String,
    val period: WeightProgressPeriod,
    val anchor: LocalDate?,
    val from: LocalDate?,
    val to: LocalDate?,
)

data class ResolvedWeightBatchProgressRequest(
    val requestId: String,
    val period: WeightProgressPeriod,
    val timezone: String,
    val range: ProgressDateRange,
)

data class NutritionBatchProgressRequest(
    val requestId: String,
    val period: NutritionProgressPeriod,
    val anchor: LocalDate?,
    val month: YearMonth?,
    val from: LocalDate?,
    val to: LocalDate?,
)

data class ResolvedNutritionBatchProgressRequest(
    val requestId: String,
    val period: NutritionProgressPeriod,
    val timezone: String,
    val range: ProgressDateRange,
)

@Service
class ProgressPeriodResolver(
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
) {
    fun resolveWeightBatchPeriods(
        userId: UUID,
        requests: List<WeightBatchProgressRequest>,
    ): List<ResolvedWeightBatchProgressRequest> {
        if (requests.size > MAX_WEIGHT_BATCH_RANGES) {
            throw InvalidProgressRangeException(
                "Weight progress batch cannot contain more than $MAX_WEIGHT_BATCH_RANGES ranges."
            )
        }

        val duplicateRequestId = requests.groupingBy { it.requestId }.eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateRequestId != null) {
            throw InvalidProgressRangeException("requestId '$duplicateRequestId' must be unique within the batch.")
        }

        val resolved = requests.map { request ->
            val resolvedPeriod = resolveWeightPeriod(
                userId = userId,
                period = request.period,
                anchor = request.anchor,
                from = request.from,
                to = request.to,
            )
            ResolvedWeightBatchProgressRequest(
                requestId = request.requestId,
                period = resolvedPeriod.period,
                timezone = resolvedPeriod.timezone,
                range = resolvedPeriod.range,
            )
        }

        val totalCoveredDays = resolved.sumOf { it.range.dayCount() }
        if (totalCoveredDays > MAX_WEIGHT_BATCH_TOTAL_DAYS) {
            throw InvalidProgressRangeException(
                "Weight progress batch cannot cover more than $MAX_WEIGHT_BATCH_TOTAL_DAYS total days."
            )
        }

        return resolved
    }

    fun resolveNutritionBatchPeriods(
        userId: UUID,
        requests: List<NutritionBatchProgressRequest>,
    ): List<ResolvedNutritionBatchProgressRequest> {
        if (requests.size > MAX_NUTRITION_BATCH_RANGES) {
            throw InvalidProgressRangeException(
                "Nutrition progress batch cannot contain more than $MAX_NUTRITION_BATCH_RANGES ranges."
            )
        }

        val duplicateRequestId = requests.groupingBy { it.requestId }.eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateRequestId != null) {
            throw InvalidProgressRangeException("requestId '$duplicateRequestId' must be unique within the batch.")
        }

        val resolved = requests.map { request ->
            val resolvedPeriod = resolveNutritionPeriod(
                userId = userId,
                period = request.period,
                anchor = request.anchor,
                month = request.month,
                from = request.from,
                to = request.to,
            )
            ResolvedNutritionBatchProgressRequest(
                requestId = request.requestId,
                period = resolvedPeriod.period,
                timezone = resolvedPeriod.timezone,
                range = resolvedPeriod.range,
            )
        }

        val totalCoveredDays = resolved.sumOf { it.range.dayCount() }
        if (totalCoveredDays > MAX_NUTRITION_BATCH_TOTAL_DAYS) {
            throw InvalidProgressRangeException(
                "Nutrition progress batch cannot cover more than $MAX_NUTRITION_BATCH_TOTAL_DAYS total days."
            )
        }

        return resolved
    }

    fun resolveWeightPeriod(
        userId: UUID,
        period: WeightProgressPeriod,
        anchor: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): ResolvedWeightProgressPeriod {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone

        val range = when (period) {
            WeightProgressPeriod.WEEK -> resolveWeekRange(anchor, from, to)
            WeightProgressPeriod.PHASE -> resolvePhaseRange(anchor, from, to)
        }

        return ResolvedWeightProgressPeriod(
            period = period,
            timezone = timezone,
            range = range,
        )
    }

    fun resolveNutritionPeriod(
        userId: UUID,
        period: NutritionProgressPeriod,
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ResolvedNutritionProgressPeriod {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone

        val range = when (period) {
            NutritionProgressPeriod.WEEK -> resolveNutritionWeekRange(anchor, month, from, to)
            NutritionProgressPeriod.MONTH -> resolveMonthRange(anchor, month, from, to)
            NutritionProgressPeriod.PHASE -> resolveNutritionPhaseRange(anchor, month, from, to)
        }

        return ResolvedNutritionProgressPeriod(
            period = period,
            timezone = timezone,
            range = range,
        )
    }

    fun resolveNutritionWeeklyAlias(
        userId: UUID,
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ResolvedNutritionProgressPeriod {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone

        return ResolvedNutritionProgressPeriod(
            period = NutritionProgressPeriod.WEEK,
            timezone = timezone,
            range = resolveNutritionWeeklyAliasRange(
                anchor = anchor,
                month = month,
                from = from,
                to = to,
            ),
        )
    }

    private fun resolveWeekRange(
        anchor: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (anchor == null) {
            throw InvalidProgressRangeException("anchor is required when period=WEEK.")
        }
        if (from != null || to != null) {
            throw InvalidProgressRangeException("from and to are not allowed when period=WEEK.")
        }

        val start = anchor.previousOrSame(DayOfWeek.SATURDAY)
        return ProgressDateRange(
            from = start,
            to = start.plusDays(6),
        )
    }

    private fun resolveNutritionWeekRange(
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (month != null) {
            throw InvalidProgressRangeException("month is not allowed when period=WEEK.")
        }
        return resolveWeekRange(anchor, from, to)
    }

    private fun resolveNutritionWeeklyAliasRange(
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (month != null) {
            throw InvalidProgressRangeException("month is not allowed for progress/weekly.")
        }
        if (from != null || to != null) {
            if (anchor != null) {
                throw InvalidProgressRangeException("anchor is not allowed when from and to are provided.")
            }
            if (from == null || to == null) {
                throw InvalidProgressRangeException("from and to are required together.")
            }
            if (to.isBefore(from)) {
                throw InvalidProgressRangeException("to must be on or after from.")
            }
            if (ProgressDateRange(from = from, to = to).dayCount() != 7) {
                throw InvalidProgressRangeException("progress/weekly from and to must cover exactly 7 days.")
            }
            return ProgressDateRange(from = from, to = to)
        }

        return resolveWeekRange(anchor, from = null, to = null)
    }

    private fun resolveMonthRange(
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (anchor != null || from != null || to != null) {
            throw InvalidProgressRangeException("anchor, from, and to are not allowed when period=MONTH.")
        }
        if (month == null) {
            throw InvalidProgressRangeException("month is required when period=MONTH.")
        }

        return ProgressDateRange(
            from = month.atDay(1),
            to = month.atEndOfMonth(),
        )
    }

    private fun resolvePhaseRange(
        anchor: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (anchor != null) {
            throw InvalidProgressRangeException("anchor is not allowed when period=PHASE.")
        }
        if (from == null || to == null) {
            throw InvalidProgressRangeException("from and to are required when period=PHASE.")
        }

        return ProgressDateRange(
            from = from,
            to = to,
        )
    }

    private fun resolveNutritionPhaseRange(
        anchor: LocalDate?,
        month: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): ProgressDateRange {
        if (month != null) {
            throw InvalidProgressRangeException("month is not allowed when period=PHASE.")
        }
        return resolvePhaseRange(anchor, from, to)
    }

    private fun LocalDate.previousOrSame(dayOfWeek: DayOfWeek): LocalDate {
        var current = this
        while (current.dayOfWeek != dayOfWeek) {
            current = current.minusDays(1)
        }
        return current
    }

    private fun ProgressDateRange.dayCount(): Int {
        return (to.toEpochDay() - from.toEpochDay()).toInt() + 1
    }

    companion object {
        private const val MAX_WEIGHT_BATCH_RANGES = 12
        private const val MAX_WEIGHT_BATCH_TOTAL_DAYS = 366
        private const val MAX_NUTRITION_BATCH_RANGES = 12
        private const val MAX_NUTRITION_BATCH_TOTAL_DAYS = 366
    }
}
