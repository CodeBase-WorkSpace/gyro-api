package com.gyro.api.goal.application.coach

import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.goal.application.recalibration.RecalibrationData
import com.gyro.api.goal.application.recalibration.RecalibrationWindowPolicy
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

enum class NutritionCoachReadinessReason {
    INSUFFICIENT_FOOD_EVIDENCE,
    INSUFFICIENT_WEIGH_IN_DAYS,
    INSUFFICIENT_WEIGHT_SPAN,
    INSUFFICIENT_FOOD_AND_WEIGHT_EVIDENCE,
}

enum class NutritionCoachNextUsefulAction {
    LOG_FOOD,
    LOG_WEIGHT_TODAY,
    WAIT_FOR_ANOTHER_WEIGHT_DAY,
    EXTEND_WEIGHT_SPAN,
}

data class NutritionCoachCollecting(
    val reason: String,
    val weighIns: Int,
    val weighInsRequired: Int = RecalibrationConfidence.LOW_WEIGH_IN_DAYS,
    val spanDays: Int,
    val spanDaysRequired: Int = RecalibrationConfidence.LOW_SPAN_DAYS.toInt(),
    val coveragePercent: Int,
    val coverageRequired: Int = (RecalibrationConfidence.LOW_LOGGED_RATIO * 100).toInt(),
    val foodEvidenceDays: Int,
    val foodEvidenceDaysRequired: Int,
    val weighInDays: Int,
    val weighInDaysRequired: Int = RecalibrationConfidence.LOW_WEIGH_IN_DAYS,
    val weightSpanDays: Int,
    val weightSpanDaysRequired: Int = RecalibrationConfidence.LOW_SPAN_DAYS.toInt(),
    val weighedInToday: Boolean,
    val readinessReason: NutritionCoachReadinessReason,
    val nextUsefulAction: NutritionCoachNextUsefulAction,
)

internal object NutritionCoachReadinessPolicy {
    const val READINESS_CHECK_DAY = 7
    val FINAL_FOOD_EVIDENCE_DAYS_REQUIRED = requiredFoodDays(RecalibrationWindowPolicy.FOURTEEN_DAYS.days)

    fun evaluate(data: RecalibrationData, completedPlanDays: Int): NutritionCoachCollecting? {
        val weighInDates = data.weights.map { it.date }.distinct().sorted()
        val span = if (weighInDates.size < 2) {
            0
        } else {
            ChronoUnit.DAYS.between(weighInDates.first(), weighInDates.last()).toInt()
        }
        val elapsedFoodRequirement = requiredFoodDays(
            completedPlanDays.coerceAtMost(RecalibrationWindowPolicy.FOURTEEN_DAYS.days),
        )
        val foodInsufficient = data.loggedDays < elapsedFoodRequirement
        val weighInDaysInsufficient = weighInDates.size < RecalibrationConfidence.LOW_WEIGH_IN_DAYS
        val weightSpanInsufficient = span < RecalibrationConfidence.LOW_SPAN_DAYS.toInt()
        val weightInsufficient = weighInDaysInsufficient || weightSpanInsufficient
        if (!foodInsufficient && !weightInsufficient) return null
        val weighedInToday = data.today in weighInDates

        val readinessReason = when {
            foodInsufficient && weightInsufficient ->
                NutritionCoachReadinessReason.INSUFFICIENT_FOOD_AND_WEIGHT_EVIDENCE
            foodInsufficient -> NutritionCoachReadinessReason.INSUFFICIENT_FOOD_EVIDENCE
            weighInDaysInsufficient -> NutritionCoachReadinessReason.INSUFFICIENT_WEIGH_IN_DAYS
            else -> NutritionCoachReadinessReason.INSUFFICIENT_WEIGHT_SPAN
        }
        // When both evidence types lag, compare progress toward the final LOW gates so
        // the card offers one stable action instead of switching as the day-7 food pace
        // rises. A tie favors food because one diary entry can make today count, while
        // an extra same-day weigh-in cannot improve the distinct-day gate.
        val evidenceType = when {
            !foodInsufficient -> EvidenceType.WEIGHT
            !weightInsufficient -> EvidenceType.FOOD
            evidenceProgress(data.loggedDays, FINAL_FOOD_EVIDENCE_DAYS_REQUIRED) <=
                minOf(
                    evidenceProgress(weighInDates.size, RecalibrationConfidence.LOW_WEIGH_IN_DAYS),
                    evidenceProgress(span, RecalibrationConfidence.LOW_SPAN_DAYS.toInt()),
                ) -> EvidenceType.FOOD
            else -> EvidenceType.WEIGHT
        }
        val nextUsefulAction = when {
            evidenceType == EvidenceType.FOOD -> NutritionCoachNextUsefulAction.LOG_FOOD
            !weighedInToday -> NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY
            weighInDaysInsufficient -> NutritionCoachNextUsefulAction.WAIT_FOR_ANOTHER_WEIGHT_DAY
            else -> NutritionCoachNextUsefulAction.EXTEND_WEIGHT_SPAN
        }
        return NutritionCoachCollecting(
            reason = when {
                weighInDaysInsufficient -> "INSUFFICIENT_WEIGH_INS"
                weightSpanInsufficient -> "INSUFFICIENT_WEIGHT_SPAN"
                else -> "INSUFFICIENT_LOGGED_DAYS"
            },
            weighIns = weighInDates.size,
            spanDays = span,
            coveragePercent = data.loggedDays * 100 / RecalibrationWindowPolicy.FOURTEEN_DAYS.days,
            foodEvidenceDays = data.loggedDays,
            foodEvidenceDaysRequired = elapsedFoodRequirement,
            weighInDays = weighInDates.size,
            weightSpanDays = span,
            weighedInToday = weighedInToday,
            readinessReason = readinessReason,
            nextUsefulAction = nextUsefulAction,
        )
    }

    private fun requiredFoodDays(days: Int): Int =
        ceil(days * RecalibrationConfidence.LOW_LOGGED_RATIO).toInt()

    private fun evidenceProgress(actual: Int, required: Int): Double =
        actual.toDouble() / required

    private enum class EvidenceType { FOOD, WEIGHT }
}
