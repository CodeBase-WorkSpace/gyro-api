package com.gyro.api.goal.application.coach

import com.gyro.api.common.trend.TrendPoint
import com.gyro.api.diary.application.GoalForecastCandidateOutcome
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.jooq.Tables.WEIGHT_ENTRIES
import com.gyro.api.weight.domain.WeightUnit
import com.gyro.api.weight.domain.toKilograms
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** Which way the saved goal moves weight. Maintenance goals never reach here. */
internal enum class GoalForecastDirection { LOSS, GAIN }

/**
 * A compatible goal, its canonical kilogram values, and the bounded weight evidence
 * available for projecting it. Everything here is a recorded fact; no gate beyond goal
 * compatibility has been applied yet.
 */
internal data class GoalForecastEvidence(
    val today: LocalDate,
    val planStart: LocalDate,
    val originalTargetDate: LocalDate,
    val direction: GoalForecastDirection,
    val startWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal,
    /** First completed date the forecast may read, bounded by the plan start. */
    val windowStart: LocalDate,
    /** Yesterday in the user's timezone. Today's weight is never used. */
    val windowEnd: LocalDate,
    val weights: List<TrendPoint>,
)

internal sealed interface GoalForecastEvidenceOutcome {
    data class Eligible(val evidence: GoalForecastEvidence) : GoalForecastEvidenceOutcome

    /** A goal that can never carry a forecast, so no observation is produced at all. */
    data class Ineligible(val outcome: GoalForecastCandidateOutcome) : GoalForecastEvidenceOutcome
}

/** A saved goal that can be forecast, in canonical kilograms. */
internal data class GoalForecastGoal(
    val planStart: LocalDate,
    val originalTargetDate: LocalDate,
    val direction: GoalForecastDirection,
    val startWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal,
)

internal sealed interface GoalForecastGoalResolution {
    data class Compatible(val goal: GoalForecastGoal) : GoalForecastGoalResolution
    data class Incompatible(val outcome: GoalForecastCandidateOutcome) : GoalForecastGoalResolution
}

/**
 * Decides whether a saved plan describes a goal a forecast may be built for, and in
 * what canonical kilogram values.
 *
 * This is pure on purpose: every rule here is a product decision about the *goal*, not
 * about the weight evidence, and it must be assertable without a database. Nothing in
 * it mutates the plan — a pounds goal is converted for reading only, and the calculator
 * snapshot is consulted, never rewritten.
 */
internal object GoalForecastGoalPolicy {
    val MIN_PLANNED_CHANGE_KG: BigDecimal = BigDecimal("1.0")

    /**
     * How many days after the plan start a weigh-in may still be accepted as the
     * baseline. The bound is inclusive on both ends, so the plan start date itself and
     * the seventh day after it both qualify and the range spans eight calendar dates.
     * "Seven days after the start" is the approved contract; it is deliberately not the
     * same thing as a seven-date week.
     */
    const val START_WEIGHT_MAX_DAYS_AFTER_START = 7L

    /** The inclusive date range a baseline weigh-in may fall in. */
    fun startWeightWindow(planStart: LocalDate): ClosedRange<LocalDate> =
        planStart..planStart.plusDays(START_WEIGHT_MAX_DAYS_AFTER_START)

    /**
     * [startPeriodWeightKg] supplies the fallback baseline — the earliest weigh-in in the
     * start-weight window — and is only invoked when the plan carries no calculator
     * snapshot, so a manual plan costs one extra read and a calculator plan costs none.
     */
    fun resolve(
        plan: NutritionPlanEntity,
        today: LocalDate,
        startPeriodWeightKg: () -> BigDecimal?,
    ): GoalForecastGoalResolution {
        val planStart = plan.startDate
        if (planStart.isAfter(today)) return incompatible(GoalForecastCandidateOutcome.NO_ACTIVE_GOAL)
        if (plan.calculatorGoalType == GoalType.MAINTAIN_WEIGHT) {
            return incompatible(GoalForecastCandidateOutcome.MAINTENANCE_GOAL)
        }

        val targetDate = plan.targetDate
            ?: return incompatible(GoalForecastCandidateOutcome.MISSING_TARGET)
        if (!targetDate.isAfter(planStart)) {
            return incompatible(GoalForecastCandidateOutcome.MISSING_TARGET)
        }
        val targetWeightKg = canonicalTargetWeightKg(plan)
            ?: return incompatible(GoalForecastCandidateOutcome.MISSING_TARGET)
        val startWeightKg = calculatorStartWeightKg(plan)
            ?: startPeriodWeightKg()
            ?: return incompatible(GoalForecastCandidateOutcome.NO_START_WEIGHT)

        val plannedChange = targetWeightKg.subtract(startWeightKg)
        if (plannedChange.abs() < MIN_PLANNED_CHANGE_KG) {
            return incompatible(GoalForecastCandidateOutcome.PLANNED_CHANGE_TOO_SMALL)
        }
        val direction =
            if (plannedChange.signum() < 0) GoalForecastDirection.LOSS else GoalForecastDirection.GAIN
        // A saved goal type that contradicts its own weights is a broken plan, not a slow
        // one. Nothing here repairs it; the observation is simply not offered.
        val declaredDirection = when (plan.calculatorGoalType) {
            GoalType.LOSE_WEIGHT -> GoalForecastDirection.LOSS
            GoalType.GAIN_WEIGHT -> GoalForecastDirection.GAIN
            GoalType.MAINTAIN_WEIGHT, null -> null
        }
        if (declaredDirection != null && declaredDirection != direction) {
            return incompatible(GoalForecastCandidateOutcome.DIRECTION_CONFLICT)
        }

        return GoalForecastGoalResolution.Compatible(
            GoalForecastGoal(
                planStart = planStart,
                originalTargetDate = targetDate,
                direction = direction,
                startWeightKg = startWeightKg,
                targetWeightKg = targetWeightKg,
            ),
        )
    }

    /**
     * The calculator's own kilogram value first, then the saved goal converted from its
     * display unit. A pounds-based goal is therefore forecast in kilograms without the
     * stored plan being normalized.
     */
    fun canonicalTargetWeightKg(plan: NutritionPlanEntity): BigDecimal? =
        plan.calculatorTargetWeightKg?.takeIf { it.signum() > 0 }
            ?: plan.targetWeight
                ?.takeIf { it.signum() > 0 }
                ?.let { (plan.targetWeightUnit ?: WeightUnit.KG).toKilograms(it) }

    /**
     * The calculator baseline, accepted only from a snapshot that recorded both of its
     * own weights. A half-written snapshot falls through to the start-period weigh-in.
     */
    fun calculatorStartWeightKg(plan: NutritionPlanEntity): BigDecimal? =
        plan.calculatorCurrentWeightKg
            ?.takeIf { it.signum() > 0 && plan.calculatorTargetWeightKg?.signum() == 1 }

    private fun incompatible(outcome: GoalForecastCandidateOutcome) =
        GoalForecastGoalResolution.Incompatible(outcome)
}

/**
 * Read-only resolution of the goal and weight evidence behind the forecast.
 *
 * It reads the saved plan and at most 28 completed days of weight measurements, and it
 * never writes: the saved target date and the calculator snapshot are provenance that a
 * forecast refresh must leave exactly as it found them. Every rule that does not need
 * the database lives in [GoalForecastGoalPolicy] or [GoalForecastObservationFactory].
 */
@Service
class GoalForecastEvidenceService(
    private val dsl: DSLContext,
) {
    @Transactional(readOnly = true)
    internal fun evidenceFor(
        userId: UUID,
        plan: NutritionPlanEntity,
        today: LocalDate,
    ): GoalForecastEvidenceOutcome {
        val goal = when (
            val resolution = GoalForecastGoalPolicy.resolve(plan, today) {
                startPeriodWeightKg(userId, plan.startDate)
            }
        ) {
            is GoalForecastGoalResolution.Incompatible ->
                return GoalForecastEvidenceOutcome.Ineligible(resolution.outcome)
            is GoalForecastGoalResolution.Compatible -> resolution.goal
        }

        val windowEnd = today.minusDays(1)
        val windowStart = maxOf(goal.planStart, today.minusDays(EVIDENCE_WINDOW_DAYS))
        val weights = if (windowStart.isAfter(windowEnd)) {
            emptyList()
        } else {
            weightsBetween(userId, windowStart, windowEnd)
        }

        return GoalForecastEvidenceOutcome.Eligible(
            GoalForecastEvidence(
                today = today,
                planStart = goal.planStart,
                originalTargetDate = goal.originalTargetDate,
                direction = goal.direction,
                startWeightKg = goal.startWeightKg,
                targetWeightKg = goal.targetWeightKg,
                windowStart = windowStart,
                windowEnd = windowEnd,
                weights = weights,
            ),
        )
    }

    /**
     * The earliest weigh-in within [GoalForecastGoalPolicy.startWeightWindow]. A later
     * weigh-in is deliberately not accepted, because it would redefine the milestone
     * weights of a plan already under way.
     */
    private fun startPeriodWeightKg(userId: UUID, planStart: LocalDate): BigDecimal? {
        val window = GoalForecastGoalPolicy.startWeightWindow(planStart)
        val startPeriod = weightsBetween(
            userId = userId,
            from = window.start,
            to = window.endInclusive,
        )
        val earliestDate = startPeriod.minOfOrNull { it.date } ?: return null
        val sameDay = startPeriod.filter { it.date == earliestDate }
        return sameDay
            .fold(BigDecimal.ZERO) { total, point -> total.add(point.value) }
            .divide(BigDecimal(sameDay.size), WEIGHT_SCALE, RoundingMode.HALF_UP)
    }

    private fun weightsBetween(userId: UUID, from: LocalDate, to: LocalDate): List<TrendPoint> =
        dsl.select(WEIGHT_ENTRIES.RECORDED_DATE, WEIGHT_ENTRIES.WEIGHT_KG)
            .from(WEIGHT_ENTRIES)
            .where(
                WEIGHT_ENTRIES.USER_ID.eq(userId)
                    .and(WEIGHT_ENTRIES.RECORDED_DATE.between(from, to)),
            )
            .orderBy(WEIGHT_ENTRIES.RECORDED_DATE.asc())
            .fetch { TrendPoint(it[WEIGHT_ENTRIES.RECORDED_DATE], it[WEIGHT_ENTRIES.WEIGHT_KG]) }

    private companion object {
        const val EVIDENCE_WINDOW_DAYS = 28L
        const val WEIGHT_SCALE = 3
    }
}
