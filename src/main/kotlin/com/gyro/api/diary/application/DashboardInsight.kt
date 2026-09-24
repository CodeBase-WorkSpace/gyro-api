package com.gyro.api.diary.application

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate

enum class DashboardInsightKind {
    CALORIE_ADHERENCE,
    SCORE_TREND,
    BEST_DAY,
    PROTEIN_CONSISTENCY,
    LOGGING_STREAK,
    COACH_TIP,
    MEASURED_TDEE,
    WEEKEND_GAP,
    TREND_EXPLANATION,
    GOAL_FORECAST,
}

enum class DashboardInsightTrend { UP, DOWN, STABLE }

enum class DashboardInsightBasis { LOGGED_DAYS, TARGET_COMPARISON, OBSERVED_ENERGY, WEIGHT_FORECAST }

/**
 * Discriminated Coach observation contract.
 *
 * Each subtype owns exactly the evidence fields valid for its [kind]. Jackson uses
 * the existing `kind` property as the discriminator, so Redis cache entries and the
 * public JSON shape stay explicit without one ever-growing nullable transport object.
 *
 * That claim is enforced, not asserted. Every field shared across kinds — [kind],
 * [impressionId], [value], [basis], [trend] — is declared on the interface, so adding a
 * kind is a compile error until it answers all of them, and a field meaningful to only
 * one kind lives on that kind alone. `DashboardInsightContractTest` pins the exact JSON
 * key set of every kind, so widening one subtype cannot silently widen the contract:
 * the test names the new field and the reviewer decides whether it belongs there or on
 * the interface.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
)
@JsonSubTypes(
    JsonSubTypes.Type(CalorieAdherenceInsight::class, name = "CALORIE_ADHERENCE"),
    JsonSubTypes.Type(ScoreTrendInsight::class, name = "SCORE_TREND"),
    JsonSubTypes.Type(BestDayInsight::class, name = "BEST_DAY"),
    JsonSubTypes.Type(ProteinConsistencyInsight::class, name = "PROTEIN_CONSISTENCY"),
    JsonSubTypes.Type(LoggingStreakInsight::class, name = "LOGGING_STREAK"),
    JsonSubTypes.Type(CoachTipInsight::class, name = "COACH_TIP"),
    JsonSubTypes.Type(MeasuredTdeeInsight::class, name = "MEASURED_TDEE"),
    JsonSubTypes.Type(WeekendGapInsight::class, name = "WEEKEND_GAP"),
    JsonSubTypes.Type(TrendExplanationInsight::class, name = "TREND_EXPLANATION"),
    JsonSubTypes.Type(GoalForecastInsight::class, name = "GOAL_FORECAST"),
)
@Schema(
    discriminatorProperty = "kind",
    oneOf = [
        CalorieAdherenceInsight::class,
        ScoreTrendInsight::class,
        BestDayInsight::class,
        ProteinConsistencyInsight::class,
        LoggingStreakInsight::class,
        CoachTipInsight::class,
        MeasuredTdeeInsight::class,
        WeekendGapInsight::class,
        TrendExplanationInsight::class,
        GoalForecastInsight::class,
    ],
)
sealed interface DashboardInsight {
    val kind: DashboardInsightKind
    val impressionId: String
    val value: Int

    /**
     * What class of evidence [value] rests on, or null for an observation that rests on
     * none — a streak counts records, and a tip has no evidence at all.
     *
     * Declared here rather than on each subtype so a new kind cannot quietly ship without
     * answering the question. It was previously an ad-hoc `val` that seven subtypes
     * happened to declare and two happened to omit, which left "does this kind have a
     * basis?" answerable only by reading all nine classes.
     */
    val basis: DashboardInsightBasis?

    /**
     * The generic direction badge, or null when the observation makes no directional
     * claim.
     *
     * This is deliberately not a free field. The frontend renders `UP` as "رو به بهبود" —
     * improvement — so any kind that reports a neutral fact must return null, and every
     * kind must now say so in Kotlin instead of relying on the frontend parser to reject
     * a stray value at runtime.
     */
    val trend: DashboardInsightTrend?
}

data class CalorieAdherenceInsight(
    override val impressionId: String,
    override val value: Int,
    val averageIntakeCalories: Int,
    val averageTargetCalories: Int,
    val deltaPercent: BigDecimal,
    val loggedDayCount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.CALORIE_ADHERENCE
    override val basis = DashboardInsightBasis.TARGET_COMPARISON
    override val trend: DashboardInsightTrend? = null
}

data class ScoreTrendInsight(
    override val impressionId: String,
    override val value: Int,
    override val trend: DashboardInsightTrend,
    val loggedDayCount: Int,
    val previousLoggedDayCount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.SCORE_TREND
    override val basis = DashboardInsightBasis.LOGGED_DAYS
}

data class BestDayInsight(
    override val impressionId: String,
    override val value: Int,
    val date: LocalDate,
    val loggedDayCount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.BEST_DAY
    override val basis = DashboardInsightBasis.LOGGED_DAYS
    override val trend: DashboardInsightTrend? = null
}

data class ProteinConsistencyInsight(
    override val impressionId: String,
    override val value: Int,
    override val trend: DashboardInsightTrend?,
    val loggedDayCount: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.PROTEIN_CONSISTENCY
    override val basis = DashboardInsightBasis.LOGGED_DAYS
}

data class LoggingStreakInsight(
    override val impressionId: String,
    override val value: Int,
    val capped: Boolean,
) : DashboardInsight {
    override val kind = DashboardInsightKind.LOGGING_STREAK
    override val basis: DashboardInsightBasis? = null
    override val trend: DashboardInsightTrend? = null
}

data class CoachTipInsight(
    override val impressionId: String,
    override val value: Int,
) : DashboardInsight {
    override val kind = DashboardInsightKind.COACH_TIP
    override val basis: DashboardInsightBasis? = null
    override val trend: DashboardInsightTrend? = null
}

data class MeasuredTdeeInsight(
    override val impressionId: String,
    override val value: Int,
    val confidence: RecalibrationConfidence,
    val estimatorVersion: String,
    val displayPolicyVersion: String,
    val windowDays: Int,
    val loggedDayCount: Int,
    val weighInDayCount: Int,
    val weightSpanDays: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.MEASURED_TDEE
    override val basis = DashboardInsightBasis.OBSERVED_ENERGY
    override val trend: DashboardInsightTrend? = null
}

data class WeekendGapInsight(
    override val impressionId: String,
    override val value: Int,
    val weekendTargetDeltaPercent: BigDecimal,
    val weekdayTargetDeltaPercent: BigDecimal,
    val weekendLoggedDayCount: Int,
    val weekdayLoggedDayCount: Int,
    val loggedDayCount: Int,
    val windowDays: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
) : DashboardInsight {
    override val kind = DashboardInsightKind.WEEKEND_GAP
    override val basis = DashboardInsightBasis.TARGET_COMPARISON
    override val trend: DashboardInsightTrend? = null
}

data class TrendExplanationInsight(
    override val impressionId: String,
    override val value: Int,
    val deltaPercent: BigDecimal,
    val weightTrendKgPerWeek: BigDecimal,
    val averageIntakeCalories: Int,
    val averageTargetCalories: Int,
    val loggedDayCount: Int,
    val weighInDayCount: Int,
    val weightSpanDays: Int,
    val windowDays: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val confidence: RecalibrationConfidence,
) : DashboardInsight {
    override val kind = DashboardInsightKind.TREND_EXPLANATION
    override val basis = DashboardInsightBasis.TARGET_COMPARISON
    override val trend: DashboardInsightTrend? = null
}

/** Whether a date forecast is available, and when it is not, the single reason why. */
enum class GoalForecastStatus {
    AVAILABLE,
    INSUFFICIENT_EVIDENCE,
    STALE_EVIDENCE,
    FLAT_TREND,
    OPPOSITE_TREND,
    LOW_TREND_QUALITY,
    BEYOND_HORIZON,
}

/** Where one quarter block sits relative to the weight observed so far. */
enum class GoalForecastMilestoneState { REACHED, NEXT, UPCOMING }

/**
 * One quarter of the saved plan.
 *
 * [plannedDate] is interpolated from the saved plan and never moves on an ordinary
 * forecast refresh. [forecastDate] is the projection of the recent weight trend and
 * is null for a reached block or whenever no trustworthy forecast exists.
 */
data class GoalForecastMilestone(
    val progressPercent: Int,
    val targetWeightKg: BigDecimal,
    val plannedDate: LocalDate,
    val forecastDate: LocalDate?,
    val state: GoalForecastMilestoneState,
)

/**
 * The four stable milestones of a weight goal, with the saved plan dates alongside
 * the dates projected from the recent weight trend.
 *
 * The milestone weights and [originalTargetDate] stay anchored to the saved goal, so
 * this structure never rewrites the plan or its calculator provenance. Every projected
 * date is null unless [status] is [GoalForecastStatus.AVAILABLE].
 */
data class GoalForecast(
    val status: GoalForecastStatus,
    val originalTargetDate: LocalDate,
    val forecastTargetDate: LocalDate?,
    val delayDays: Int?,
    val startWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal,
    val fittedWeightKg: BigDecimal?,
    val observedKgPerWeek: BigDecimal?,
    val progressPercent: BigDecimal,
    val evidenceStart: LocalDate?,
    val evidenceEnd: LocalDate?,
    val weighInDayCount: Int,
    val weightSpanDays: Int,
    val milestones: List<GoalForecastMilestone>,
)

/**
 * Carries the forecast as one nested model rather than a dozen loosely related nullable
 * fields on the shared transport. `trend` is deliberately absent: the frontend reads the
 * generic `UP` as improvement, which a schedule forecast does not claim.
 */
data class GoalForecastInsight(
    override val impressionId: String,
    override val value: Int,
    val goalForecast: GoalForecast,
) : DashboardInsight {
    override val kind = DashboardInsightKind.GOAL_FORECAST
    override val basis = DashboardInsightBasis.WEIGHT_FORECAST
    override val trend: DashboardInsightTrend? = null
}
