package com.gyro.api.diary.application

import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import java.math.BigDecimal
import java.time.LocalDate

/** Magnitude band of the positive fitted weight trend in a trend-explanation fingerprint. */
internal enum class TrendExplanationWeightBand { W_SMALL, W_MEDIUM, W_LARGE }

/** Magnitude band of how far below target intake fell in a trend-explanation fingerprint. */
internal enum class TrendExplanationIntakeBand { I_10_15, I_15_25, I_25_40 }

/**
 * How far the projected completion date sits from the saved deadline.
 *
 * `ON_PLAN` covers the seven days either side of the saved date, so ordinary daily
 * movement inside that tolerance is not reported as a schedule change. `NONE` is the
 * band of every state that has no projected date at all.
 */
internal enum class GoalForecastDelayBand {
    EARLY_30_PLUS,
    EARLY_8_30,
    ON_PLAN,
    LATE_8_30,
    LATE_31_60,
    LATE_60_PLUS,
    NONE,
}

/** Quarter of the goal the observed weight has reached. */
internal enum class GoalForecastProgressBand { P0_25, P25_50, P50_75, P75_100 }

/**
 * Defines the opaque evidence identity used by Coach observation impressions.
 *
 * A rolling window date changes the fingerprint, while [materialSignature]
 * deliberately ignores that date so rotation can distinguish fresh evidence
 * from a meaningful direction, magnitude, best-day, or milestone change.
 */
internal object CoachObservationFingerprintPolicy {
    private const val PREFIX = "OBS"
    const val WEEKEND_GAP_FINGERPRINT_VERSION = "V1"
    const val TREND_EXPLANATION_FINGERPRINT_VERSION = "V1"
    const val GOAL_FORECAST_FINGERPRINT_VERSION = "V1"
    const val MEASURED_TDEE_FINGERPRINT_VERSION = "V1"
    const val MEASURED_TDEE_ESTIMATOR_VERSION = "OLS_7700_V1"
    private val streakMilestones = listOf(1, 3, 7, 14, 30, 60, 90, 180, 365)

    fun calorieComparison(evidenceEnd: LocalDate, deltaPercent: BigDecimal): String =
        fingerprint(
            "CA",
            evidenceEnd,
            direction(deltaPercent.signum()),
            calorieBand(deltaPercent.abs()),
        )

    fun bestDay(evidenceEnd: LocalDate, bestDay: LocalDate, score: Int): String =
        fingerprint("BD", evidenceEnd, bestDay, score)

    fun scoreTrend(comparedPeriodEnd: LocalDate, difference: Int): String =
        fingerprint(
            "ST",
            comparedPeriodEnd,
            direction(difference.compareTo(0)),
            scoreTrendBand(kotlin.math.abs(difference)),
        )

    fun proteinConsistency(
        evidenceEnd: LocalDate,
        percentage: Int,
        trend: DashboardInsightTrend?,
    ): String = fingerprint(
        "PC",
        evidenceEnd,
        proteinBand(percentage),
        trend?.name ?: "UNKNOWN",
    )

    fun loggingStreak(days: Int): String {
        val milestone = streakMilestones.lastOrNull { days >= it } ?: 1
        return "$PREFIX|LS|M$milestone"
    }

    fun measuredTdee(
        evidenceEnd: LocalDate,
        confidence: RecalibrationConfidence,
        displayedValue: Int,
    ): String {
        require(displayedValue > 0) { "Measured TDEE display value must be positive." }
        return fingerprint(
            "MT",
            MEASURED_TDEE_FINGERPRINT_VERSION,
            evidenceEnd,
            confidence.name,
            measuredTdeeBand(displayedValue),
        )
    }

    fun weekendGap(
        evidenceEnd: LocalDate,
        direction: WeekendGapDirection,
        band: WeekendGapBand,
    ): String = fingerprint(
        "WG",
        WEEKEND_GAP_FINGERPRINT_VERSION,
        evidenceEnd,
        direction.name,
        band.name,
    )

    fun trendExplanation(
        evidenceEnd: LocalDate,
        weightBand: TrendExplanationWeightBand,
        intakeBand: TrendExplanationIntakeBand,
    ): String = fingerprint(
        "TX",
        TREND_EXPLANATION_FINGERPRINT_VERSION,
        evidenceEnd,
        weightBand.name,
        intakeBand.name,
    )

    /**
     * `OBS|GF|V1|<evidenceEnd>|<originalDate>|<forecastDateOrNONE>|<status>|<delayBand>|<progressBand>`.
     *
     * The status uses a fixed two-letter code rather than the enum name so the whole
     * identity stays inside the 80-character impression key. The rolling evidence and
     * forecast dates rotate the exact identity; [materialSignature] drops them and keeps
     * the version, status, delay band, and progress band.
     */
    fun goalForecast(
        evidenceEnd: LocalDate,
        originalTargetDate: LocalDate,
        forecastTargetDate: LocalDate?,
        status: GoalForecastStatus,
        delayBand: GoalForecastDelayBand,
        progressBand: GoalForecastProgressBand,
    ): String = fingerprint(
        "GF",
        GOAL_FORECAST_FINGERPRINT_VERSION,
        evidenceEnd,
        originalTargetDate,
        forecastTargetDate?.toString() ?: NO_FORECAST_DATE,
        goalForecastStatusCode(status),
        delayBand.name,
        progressBand.name,
    )

    internal fun goalForecastStatusCode(status: GoalForecastStatus): String = when (status) {
        GoalForecastStatus.AVAILABLE -> "AV"
        GoalForecastStatus.INSUFFICIENT_EVIDENCE -> "IE"
        GoalForecastStatus.STALE_EVIDENCE -> "SE"
        GoalForecastStatus.FLAT_TREND -> "FT"
        GoalForecastStatus.OPPOSITE_TREND -> "OT"
        GoalForecastStatus.LOW_TREND_QUALITY -> "LQ"
        GoalForecastStatus.BEYOND_HORIZON -> "BH"
    }

    /** Classifies a positive fitted trend, assumed already inside [0.20, 1.50] kg/week. */
    internal fun trendExplanationWeightBand(kgPerWeek: BigDecimal): TrendExplanationWeightBand = when {
        kgPerWeek < BigDecimal("0.40") -> TrendExplanationWeightBand.W_SMALL
        kgPerWeek < BigDecimal("0.75") -> TrendExplanationWeightBand.W_MEDIUM
        else -> TrendExplanationWeightBand.W_LARGE
    }

    /** Classifies how far below target intake fell, assumed already inside [10, 40] percent. */
    internal fun trendExplanationIntakeBand(belowPercent: BigDecimal): TrendExplanationIntakeBand = when {
        belowPercent < BigDecimal("15") -> TrendExplanationIntakeBand.I_10_15
        belowPercent < BigDecimal("25") -> TrendExplanationIntakeBand.I_15_25
        else -> TrendExplanationIntakeBand.I_25_40
    }

    /**
     * Validates an impression identity at the write boundary without requiring
     * the observation to still be present in a newly computed Coach response.
     * Legacy kind-only identities remain accepted during rolling deployments.
     */
    fun recordableKind(key: String): DashboardInsightKind? {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
        DashboardInsightKind.entries.firstOrNull {
            it != DashboardInsightKind.COACH_TIP &&
                it !in generatedOnlyKinds &&
                it.name == key
        }?.let { return it }
        if (tipPattern.matches(key)) return DashboardInsightKind.COACH_TIP
        return generatedKindOf(key)
    }

    fun kindOf(key: String): DashboardInsightKind? {
        DashboardInsightKind.entries.firstOrNull {
            it !in generatedOnlyKinds && it.name == key
        }?.let { return it }
        if (tipPattern.matches(key)) return DashboardInsightKind.COACH_TIP
        return generatedKindOf(key)
    }

    fun materialSignature(key: String): String? {
        val parts = key.split('|')
        if (parts.firstOrNull() != PREFIX) return null
        return when (parts.getOrNull(1)) {
            "CA", "ST", "PC" -> parts.drop(3).joinToString("|").takeIf(String::isNotEmpty)
            "BD" -> parts.drop(3).joinToString("|").takeIf(String::isNotEmpty)
            "LS" -> parts.getOrNull(2)
            "WG" -> parts
                .takeIf { isWeekendGapFingerprint(it) }
                ?.let { listOf(it[2], it[4], it[5]).joinToString("|") }
            "TX" -> parts
                .takeIf { isTrendExplanationFingerprint(it) }
                ?.let { listOf(it[2], it[4], it[5]).joinToString("|") }
            // The saved deadline is material: moving it changes what the forecast is
            // being compared against, even when the status and both bands are unchanged.
            "GF" -> parts
                .takeIf { isGoalForecastFingerprint(it) }
                ?.let { listOf(it[2], it[4], it[6], it[7], it[8]).joinToString("|") }
            "MT" -> parts
                .takeIf {
                    it.size == 6 &&
                        it[2] == MEASURED_TDEE_FINGERPRINT_VERSION &&
                        isIsoDate(it[3]) &&
                        it[4] in measuredTdeeConfidences &&
                        measuredTdeeBandOrNull(it[5]) != null
                }
                ?.let { listOf(it[2], it[4], it[5]).joinToString("|") }
            else -> null
        }
    }

    internal fun calorieBand(absolutePercent: BigDecimal): String = when {
        absolutePercent <= BigDecimal("5") -> "NEAR"
        absolutePercent <= BigDecimal("15") -> "MODERATE"
        absolutePercent <= BigDecimal("25") -> "LARGE"
        else -> "VERY_LARGE"
    }

    internal fun scoreTrendBand(absolutePoints: Int): String = when {
        absolutePoints <= 5 -> "SMALL"
        absolutePoints <= 10 -> "MEDIUM"
        else -> "LARGE"
    }

    internal fun proteinBand(percentage: Int): String = when {
        percentage < 50 -> "LOW"
        percentage < 75 -> "DEVELOPING"
        percentage < 90 -> "CLOSE"
        else -> "TARGET"
    }

    internal fun measuredTdeeBand(displayedValue: Int): String =
        "B${(displayedValue / 100) * 100}"

    private fun direction(sign: Int): String = when {
        sign < 0 -> "DOWN"
        sign > 0 -> "UP"
        else -> "STABLE"
    }

    private fun fingerprint(vararg parts: Any): String =
        (listOf(PREFIX) + parts.map(Any::toString)).joinToString("|")

    private fun generatedKindOf(key: String): DashboardInsightKind? {
        if (key.length > MAX_KEY_LENGTH) return null
        val parts = key.split('|')
        if (parts.firstOrNull() != PREFIX) return null
        return when (parts.getOrNull(1)) {
            "CA" -> DashboardInsightKind.CALORIE_ADHERENCE.takeIf {
                parts.size == 5 && isIsoDate(parts[2]) &&
                    parts[3] in directions && parts[4] in calorieBands
            }
            "BD" -> DashboardInsightKind.BEST_DAY.takeIf {
                parts.size == 5 && isIsoDate(parts[2]) && isIsoDate(parts[3]) &&
                    parts[4].toIntOrNull()?.let { it in 0..100 } == true
            }
            "ST" -> DashboardInsightKind.SCORE_TREND.takeIf {
                parts.size == 5 && isIsoDate(parts[2]) &&
                    parts[3] in scoreDirections && parts[4] in scoreBands
            }
            "PC" -> DashboardInsightKind.PROTEIN_CONSISTENCY.takeIf {
                parts.size == 5 && isIsoDate(parts[2]) &&
                    parts[3] in proteinBands && parts[4] in proteinTrends
            }
            "LS" -> DashboardInsightKind.LOGGING_STREAK.takeIf {
                parts.size == 3 && parts[2] in streakMilestones.map { "M$it" }
            }
            "WG" -> DashboardInsightKind.WEEKEND_GAP.takeIf { isWeekendGapFingerprint(parts) }
            "TX" -> DashboardInsightKind.TREND_EXPLANATION.takeIf { isTrendExplanationFingerprint(parts) }
            "GF" -> DashboardInsightKind.GOAL_FORECAST.takeIf { isGoalForecastFingerprint(parts) }
            "MT" -> DashboardInsightKind.MEASURED_TDEE.takeIf {
                parts.size == 6 &&
                    parts[2] == MEASURED_TDEE_FINGERPRINT_VERSION &&
                    isIsoDate(parts[3]) &&
                    parts[4] in measuredTdeeConfidences &&
                    measuredTdeeBandOrNull(parts[5]) != null
            }
            else -> null
        }
    }

    private fun isWeekendGapFingerprint(parts: List<String>): Boolean =
        parts.size == 6 &&
            parts[1] == "WG" &&
            parts[2] == WEEKEND_GAP_FINGERPRINT_VERSION &&
            isIsoDate(parts[3]) &&
            parts[4] in weekendGapDirections &&
            parts[5] in weekendGapBands

    private fun isTrendExplanationFingerprint(parts: List<String>): Boolean =
        parts.size == 6 &&
            parts[1] == "TX" &&
            parts[2] == TREND_EXPLANATION_FINGERPRINT_VERSION &&
            isIsoDate(parts[3]) &&
            parts[4] in trendExplanationWeightBands &&
            parts[5] in trendExplanationIntakeBands

    private fun isGoalForecastFingerprint(parts: List<String>): Boolean =
        parts.size == 9 &&
            parts[1] == "GF" &&
            parts[2] == GOAL_FORECAST_FINGERPRINT_VERSION &&
            isIsoDate(parts[3]) &&
            isIsoDate(parts[4]) &&
            (parts[5] == NO_FORECAST_DATE || isIsoDate(parts[5])) &&
            parts[6] in goalForecastStatusCodes &&
            parts[7] in goalForecastDelayBands &&
            parts[8] in goalForecastProgressBands

    private fun isIsoDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess

    private const val MAX_KEY_LENGTH = 80
    private val tipPattern = Regex("^COACH_TIP_(?:[0-9]|[1-3][0-9])$")
    private val directions = setOf("DOWN", "UP", "STABLE")
    private val scoreDirections = setOf("DOWN", "UP")
    private val calorieBands = setOf("NEAR", "MODERATE", "LARGE", "VERY_LARGE")
    private val scoreBands = setOf("SMALL", "MEDIUM", "LARGE")
    private val proteinBands = setOf("LOW", "DEVELOPING", "CLOSE", "TARGET")
    private val proteinTrends = setOf("DOWN", "UP", "STABLE", "UNKNOWN")
    private val measuredTdeeConfidences = RecalibrationConfidence.entries.mapTo(mutableSetOf()) { it.name }
    private val weekendGapDirections = WeekendGapDirection.entries.mapTo(mutableSetOf()) { it.name }
    private val weekendGapBands = WeekendGapBand.entries.mapTo(mutableSetOf()) { it.name }
    private val trendExplanationWeightBands = TrendExplanationWeightBand.entries.mapTo(mutableSetOf()) { it.name }
    private val trendExplanationIntakeBands = TrendExplanationIntakeBand.entries.mapTo(mutableSetOf()) { it.name }
    private const val NO_FORECAST_DATE = "NONE"
    private val goalForecastStatusCodes =
        GoalForecastStatus.entries.mapTo(mutableSetOf(), ::goalForecastStatusCode)
    private val goalForecastDelayBands = GoalForecastDelayBand.entries.mapTo(mutableSetOf()) { it.name }
    private val goalForecastProgressBands = GoalForecastProgressBand.entries.mapTo(mutableSetOf()) { it.name }

    /**
     * Kinds whose impressions are only ever issued as structured fingerprints.
     * A legacy kind-only identity is never accepted for them.
     */
    private val generatedOnlyKinds = setOf(
        DashboardInsightKind.MEASURED_TDEE,
        DashboardInsightKind.WEEKEND_GAP,
        DashboardInsightKind.TREND_EXPLANATION,
        DashboardInsightKind.GOAL_FORECAST,
    )

    private fun measuredTdeeBandOrNull(value: String): String? {
        if (!value.startsWith("B")) return null
        val band = value.removePrefix("B").toIntOrNull() ?: return null
        return band.takeIf { it >= 0 && it % 100 == 0 && value == "B$it" }?.let { value }
    }
}
