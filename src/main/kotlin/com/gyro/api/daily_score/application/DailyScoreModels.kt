package com.gyro.api.daily_score.application

import com.gyro.api.goal.domain.GoalType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class DailyScoreMode {
    GOAL_ADHERENCE,
    CONSISTENCY,
}

enum class DailyScoreBand {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
}

data class DailyScoreReadModel(
    val id: UUID,
    val userId: UUID,
    val localDate: LocalDate,
    val score: Int,
    val mode: DailyScoreMode,
    val goalId: UUID?,
    val goalType: GoalType?,
    val formulaName: String,
    val formulaVersion: String,
    val breakdown: DailyScoreBreakdown,
    val finalizedAt: Instant,
    val createdAt: Instant,
) {
    val band: DailyScoreBand
        get() = score.toDailyScoreBand()
}

data class DailyScoreDraft(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val localDate: LocalDate,
    val score: Int,
    val mode: DailyScoreMode,
    val goalId: UUID?,
    val goalType: GoalType?,
    val formulaName: String,
    val formulaVersion: String,
    val breakdown: DailyScoreBreakdown,
    val finalizedAt: Instant,
    val createdAt: Instant,
)

data class DailyScoreInput(
    val date: LocalDate,
    val logged: Boolean,
    val loggedMealCount: Int,
    val loggedMealTypes: Set<String>,
    val totals: DailyScoreNutritionTotals,
    val target: DailyScoreTarget?,
)

data class DailyScoreTarget(
    val goalId: UUID,
    val goalType: GoalType?,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
)

data class DailyScoreNutritionTotals(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
)

data class DailyScoreBreakdown(
    val formulaName: String,
    val formulaVersion: String,
    val componentWeights: Map<String, BigDecimal>,
    val calorieScore: Int?,
    val proteinScore: Int?,
    val fatScore: Int?,
    val carbohydrateScore: Int?,
    val loggingCompletenessScore: Int,
    val loggedMealCount: Int,
    val loggedMealTypes: List<String>,
    val missingMajorMeals: List<String>,
    val totalCalories: BigDecimal,
    val targetCalories: BigDecimal?,
    val calorieDelta: BigDecimal?,
    val totalProtein: BigDecimal,
    val targetProtein: BigDecimal?,
    val proteinDelta: BigDecimal?,
    val totalFat: BigDecimal,
    val targetFat: BigDecimal?,
    val fatDelta: BigDecimal?,
    val totalCarbohydrates: BigDecimal,
    val targetCarbohydrates: BigDecimal?,
    val carbohydrateDelta: BigDecimal?,
)

data class DailyScoreAnalyticsSummary(
    val from: LocalDate,
    val to: LocalDate,
    val scoreCount: Int,
    val loggedDayCount: Int,
    val averageScore: BigDecimal?,
    val averageCalorieScore: BigDecimal?,
    val averageProteinScore: BigDecimal?,
    val averageLoggingConsistencyScore: BigDecimal?,
    val formulaVersions: Map<String, Int>,
    val distribution: Map<DailyScoreBand, Int>,
    val weeklyAverages: List<DailyScorePeriodAverage>,
    val monthlyAverages: List<DailyScorePeriodAverage>,
    val bestDays: List<DailyScoreReadModel>,
    val worstDays: List<DailyScoreReadModel>,
)

data class DailyScorePeriodAverage(
    val from: LocalDate,
    val to: LocalDate,
    val scoreCount: Int,
    val averageScore: BigDecimal,
)

data class DailyScoreCoachAnalytics(
    val currentWindow: DailyScoreAnalyticsSummary?,
    val previousWindow: DailyScoreAnalyticsSummary?,
    val currentWindowCalorieComparison: DailyScoreCalorieTargetComparison? = null,
)

data class DailyScoreCalorieTargetComparison(
    val averageIntakeCalories: BigDecimal,
    val averageTargetCalories: BigDecimal,
    /**
     * Signed intake difference from the historical targets, retained at a
     * deterministic internal precision for ranking and boundary checks.
     */
    val deltaPercent: BigDecimal,
    val loggedDayCount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
)

fun Int.toDailyScoreBand(): DailyScoreBand {
    return when {
        this >= 85 -> DailyScoreBand.EXCELLENT
        this >= 70 -> DailyScoreBand.GOOD
        this >= 50 -> DailyScoreBand.FAIR
        else -> DailyScoreBand.POOR
    }
}
