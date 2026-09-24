package com.gyro.api.progress.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.progress.application.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class NutritionBatchProgressRequestBody(
    @field:NotEmpty
    @field:Size(max = 12)
    @field:Valid
    val ranges: List<NutritionBatchProgressRangeRequest>,
)

data class NutritionBatchProgressRangeRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val requestId: String,

    @field:NotNull
    val period: NutritionProgressPeriod,

    val anchor: LocalDate? = null,
    val month: YearMonth? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionBatchProgressResponse(
    val results: List<NutritionBatchProgressResultEnvelope>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionBatchProgressResultEnvelope(
    val requestId: String,
    val period: NutritionProgressPeriod,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val points: List<NutritionProgressPointResponse>,
    val summary: NutritionProgressSummaryResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressResponse(
    val period: NutritionProgressPeriod,
    val timezone: String,
    val from: LocalDate,
    val to: LocalDate,
    val points: List<NutritionProgressPointResponse>,
    val summary: NutritionProgressSummaryResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressPointResponse(
    val date: LocalDate,
    val logged: Boolean,
    val totals: NutritionProgressTotalsResponse,
    val goal: NutritionProgressGoalResponse?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressGoalResponse(
    val configured: Boolean,
    val targets: NutritionProgressGoalTargetsResponse?,
    val adherence: NutritionProgressGoalAdherenceResponse?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressGoalTargetsResponse(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressGoalAdherenceResponse(
    val caloriesDelta: BigDecimal,
    val proteinDelta: BigDecimal,
    val carbsDelta: BigDecimal,
    val fatDelta: BigDecimal,
    val fiberDelta: BigDecimal?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressSummaryResponse(
    val totals: NutritionProgressTotalsResponse,
    val averagePerDay: NutritionProgressTotalsResponse,
    val averagePerLoggedDay: NutritionProgressTotalsResponse?,
    val minDailyTotals: NutritionProgressTotalsResponse?,
    val maxDailyTotals: NutritionProgressTotalsResponse?,
    val loggedDayCount: Int,
    val missingDayCount: Int,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NutritionProgressTotalsResponse(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

fun NutritionProgressReadModel.toResponse(
    period: NutritionProgressPeriod,
    timezone: String,
): NutritionProgressResponse {
    return NutritionProgressResponse(
        period = period,
        timezone = timezone,
        from = range.from,
        to = range.to,
        points = dailyPoints.map { it.toResponse() },
        summary = NutritionProgressSummaryResponse(
            totals = totals.toResponse(),
            averagePerDay = averagePerDay.toResponse(),
            averagePerLoggedDay = averagePerLoggedDay?.toResponse(),
            minDailyTotals = minDailyTotals?.toResponse(),
            maxDailyTotals = maxDailyTotals?.toResponse(),
            loggedDayCount = loggedDayCount,
            missingDayCount = missingDayCount,
        ),
    )
}

fun List<NutritionBatchProgressReadModel>.toBatchResponse(): NutritionBatchProgressResponse {
    return NutritionBatchProgressResponse(
        results = map { item ->
            val response = item.progress.toResponse(
                period = item.period,
                timezone = item.timezone,
            )
            NutritionBatchProgressResultEnvelope(
                requestId = item.requestId,
                period = response.period,
                timezone = response.timezone,
                from = response.from,
                to = response.to,
                points = response.points,
                summary = response.summary,
            )
        },
    )
}

private fun NutritionProgressDailyPoint.toResponse(): NutritionProgressPointResponse {
    return NutritionProgressPointResponse(
        date = date,
        logged = logged,
        totals = totals.toResponse(),
        goal = goal?.toResponse(),
    )
}

private fun NutritionProgressDailyGoal.toResponse(): NutritionProgressGoalResponse {
    return NutritionProgressGoalResponse(
        configured = configured,
        targets = targets?.toResponse(),
        adherence = adherence?.toResponse(),
    )
}

private fun NutritionTargetsReadModel.toResponse(): NutritionProgressGoalTargetsResponse {
    return NutritionProgressGoalTargetsResponse(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
    )
}

private fun NutritionProgressGoalAdherence.toResponse(): NutritionProgressGoalAdherenceResponse {
    return NutritionProgressGoalAdherenceResponse(
        caloriesDelta = caloriesDelta,
        proteinDelta = proteinDelta,
        carbsDelta = carbsDelta,
        fatDelta = fatDelta,
        fiberDelta = fiberDelta,
    )
}

private fun NutritionProgressTotals.toResponse(): NutritionProgressTotalsResponse {
    return NutritionProgressTotalsResponse(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
        sugar = sugar,
        sodium = sodium,
    )
}
