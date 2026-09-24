package com.gyro.api.daily_score.web.dto

import com.gyro.api.daily_score.application.DailyScoreAnalyticsSummary
import com.gyro.api.daily_score.application.DailyScoreBand
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.daily_score.application.DailyScorePeriodAverage
import com.gyro.api.daily_score.application.DailyScoreReadModel
import com.gyro.api.goal.domain.GoalType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyScoreAnalyticsResponse(
    val from: LocalDate,
    val to: LocalDate,
    val scoreCount: Int,
    val averageScore: BigDecimal?,
    val averageCalorieScore: BigDecimal?,
    val averageProteinScore: BigDecimal?,
    val averageLoggingConsistencyScore: BigDecimal?,
    val formulaVersions: Map<String, Int>,
    val distribution: Map<DailyScoreBand, Int>,
    val weeklyAverages: List<DailyScorePeriodAverageResponse>,
    val monthlyAverages: List<DailyScorePeriodAverageResponse>,
    val bestDays: List<DailyScorePointResponse>,
    val worstDays: List<DailyScorePointResponse>,
)

data class DailyScorePeriodAverageResponse(
    val from: LocalDate,
    val to: LocalDate,
    val scoreCount: Int,
    val averageScore: BigDecimal,
)

data class DailyScorePointResponse(
    val date: LocalDate,
    val score: Int,
    val scoreMode: DailyScoreMode,
    val scoreBand: DailyScoreBand,
    val goalId: UUID?,
    val goalType: GoalType?,
    val formulaVersion: String,
    val finalizedAt: Instant,
)

fun DailyScoreAnalyticsSummary.toResponse(): DailyScoreAnalyticsResponse {
    return DailyScoreAnalyticsResponse(
        from = from,
        to = to,
        scoreCount = scoreCount,
        averageScore = averageScore,
        averageCalorieScore = averageCalorieScore,
        averageProteinScore = averageProteinScore,
        averageLoggingConsistencyScore = averageLoggingConsistencyScore,
        formulaVersions = formulaVersions,
        distribution = distribution,
        weeklyAverages = weeklyAverages.map { it.toResponse() },
        monthlyAverages = monthlyAverages.map { it.toResponse() },
        bestDays = bestDays.map { it.toPointResponse() },
        worstDays = worstDays.map { it.toPointResponse() },
    )
}

fun DailyScorePeriodAverage.toResponse(): DailyScorePeriodAverageResponse {
    return DailyScorePeriodAverageResponse(
        from = from,
        to = to,
        scoreCount = scoreCount,
        averageScore = averageScore,
    )
}

fun DailyScoreReadModel.toPointResponse(): DailyScorePointResponse {
    return DailyScorePointResponse(
        date = localDate,
        score = score,
        scoreMode = mode,
        scoreBand = band,
        goalId = goalId,
        goalType = goalType,
        formulaVersion = formulaVersion,
        finalizedAt = finalizedAt,
    )
}
