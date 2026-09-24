package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.trend.LinearTrend
import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.jooq.Tables.DIARY_ENTRIES
import com.gyro.api.jooq.Tables.WEIGHT_ENTRIES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

enum class ObservedEnergyEvidenceStatus {
    SUFFICIENT,
    INSUFFICIENT_FOOD,
    INSUFFICIENT_WEIGHT_DAYS,
    INSUFFICIENT_WEIGHT_SPAN,
}

data class ObservedEnergyAnalysis(
    val status: ObservedEnergyEvidenceStatus,
    val windowDays: Int,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val loggedDays: Int,
    val loggedDaysRequired: Int,
    val recentLoggedDays: Int,
    val weighInDays: Int,
    val weightSpanDays: Long,
    val averageLoggedCalories: BigDecimal?,
    val observedKgPerWeek: BigDecimal?,
    val estimatedTdee: BigDecimal?,
    val confidence: RecalibrationConfidence?,
    val trendRSquared: BigDecimal?,
    val trendStdErrorKgPerDay: BigDecimal?,
) {
    val sufficient: Boolean get() = status == ObservedEnergyEvidenceStatus.SUFFICIENT

    fun auditBasis(): Map<String, Any?> = mapOf(
        "windowDays" to windowDays,
        "windowStart" to windowStart.toString(),
        "windowEnd" to windowEnd.toString(),
        "intakeThrough" to windowEnd.toString(),
        "weightThrough" to windowEnd.toString(),
        "loggedDays" to loggedDays,
        "loggedDaysRequired" to loggedDaysRequired,
        "recentLoggedDays" to recentLoggedDays,
        "weighInDays" to weighInDays,
        "weightSpanDays" to weightSpanDays,
        "averageLoggedCalories" to averageLoggedCalories?.toPlainString(),
        "observedKgPerWeek" to observedKgPerWeek?.toPlainString(),
        "estimatedTdee" to estimatedTdee?.toPlainString(),
        "confidence" to confidence?.name,
        "trendMethod" to "OLS",
        "trendRSquared" to trendRSquared?.toPlainString(),
        "trendStdErrorKgPerDay" to trendStdErrorKgPerDay?.toPlainString(),
    )
}

/**
 * One window's evaluation paired with the per-date recorded intake that produced it.
 *
 * The intake map lets a downstream observation compare each logged day against the
 * historical target active on that same date without a second diary read.
 */
data class ObservedEnergyWindowEvidence(
    val analysis: ObservedEnergyAnalysis,
    val intakeByDate: Map<LocalDate, BigDecimal>,
)

/** Plan-independent intake and weight evidence over completed local dates. */
@Service
class ObservedEnergyAnalysisService(
    private val dsl: DSLContext,
) {
    /**
     * The ordered 14/21/28-day evaluations from a single food-and-weight read, each
     * carrying the recorded intake that fell inside its window. [analyze] selects the
     * first sufficient window from this projection.
     */
    @Transactional(readOnly = true)
    fun windowEvidence(
        userId: UUID,
        windowEnd: LocalDate,
        earliestDate: LocalDate? = null,
    ): List<ObservedEnergyWindowEvidence> {
        val oldestStart = windowEnd.minusDays(RecalibrationWindowPolicy.TWENTY_EIGHT_DAYS.days - 1L)
        val loadFrom = earliestDate?.let { maxOf(it, oldestStart) } ?: oldestStart
        val weights = dsl.select(WEIGHT_ENTRIES.RECORDED_DATE, WEIGHT_ENTRIES.WEIGHT_KG)
            .from(WEIGHT_ENTRIES)
            .where(
                WEIGHT_ENTRIES.USER_ID.eq(userId)
                    .and(WEIGHT_ENTRIES.RECORDED_DATE.between(loadFrom, windowEnd)),
            )
            .fetch { TrendPoint(it[WEIGHT_ENTRIES.RECORDED_DATE], it[WEIGHT_ENTRIES.WEIGHT_KG]) }
        val totalCalories = DSL.sum(DIARY_ENTRIES.CALORIES_SNAPSHOT).`as`("total_calories")
        val intake = dsl.select(DIARY_ENTRIES.DIARY_DATE, totalCalories)
            .from(DIARY_ENTRIES)
            .where(
                DIARY_ENTRIES.USER_ID.eq(userId)
                    .and(DIARY_ENTRIES.DIARY_DATE.between(loadFrom, windowEnd)),
            )
            .groupBy(DIARY_ENTRIES.DIARY_DATE)
            .fetchMap(DIARY_ENTRIES.DIARY_DATE, totalCalories)

        return RecalibrationWindowPolicy.ordered.map { policy ->
            val analysis = evaluate(policy, windowEnd, earliestDate, weights, intake)
            val intakeByDate = intake
                .filter { (date, calories) ->
                    calories != null &&
                        !date.isBefore(analysis.windowStart) &&
                        !date.isAfter(analysis.windowEnd)
                }
                .mapValues { (_, calories) -> requireNotNull(calories) }
            ObservedEnergyWindowEvidence(analysis, intakeByDate)
        }
    }

    @Transactional(readOnly = true)
    fun analyze(
        userId: UUID,
        windowEnd: LocalDate,
        earliestDate: LocalDate? = null,
    ): ObservedEnergyAnalysis {
        return selectAnalysis(windowEvidence(userId, windowEnd, earliestDate))
    }

    /** Selects the first sufficient 14/21/28 projection, matching [analyze]. */
    fun selectAnalysis(windows: List<ObservedEnergyWindowEvidence>): ObservedEnergyAnalysis {
        require(windows.isNotEmpty()) { "Observed-energy windows must not be empty." }
        return windows.firstOrNull { it.analysis.sufficient }?.analysis ?: windows.last().analysis
    }

    private fun evaluate(
        policy: RecalibrationWindowPolicy,
        windowEnd: LocalDate,
        earliestDate: LocalDate?,
        weights: List<TrendPoint>,
        intake: Map<LocalDate, BigDecimal?>,
    ): ObservedEnergyAnalysis {
        val nominalStart = windowEnd.minusDays(policy.days - 1L)
        val windowStart = earliestDate?.let { maxOf(it, nominalStart) } ?: nominalStart
        val effectiveWindowDays = (windowEnd.toEpochDay() - windowStart.toEpochDay() + 1L).toInt().coerceAtLeast(1)
        val windowWeights = weights.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(windowEnd) }
        val fit = LinearTrend.fitDaily(windowWeights)
        val weighInDays = windowWeights.map(TrendPoint::date).distinct().size
        val weightSpanDays = fit?.spanDays ?: 0L
        val intakeValues = intake.filterKeys { !it.isBefore(windowStart) && !it.isAfter(windowEnd) }.values.filterNotNull()
        val loggedDays = intakeValues.size
        val loggedDaysRequired = if (effectiveWindowDays == policy.days) {
            policy.minimumLoggedDays
        } else {
            kotlin.math.ceil(effectiveWindowDays * policy.minimumCoverage).toInt()
        }
        val recentStart = windowEnd.minusDays(6)
        val recentLoggedDays = intake.keys.count { !it.isBefore(maxOf(windowStart, recentStart)) && !it.isAfter(windowEnd) }
        val loggedRatio = loggedDays.toDouble() / effectiveWindowDays
        val foodSufficient = loggedDays >= loggedDaysRequired && loggedRatio >= policy.minimumCoverage &&
            (!policy.requiresRecentCoverage || recentLoggedDays >= MINIMUM_RECENT_LOGGED_DAYS)
        val status = when {
            !foodSufficient -> ObservedEnergyEvidenceStatus.INSUFFICIENT_FOOD
            weighInDays < RecalibrationConfidence.LOW_WEIGH_IN_DAYS ->
                ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_DAYS
            weightSpanDays < RecalibrationConfidence.LOW_SPAN_DAYS ->
                ObservedEnergyEvidenceStatus.INSUFFICIENT_WEIGHT_SPAN
            else -> ObservedEnergyEvidenceStatus.SUFFICIENT
        }
        val averageIntake = intakeValues.takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO, BigDecimal::add)
            ?.divide(BigDecimal(loggedDays), 2, RoundingMode.HALF_UP)
        val confidence = if (status == ObservedEnergyEvidenceStatus.SUFFICIENT) {
            RecalibrationConfidence.of(weighInDays, weightSpanDays, loggedRatio)
        } else {
            null
        }
        val observedKgPerWeek = fit?.slopePerDay?.multiply(BigDecimal(7))?.setScale(3, RoundingMode.HALF_UP)
        val estimatedTdee = if (confidence != null && averageIntake != null && fit != null) {
            averageIntake.subtract(fit.slopePerDay.multiply(KCAL_PER_KG)).setScale(2, RoundingMode.HALF_UP)
        } else {
            null
        }
        return ObservedEnergyAnalysis(
            status = status,
            windowDays = effectiveWindowDays,
            windowStart = windowStart,
            windowEnd = windowEnd,
            loggedDays = loggedDays,
            loggedDaysRequired = loggedDaysRequired,
            recentLoggedDays = recentLoggedDays,
            weighInDays = weighInDays,
            weightSpanDays = weightSpanDays,
            averageLoggedCalories = averageIntake,
            observedKgPerWeek = observedKgPerWeek,
            estimatedTdee = estimatedTdee,
            confidence = confidence,
            trendRSquared = fit?.rSquared,
            trendStdErrorKgPerDay = fit?.slopeStdError,
        )
    }

    private companion object {
        val KCAL_PER_KG = BigDecimal(7700)
        const val MINIMUM_RECENT_LOGGED_DAYS = 3
    }
}
