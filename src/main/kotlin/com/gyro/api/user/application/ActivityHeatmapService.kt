package com.gyro.api.user.application

import com.gyro.api.common.error.InvalidProgressRangeException
import com.gyro.api.daily_score.application.DailyScoreBand
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.daily_score.application.DailyScoreService
import com.gyro.api.user.infrastructure.ActivityHeatmapRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ActivityHeatmapReadModel(
    val from: LocalDate,
    val to: LocalDate,
    val timezone: String,
    val locale: String,
    val totalLoggedDays: Int,
    val maxEntryCount: Int,
    val bucketThresholds: List<ActivityHeatmapBucketThreshold>,
    val days: List<ActivityHeatmapDay>,
)

data class ActivityHeatmapDay(
    val date: LocalDate,
    val entryCount: Int,
    val logged: Boolean,
    val intensity: Int,
    val score: Int?,
    val scoreMode: DailyScoreMode?,
    val scoreBand: DailyScoreBand?,
    val finalizedAt: Instant?,
)

data class ActivityHeatmapBucketThreshold(
    val bucket: Int,
    val minEntryCount: Int,
    val maxEntryCount: Int?,
)

data class ActivityHeatmapDailyCount(
    val date: LocalDate,
    val entryCount: Int,
)

@Service
class ActivityHeatmapService(
    private val activityHeatmapRepository: ActivityHeatmapRepository,
    private val dailyScoreService: DailyScoreService,
    private val userService: UserService,
) {
    @Transactional
    fun activityHeatmap(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): ActivityHeatmapReadModel {
        validateRange(from, to)

        val profile = userService.getProfile(userId)
        val scoresByDate = dailyScoreService.finalizedScoresForRange(
            userId = userId,
            from = from,
            to = to,
            profile = profile,
        ).associateBy { it.localDate }
        val countsByDate = activityHeatmapRepository.loadDailyEntryCounts(
            userId = userId,
            from = from,
            to = to,
        ).associateBy { it.date }

        val days = from.datesThrough(to).map { date ->
            val activity = countsByDate[date]
            val entryCount = activity?.entryCount ?: 0
            val score = scoresByDate[date]
            ActivityHeatmapDay(
                date = date,
                entryCount = entryCount,
                logged = activity != null,
                intensity = entryCount.toIntensityBucket(),
                score = score?.score,
                scoreMode = score?.mode,
                scoreBand = score?.band,
                finalizedAt = score?.finalizedAt,
            )
        }

        return ActivityHeatmapReadModel(
            from = from,
            to = to,
            timezone = profile.timezone,
            locale = profile.locale,
            totalLoggedDays = days.count { it.logged },
            maxEntryCount = days.maxOfOrNull { it.entryCount } ?: 0,
            bucketThresholds = BUCKET_THRESHOLDS,
            days = days,
        )
    }

    private fun validateRange(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) {
            throw InvalidProgressRangeException("to must be on or after from.")
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_ACTIVITY_HEATMAP_DAYS) {
            throw InvalidProgressRangeException(
                "Activity heatmap range cannot exceed $MAX_ACTIVITY_HEATMAP_DAYS days."
            )
        }
    }

    private fun LocalDate.datesThrough(to: LocalDate): List<LocalDate> {
        return generateSequence(this) { date ->
            date.plusDays(1).takeUnless { it.isAfter(to) }
        }.toList()
    }

    private fun Int.toIntensityBucket(): Int {
        return when {
            this <= 0 -> 0
            this == 1 -> 1
            this == 2 -> 2
            this == 3 -> 3
            else -> 4
        }
    }

    companion object {
        const val MAX_ACTIVITY_HEATMAP_DAYS = 366

        val BUCKET_THRESHOLDS = listOf(
            ActivityHeatmapBucketThreshold(bucket = 0, minEntryCount = 0, maxEntryCount = 0),
            ActivityHeatmapBucketThreshold(bucket = 1, minEntryCount = 1, maxEntryCount = 1),
            ActivityHeatmapBucketThreshold(bucket = 2, minEntryCount = 2, maxEntryCount = 2),
            ActivityHeatmapBucketThreshold(bucket = 3, minEntryCount = 3, maxEntryCount = 3),
            ActivityHeatmapBucketThreshold(bucket = 4, minEntryCount = 4, maxEntryCount = null),
        )
    }
}
