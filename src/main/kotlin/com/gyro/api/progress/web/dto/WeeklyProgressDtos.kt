package com.gyro.api.progress.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.gyro.api.progress.application.WeightProgressReadModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyProgressResponse(
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val nutrition: WeeklyNutritionSummaryResponse,
    val weight: WeeklyWeightSummaryResponse?,
    val warnings: List<String>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyNutritionSummaryResponse(
    val loggedDayCount: Int,
    val missingDayCount: Int,
    val calories: WeeklyNutritionMetricResponse,
    val macros: WeeklyMacroSummaryResponse,
    val micronutrients: WeeklyMicronutrientSummaryResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyMacroSummaryResponse(
    val protein: WeeklyNutritionMetricResponse,
    val carbs: WeeklyNutritionMetricResponse,
    val fat: WeeklyNutritionMetricResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyMicronutrientSummaryResponse(
    val fiber: WeeklyNutritionMetricResponse,
    val sugar: WeeklyNutritionMetricResponse,
    val sodium: WeeklyNutritionMetricResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyNutritionMetricResponse(
    val total: BigDecimal,
    val average: BigDecimal?,
    val goalAveragePercent: BigDecimal?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeeklyWeightSummaryResponse(
    val configured: Boolean,
    val startWeightKg: BigDecimal?,
    val endWeightKg: BigDecimal?,
    val absoluteChangeKg: BigDecimal?,
    val trendDirection: String?,
    val targetWeightKg: BigDecimal?,
)

fun NutritionProgressResponse.toWeeklyResponse(
    weight: WeeklyWeightSummaryResponse?,
): WeeklyProgressResponse {
    return WeeklyProgressResponse(
        timezone = timezone,
        from = from,
        to = to,
        nutrition = WeeklyNutritionSummaryResponse(
            loggedDayCount = summary.loggedDayCount,
            missingDayCount = summary.missingDayCount,
            calories = metric(
                total = summary.totals.calories,
                average = summary.averagePerLoggedDay?.calories,
                goalAverage = averageGoal { it.calories },
                percentScale = 2,
            ),
            macros = WeeklyMacroSummaryResponse(
                protein = metric(
                    total = summary.totals.protein,
                    average = summary.averagePerLoggedDay?.protein,
                    goalAverage = averageGoal { it.protein },
                    percentScale = 3,
                ),
                carbs = metric(
                    total = summary.totals.carbs,
                    average = summary.averagePerLoggedDay?.carbs,
                    goalAverage = averageGoal { it.carbs },
                    percentScale = 3,
                ),
                fat = metric(
                    total = summary.totals.fat,
                    average = summary.averagePerLoggedDay?.fat,
                    goalAverage = averageGoal { it.fat },
                    percentScale = 3,
                ),
            ),
            micronutrients = WeeklyMicronutrientSummaryResponse(
                fiber = metric(
                    total = summary.totals.fiber,
                    average = summary.averagePerLoggedDay?.fiber,
                    goalAverage = averageGoal { it.fiber },
                    percentScale = 3,
                ),
                sugar = metric(
                    total = summary.totals.sugar,
                    average = summary.averagePerLoggedDay?.sugar,
                    goalAverage = null,
                    percentScale = 3,
                ),
                sodium = metric(
                    total = summary.totals.sodium,
                    average = summary.averagePerLoggedDay?.sodium,
                    goalAverage = null,
                    percentScale = 3,
                ),
            ),
        ),
        weight = weight,
        warnings = emptyList(),
    )
}

fun WeightProgressReadModel.toWeeklyWeightSummaryResponse(
    targetWeightKg: BigDecimal?,
): WeeklyWeightSummaryResponse? {
    if (measurements.isEmpty()) {
        return null
    }

    return WeeklyWeightSummaryResponse(
        configured = true,
        startWeightKg = startWeightKg,
        endWeightKg = endWeightKg,
        absoluteChangeKg = absoluteChangeKg,
        trendDirection = trend.name,
        targetWeightKg = targetWeightKg,
    )
}

private fun NutritionProgressResponse.averageGoal(
    selector: (NutritionProgressGoalTargetsResponse) -> BigDecimal?,
): BigDecimal? {
    val goals = points
        .filter { it.logged }
        .mapNotNull { point ->
            point.goal?.targets?.let(selector)
        }
    if (goals.isEmpty()) {
        return null
    }
    return goals.fold(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal(goals.size), 6, RoundingMode.HALF_UP)
}

private fun metric(
    total: BigDecimal,
    average: BigDecimal?,
    goalAverage: BigDecimal?,
    percentScale: Int,
): WeeklyNutritionMetricResponse {
    return WeeklyNutritionMetricResponse(
        total = total,
        average = average,
        goalAveragePercent = average?.toGoalPercent(
            goalAverage = goalAverage,
            scale = percentScale,
        ),
    )
}

private fun BigDecimal.toGoalPercent(
    goalAverage: BigDecimal?,
    scale: Int,
): BigDecimal? {
    if (goalAverage == null || goalAverage.compareTo(BigDecimal.ZERO) == 0) {
        return null
    }
    return multiply(BigDecimal("100"))
        .divide(goalAverage, scale, RoundingMode.HALF_UP)
}
