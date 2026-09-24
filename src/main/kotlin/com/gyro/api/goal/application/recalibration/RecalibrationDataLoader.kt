package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.goal.domain.hasTrustedDailyEnergyDelta
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.goal.infrastructure.PlanRecalibrationSuggestionRepository
import com.gyro.api.goal.infrastructure.PlanTargetRegimeBoundaryRepository
import com.gyro.api.jooq.Tables.DIARY_ENTRIES
import com.gyro.api.jooq.Tables.WEIGHT_ENTRIES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class RecalibrationEvidenceBoundarySource {
    PLAN_START,
    ACCEPTED_RECALIBRATION,
    SCHEDULE_CHANGE,
}

data class RecalibrationEvidenceBoundary(
    val date: LocalDate,
    val source: RecalibrationEvidenceBoundarySource,
)

data class RecalibrationData(
    val plan: NutritionPlanEntity,
    val today: LocalDate,
    val windowStart: LocalDate,
    val intakeThrough: LocalDate,
    val weightThrough: LocalDate,
    val weights: List<RecalibrationWeightPoint>,
    val loggedDays: Int,
    val recentLoggedDays: Int = 0,
    val windowDays: Int = RecalibrationWindowPolicy.FOURTEEN_DAYS.days,
    val averageLoggedCalories: BigDecimal,
    val averageHistoricalTargetCalories: BigDecimal?,
) {
    fun input(): RecalibrationInput = RecalibrationInput(
        weights = weights,
        loggedDays = loggedDays,
        recentLoggedDays = recentLoggedDays,
        windowDays = windowDays,
        averageLoggedCalories = averageLoggedCalories,
        windowStart = windowStart,
        intakeThrough = intakeThrough,
        weightThrough = weightThrough,
        averageHistoricalTargetCalories = averageHistoricalTargetCalories,
        currentCalories = plan.calories,
        currentProtein = plan.protein,
        currentCarbs = plan.carbs,
        currentFat = plan.fat,
        intendedDailyEnergyDelta = requireNotNull(plan.dailyEnergyDelta),
    )
}

data class RecalibrationWindowCandidates(
    val windows: List<RecalibrationData>,
    val boundary: RecalibrationEvidenceBoundary,
)

/** Read-only, local-date aligned evidence for recalibration and Coach state. */
@Component
class RecalibrationDataLoader(
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val dailyTargetLoader: ScheduleAwareDailyTargetLoader,
    private val suggestionRepository: PlanRecalibrationSuggestionRepository,
    private val targetRegimeBoundaryRepository: PlanTargetRegimeBoundaryRepository,
    private val dsl: DSLContext,
    private val timeProvider: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun loadForProducer(userId: UUID): RecalibrationWindowCandidates? {
        val plan = activePlanForProducer(userId) ?: return null
        if (!plan.hasTrustedDailyEnergyDelta()) return null
        val zone = zoneFor(plan)
        val today = timeProvider.now().atZone(zone).toLocalDate()
        val boundary = latestRegimeBoundary(userId, plan, zone, today)
        val evidence = loadEvidence(
            userId = userId,
            from = maxOf(boundary.date, today.minusDays(RecalibrationWindowPolicy.TWENTY_EIGHT_DAYS.days.toLong())),
            today = today,
            includeHistoricalTargetAverage = true,
        )
        return RecalibrationWindowCandidates(
            windows = RecalibrationWindowPolicy.ordered.map { policy ->
                candidateFromEvidence(plan, today, boundary.date, policy, evidence)
            },
            boundary = boundary,
        )
    }

    @Transactional(readOnly = true)
    fun loadForPlan(userId: UUID, plan: NutritionPlanEntity): RecalibrationData? =
        loadForPlan(userId, plan, zoneFor(plan), includeHistoricalTargetAverage = false)

    @Transactional(readOnly = true)
    fun loadForPlan(userId: UUID, plan: NutritionPlanEntity, zone: ZoneId): RecalibrationData? =
        loadForPlan(userId, plan, zone, includeHistoricalTargetAverage = false)

    @Transactional(readOnly = true)
    fun loadReadinessForPlan(userId: UUID, plan: NutritionPlanEntity, zone: ZoneId): RecalibrationData? =
        loadForPlan(userId, plan, zone, includeHistoricalTargetAverage = false, earliestDate = plan.startDate)

    private fun loadForPlan(
        userId: UUID,
        plan: NutritionPlanEntity,
        zone: ZoneId,
        includeHistoricalTargetAverage: Boolean,
        earliestDate: LocalDate? = null,
    ): RecalibrationData? {
        if (!plan.hasTrustedDailyEnergyDelta()) return null
        val today = timeProvider.now().atZone(zone).toLocalDate()
        val boundary = earliestDate ?: today.minusDays(RecalibrationWindowPolicy.FOURTEEN_DAYS.days.toLong())
        val evidence = loadEvidence(
            userId = userId,
            from = maxOf(boundary, today.minusDays(RecalibrationWindowPolicy.FOURTEEN_DAYS.days.toLong())),
            today = today,
            includeHistoricalTargetAverage = includeHistoricalTargetAverage,
        )
        return candidateFromEvidence(
            plan,
            today,
            boundary,
            RecalibrationWindowPolicy.FOURTEEN_DAYS,
            evidence,
        )
    }

    private fun loadEvidence(
        userId: UUID,
        from: LocalDate,
        today: LocalDate,
        includeHistoricalTargetAverage: Boolean,
    ): LoadedRecalibrationEvidence {
        val weights = dsl.select(WEIGHT_ENTRIES.RECORDED_DATE, WEIGHT_ENTRIES.WEIGHT_KG)
            .from(WEIGHT_ENTRIES)
            .where(WEIGHT_ENTRIES.USER_ID.eq(userId).and(WEIGHT_ENTRIES.RECORDED_DATE.between(from, today)))
            .fetch { record -> RecalibrationWeightPoint(record[WEIGHT_ENTRIES.RECORDED_DATE], record[WEIGHT_ENTRIES.WEIGHT_KG]) }
        val totalCalories = DSL.sum(DIARY_ENTRIES.CALORIES_SNAPSHOT).`as`("total_calories")
        val intakeDays = dsl.select(DIARY_ENTRIES.DIARY_DATE, totalCalories)
            .from(DIARY_ENTRIES)
            .where(DIARY_ENTRIES.USER_ID.eq(userId).and(DIARY_ENTRIES.DIARY_DATE.ge(from)).and(DIARY_ENTRIES.DIARY_DATE.lt(today)))
            .groupBy(DIARY_ENTRIES.DIARY_DATE)
            .orderBy(DIARY_ENTRIES.DIARY_DATE)
            .fetch { record -> LoggedIntakeDay(record[DIARY_ENTRIES.DIARY_DATE], record[totalCalories]) }
        val targets = if (includeHistoricalTargetAverage) {
            dailyTargetLoader.load(userId, intakeDays.map(LoggedIntakeDay::date))
                .mapValues { (_, target) -> target.target.targets.calories }
        } else {
            emptyMap()
        }
        return LoadedRecalibrationEvidence(weights, intakeDays, targets)
    }

    private fun candidateFromEvidence(
        plan: NutritionPlanEntity,
        today: LocalDate,
        boundary: LocalDate,
        policy: RecalibrationWindowPolicy,
        evidence: LoadedRecalibrationEvidence,
    ): RecalibrationData {
        val windowStart = maxOf(boundary, today.minusDays(policy.days.toLong()))
        val weights = evidence.weights.filter { !it.date.isBefore(windowStart) }
        val intakeDays = evidence.intakeDays.filter { !it.date.isBefore(windowStart) }
        val loggedDays = intakeDays.size
        val totalLoggedCalories = intakeDays.fold(BigDecimal.ZERO) { total, day -> total.add(day.calories) }
        val targetCalories = intakeDays.mapNotNull { day -> evidence.targets[day.date]?.takeIf { it > BigDecimal.ZERO } }
        return RecalibrationData(
            plan = plan,
            today = today,
            windowStart = windowStart,
            intakeThrough = today.minusDays(1),
            weightThrough = today,
            weights = weights,
            loggedDays = loggedDays,
            recentLoggedDays = intakeDays.count { !it.date.isBefore(today.minusDays(7)) },
            windowDays = policy.days,
            averageLoggedCalories = if (loggedDays == 0) BigDecimal.ZERO else totalLoggedCalories.divide(BigDecimal(loggedDays), 2, RoundingMode.HALF_UP),
            averageHistoricalTargetCalories = targetCalories
                .takeIf { it.size == loggedDays && it.isNotEmpty() }
                ?.fold(BigDecimal.ZERO, BigDecimal::add)
                ?.divide(BigDecimal(loggedDays), 2, RoundingMode.HALF_UP),
        )
    }

    @Transactional(readOnly = true)
    fun activePlanFor(userId: UUID, zone: ZoneId): NutritionPlanEntity? =
        nutritionPlanRepository.findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, timeProvider.today(zone))

    @Transactional(readOnly = true)
    fun activePlanForProducer(userId: UUID): NutritionPlanEntity? =
        nutritionPlanRepository.findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, timeProvider.now().atZone(DEFAULT_ZONE).toLocalDate())

    @Transactional(readOnly = true)
    fun loggedDays(userId: UUID, from: LocalDate, toExclusive: LocalDate): Int =
        dsl.select(DSL.countDistinct(DIARY_ENTRIES.DIARY_DATE))
            .from(DIARY_ENTRIES)
            .where(DIARY_ENTRIES.USER_ID.eq(userId).and(DIARY_ENTRIES.DIARY_DATE.ge(from)).and(DIARY_ENTRIES.DIARY_DATE.lt(toExclusive)))
            .fetchOne(0, Int::class.java) ?: 0

    private fun latestRegimeBoundary(
        userId: UUID,
        plan: NutritionPlanEntity,
        zone: ZoneId,
        today: LocalDate,
    ): RecalibrationEvidenceBoundary {
        val planId = requireNotNull(plan.id)
        val acceptedDate = suggestionRepository
            .findFirstByUserIdAndNutritionPlanIdAndStatusOrderByDecidedAtDesc(userId, planId, RecalibrationSuggestionStatus.ACCEPTED)
            ?.decidedAt
            ?.atZone(zone)
            ?.toLocalDate()
        val scheduleDate = targetRegimeBoundaryRepository.latestScheduleBoundaryOnOrBefore(planId, today)
        return listOfNotNull(
            RecalibrationEvidenceBoundary(plan.startDate, RecalibrationEvidenceBoundarySource.PLAN_START),
            acceptedDate?.let { RecalibrationEvidenceBoundary(it, RecalibrationEvidenceBoundarySource.ACCEPTED_RECALIBRATION) },
            scheduleDate?.let { RecalibrationEvidenceBoundary(it, RecalibrationEvidenceBoundarySource.SCHEDULE_CHANGE) },
        ).maxWith(compareBy<RecalibrationEvidenceBoundary> { it.date }.thenBy { it.source.ordinal })
    }

    private fun zoneFor(plan: NutritionPlanEntity): ZoneId =
        runCatching { ZoneId.of(plan.timezone) }.getOrDefault(DEFAULT_ZONE)

    private data class LoadedRecalibrationEvidence(
        val weights: List<RecalibrationWeightPoint>,
        val intakeDays: List<LoggedIntakeDay>,
        val targets: Map<LocalDate, BigDecimal>,
    )

    private data class LoggedIntakeDay(val date: LocalDate, val calories: BigDecimal)

    private companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Tehran")
    }
}
