package com.gyro.api.goal.application.goal_schedule

import com.gyro.api.common.decimal.NutritionDecimal
import com.gyro.api.common.error.ApiErrorCode
import com.gyro.api.common.error.FeatureDisabledException
import com.gyro.api.common.error.InvalidGoalScheduleException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.application.nutrition_plan.DailyTargetSource
import com.gyro.api.goal.application.nutrition_plan.NutritionTargetsReadModel
import com.gyro.api.goal.application.nutrition_plan.PlanScheduleSummaryReadModel
import com.gyro.api.goal.config.GoalScheduleProperties
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.goal.domain.PlanScheduleEntity
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.goal.infrastructure.PlanScheduleRepository
import com.gyro.api.goal.infrastructure.PlanTargetRegimeBoundaryRepository
import com.gyro.api.goal.application.recalibration.TargetRegimePolicy
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.EntitlementLossBoundaryService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@Service
class PlanScheduleService(
    private val planScheduleRepository: PlanScheduleRepository,
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val targetRegimeBoundaryRepository: PlanTargetRegimeBoundaryRepository,
    private val goalScheduleProperties: GoalScheduleProperties,
    private val entitlementGateService: EntitlementGateService,
    private val cachedEntitlementService: CachedEntitlementService,
    private val entitlementLossBoundaryService: EntitlementLossBoundaryService,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
    private val timeProvider: TimeProvider,
) {

    data class AdvancedScheduleAccess(
        val activeFrom: LocalDate,
        val degradedFrom: LocalDate?,
        val preserved: Boolean,
    )

    @Transactional
    fun savePlanSchedule(
        userId: UUID,
        command: SavePlanScheduleCommand,
    ): PlanScheduleEntity {
        val plan = planForUser(
            userId = userId,
            nutritionPlanId = command.nutritionPlanId,
        )
        validateScheduleAccess(
            userId = userId,
            scheduleType = command.scheduleType,
        )
        validateScheduleShape(command)

        val existing = planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = command.nutritionPlanId,
            userId = userId,
        )
        val previousAverageCalories = existing?.let { expectedAverageCalories(plan, it.weeklyCalorieBudget, it.weekdayTargets) }
            ?: plan.calories

        // A lapsed user saving base-goal edits sends the default FLAT
        // schedule. Never let that strip their stored premium schedule -- it
        // stays preserved (degraded at resolve time) so resubscribing
        // reactivates the exact plan. Entitled users can still switch to FLAT
        // deliberately.
        if (shouldPreserveExistingAdvancedSchedule(userId, existing, command.scheduleType)) {
            val preserved = requireNotNull(existing)
            StageLog.info(
                logger = logger,
                event = GOAL_LOG_EVENT,
                stage = "premium_schedule_preserved",
                outcome = "kept",
                fields = mapOf("scheduleType" to preserved.scheduleType.name),
            )
            return preserved
        }

        val schedule = existing ?: PlanScheduleEntity(
            userId = userId,
            nutritionPlanId = command.nutritionPlanId,
            scheduleType = command.scheduleType,
            activeFrom = command.activeFrom,
            macroAdjustmentMode = command.macroAdjustmentMode,
        )

        schedule.scheduleType = command.scheduleType
        schedule.activeFrom = command.activeFrom
        schedule.activeTo = command.activeTo
        schedule.weeklyCalorieBudget = command.weeklyCalorieBudget
        schedule.weekdayTargets = command.weekdayTargets
        schedule.dateOverrides = command.dateOverrides
        schedule.macroAdjustmentMode = command.macroAdjustmentMode
        schedule.dietMode = command.dietMode
        schedule.formulaName = command.formulaName
        schedule.formulaVersion = command.formulaVersion
        schedule.scheduleSnapshot = command.scheduleSnapshot

        val saved = planScheduleRepository.saveAndFlush(schedule)
        val currentAverageCalories = expectedAverageCalories(
            plan = plan,
            weeklyCalorieBudget = saved.weeklyCalorieBudget,
            weekdayTargets = saved.weekdayTargets,
        )
        if (TargetRegimePolicy.startsNewRegime(previousAverageCalories, currentAverageCalories)) {
            val zone = runCatching { ZoneId.of(plan.timezone) }.getOrDefault(ZoneId.of("Asia/Tehran"))
            val localToday = timeProvider.today(zone)
            val effectiveFrom = if (saved.activeFrom.isAfter(localToday)) saved.activeFrom else localToday
            targetRegimeBoundaryRepository.insertScheduleBoundaryIfAbsent(
                userId = userId,
                nutritionPlanId = requireNotNull(saved.nutritionPlanId),
                effectiveFrom = effectiveFrom.coerceAtLeast(plan.startDate),
            )
        }
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.GOAL)
        return saved
    }

    @Transactional(readOnly = true)
    fun findPlanSchedule(
        userId: UUID,
        nutritionPlanId: UUID,
    ): PlanScheduleEntity? {
        return planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = nutritionPlanId,
            userId = userId,
        )
    }

    /**
     * Resolves the persisted-state part of a base-goal save before calculator
     * provenance is mutated. Lapsed users submit FLAT because the premium
     * editor is unavailable, but the stored overlay remains authoritative and
     * must still participate in base-plan invariants.
     */
    @Transactional(readOnly = true)
    fun willPreserveAdvancedSchedule(
        userId: UUID,
        nutritionPlanId: UUID,
        requestedScheduleType: GoalScheduleType,
    ): Boolean {
        val existing = planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = nutritionPlanId,
            userId = userId,
        )
        return shouldPreserveExistingAdvancedSchedule(userId, existing, requestedScheduleType)
    }

    @Transactional(readOnly = true)
    fun advancedScheduleAccess(
        userId: UUID,
        plan: NutritionPlanEntity,
    ): AdvancedScheduleAccess? {
        val schedule = planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = requireNotNull(plan.id),
            userId = userId,
        ) ?: return null
        if (schedule.scheduleType == GoalScheduleType.FLAT) return null
        return AdvancedScheduleAccess(
            activeFrom = schedule.activeFrom,
            degradedFrom = premiumDegradeFrom(userId, plan, schedule),
            preserved = true,
        )
    }

    @Transactional(readOnly = true)
    fun findLatestActiveSchedule(
        userId: UUID,
        activeOn: LocalDate,
    ): PlanScheduleEntity? {
        return planScheduleRepository.findFirstByUserIdAndActiveFromLessThanEqualAndActiveToGreaterThanEqualOrderByActiveFromDesc(
            userId = userId,
            activeOn = activeOn,
            activeOnForEnd = activeOn,
        ) ?: planScheduleRepository.findFirstByUserIdAndActiveFromLessThanEqualAndActiveToIsNullOrderByActiveFromDesc(
            userId = userId,
            activeOn = activeOn,
        )
    }

    @Transactional(readOnly = true)
    fun resolveDailyTarget(
        userId: UUID,
        plan: NutritionPlanEntity,
        activeOn: LocalDate,
    ): DailyTargetReadModel {
        val schedule = planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = requireNotNull(plan.id),
            userId = userId,
        )

        return resolveDailyTarget(
            plan = plan,
            activeOn = activeOn,
            schedule = schedule,
            degradedFrom = premiumDegradeFrom(userId, plan, schedule),
        )
    }

    @Transactional(readOnly = true)
    fun resolveDailyTargets(
        userId: UUID,
        plan: NutritionPlanEntity,
        activeDates: List<LocalDate>,
    ): Map<LocalDate, DailyTargetReadModel> {
        if (activeDates.isEmpty()) {
            return emptyMap()
        }
        val schedule = planScheduleRepository.findByNutritionPlanIdAndUserId(
            nutritionPlanId = requireNotNull(plan.id),
            userId = userId,
        )

        val degradedFrom = premiumDegradeFrom(userId, plan, schedule)
        return activeDates.associateWith { activeOn ->
            resolveDailyTarget(
                plan = plan,
                activeOn = activeOn,
                schedule = schedule,
                degradedFrom = degradedFrom,
            )
        }
    }

    private fun resolveDailyTarget(
        plan: NutritionPlanEntity,
        activeOn: LocalDate,
        schedule: PlanScheduleEntity?,
        degradedFrom: LocalDate? = null,
    ): DailyTargetReadModel {
        if (schedule == null || !schedule.isActiveOn(activeOn)) {
            return baseGoalSummary(
                plan = plan,
                activeOn = activeOn,
                type = schedule?.scheduleType ?: GoalScheduleType.FLAT,
            )
        }

        if (schedule.scheduleType == GoalScheduleType.FLAT) {
            return baseGoalSummary(
                plan = plan,
                activeOn = activeOn,
                type = GoalScheduleType.FLAT,
            )
        }

        if (degradedFrom != null && !activeOn.isBefore(degradedFrom)) {
            return degradedAverageSummary(
                plan = plan,
                activeOn = activeOn,
                schedule = schedule,
            )
        }

        val weekdayRule = schedule.weekdayTargets[activeOn.dayOfWeek.name]
        val dateOverride = schedule.dateOverrides[activeOn.toString()]
        val resolved = partialTargets(dateOverride)?.let { partial ->
            buildSummary(
                plan = plan,
                activeOn = activeOn,
                type = schedule.scheduleType,
                source = DailyTargetSource.DATE_OVERRIDE,
                partial = partial,
            )
        } ?: partialTargets(weekdayRule)?.let { partial ->
            buildSummary(
                plan = plan,
                activeOn = activeOn,
                type = schedule.scheduleType,
                source = DailyTargetSource.WEEKDAY_RULE,
                partial = partial,
            )
        }

        return resolved ?: baseGoalSummary(
            plan = plan,
            activeOn = activeOn,
            type = schedule.scheduleType,
        )
    }

    /**
     * The local date from which non-FLAT schedule execution is degraded for
     * this user, or null when the schedule still runs. The stored schedule is
     * never mutated: resubscribing makes this return null again and the exact
     * plan resumes. Historical dates before the lapse keep their real
     * schedule-resolved targets (daily scores backfill stays accurate).
     */
    private fun hasPremiumSchedules(userId: UUID): Boolean {
        val entitlement = cachedEntitlementService.getEntitlement(userId)
        return entitlementGateService.hasFeatureAccess(entitlement, PREMIUM_SCHEDULES_FEATURE)
    }

    private fun shouldPreserveExistingAdvancedSchedule(
        userId: UUID,
        existing: PlanScheduleEntity?,
        requestedScheduleType: GoalScheduleType,
    ): Boolean =
        existing != null &&
            existing.scheduleType != GoalScheduleType.FLAT &&
            requestedScheduleType == GoalScheduleType.FLAT &&
            !hasPremiumSchedules(userId)

    private fun premiumDegradeFrom(
        userId: UUID,
        plan: NutritionPlanEntity,
        schedule: PlanScheduleEntity?,
    ): LocalDate? {
        if (schedule == null || schedule.scheduleType == GoalScheduleType.FLAT) {
            return null
        }

        val entitlement = cachedEntitlementService.getEntitlement(userId)
        if (entitlementGateService.hasFeatureAccess(entitlement, PREMIUM_SCHEDULES_FEATURE)) return null

        val zone = runCatching { ZoneId.of(plan.timezone) }
            .getOrDefault(ZoneId.of("Asia/Tehran"))
        return entitlementLossBoundaryService.resolveDate(
            userId = userId,
            entitlement = entitlement,
            zone = zone,
            persistedFallback = schedule.updatedAt,
        )
    }

    private fun degradedAverageSummary(
        plan: NutritionPlanEntity,
        activeOn: LocalDate,
        schedule: PlanScheduleEntity,
    ): DailyTargetReadModel {
        val averageCalories = degradedAverageCalories(plan, schedule)
        val ratio = if (plan.calories > BigDecimal.ZERO) {
            averageCalories.divide(plan.calories, 6, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ONE
        }

        return DailyTargetReadModel(
            planScheduleSummary = PlanScheduleSummaryReadModel(
                type = schedule.scheduleType,
                date = activeOn,
                source = DailyTargetSource.DEGRADED_AVERAGE,
            ),
            targets = NutritionTargetsReadModel(
                calories = NutritionDecimal.calories(averageCalories),
                protein = NutritionDecimal.macro(plan.protein.multiply(ratio)),
                carbs = NutritionDecimal.macro(plan.carbs.multiply(ratio)),
                fat = NutritionDecimal.macro(plan.fat.multiply(ratio)),
                fiber = plan.fiber,
            ),
        )
    }

    /**
     * Weekly budget / 7 when present; otherwise the true weekly mean where
     * days without a calorie rule count at the base plan's calories.
     */
    private fun degradedAverageCalories(
        plan: NutritionPlanEntity,
        schedule: PlanScheduleEntity,
    ): BigDecimal {
        schedule.weeklyCalorieBudget?.let { budget ->
            return budget.divide(BigDecimal(7), 2, RoundingMode.HALF_UP)
        }

        val weekdayCalories = schedule.weekdayTargets.values.mapNotNull { rule ->
            partialTargets(rule)?.calories
        }
        if (weekdayCalories.isEmpty()) return plan.calories

        val unruledDays = BigDecimal(7 - weekdayCalories.size.coerceAtMost(7))
        return weekdayCalories
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .add(plan.calories.multiply(unruledDays))
            .divide(BigDecimal(7), 2, RoundingMode.HALF_UP)
    }

    fun validateScheduleAccess(
        userId: UUID,
        scheduleType: GoalScheduleType,
    ) {
        if (scheduleType == GoalScheduleType.FLAT) {
            StageLog.info(
                logger = logger,
                event = GOAL_LOG_EVENT,
                stage = "premium_lock_decision",
                outcome = "bypassed",
                fields = mapOf("scheduleType" to scheduleType.name),
            )
            return
        }

        if (!goalScheduleProperties.premiumGoalSchedulesEnabled) {
            StageLog.warn(
                logger = logger,
                event = GOAL_LOG_EVENT,
                stage = "premium_lock_decision",
                outcome = "rejected",
                fields = mapOf(
                    "scheduleType" to scheduleType.name,
                    "errorCode" to ApiErrorCode.FEATURE_DISABLED.name,
                ),
            )
            throw FeatureDisabledException("Premium goal schedules")
        }

        entitlementGateService.requireFeature(
            userId = userId,
            featureKey = PREMIUM_SCHEDULES_FEATURE,
            routeTemplate = GOALS_ROUTE_TEMPLATE,
        )
        logPremiumDecision(scheduleType, "ACTIVE", "allowed")
    }

    private fun logPremiumDecision(
        scheduleType: GoalScheduleType,
        access: String,
        outcome: String,
        errorCode: String? = null,
    ) {
        StageLog.info(
            logger = logger,
            event = GOAL_LOG_EVENT,
            stage = "premium_lock_decision",
            outcome = outcome,
            fields = mapOf(
                "scheduleType" to scheduleType.name,
                "premiumAccess" to access,
                "errorCode" to errorCode,
            ),
        )
    }

    private fun validateScheduleShape(command: SavePlanScheduleCommand) {
        if (command.activeTo != null && command.activeTo.isBefore(command.activeFrom)) {
            throw InvalidGoalScheduleException("activeTo must be on or after activeFrom.")
        }

        if (command.scheduleType == GoalScheduleType.FLAT) {
            val hasNonFlatData = command.weeklyCalorieBudget != null ||
                command.weekdayTargets.isNotEmpty() ||
                command.dateOverrides.isNotEmpty() ||
                command.dietMode != null
            if (hasNonFlatData) {
                throw InvalidGoalScheduleException("FLAT schedules cannot include premium schedule rules.")
            }
        }
    }

    private fun planForUser(
        userId: UUID,
        nutritionPlanId: UUID,
    ): NutritionPlanEntity = nutritionPlanRepository.findById(nutritionPlanId)
        .filter { it.userId == userId }
        .orElseThrow { ResourceNotFoundException("Nutrition plan") }

    private fun expectedAverageCalories(
        plan: NutritionPlanEntity,
        weeklyCalorieBudget: BigDecimal?,
        weekdayTargets: Map<String, Any?>,
    ): BigDecimal {
        weeklyCalorieBudget?.let { return it.divide(BigDecimal(7), 2, RoundingMode.HALF_UP) }
        return java.time.DayOfWeek.entries
            .map { day -> partialTargets(weekdayTargets[day.name])?.calories ?: plan.calories }
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(7), 2, RoundingMode.HALF_UP)
    }

    private fun baseGoalSummary(
        plan: NutritionPlanEntity,
        activeOn: LocalDate,
        type: GoalScheduleType,
    ): DailyTargetReadModel {
        return DailyTargetReadModel(
            planScheduleSummary = PlanScheduleSummaryReadModel(
                type = type,
                date = activeOn,
                source = DailyTargetSource.BASE_PLAN,
            ),
            targets = NutritionTargetsReadModel(
                calories = plan.calories,
                protein = plan.protein,
                carbs = plan.carbs,
                fat = plan.fat,
                fiber = plan.fiber,
            ),
        )
    }

    private fun buildSummary(
        plan: NutritionPlanEntity,
        activeOn: LocalDate,
        type: GoalScheduleType,
        source: DailyTargetSource,
        partial: PartialTargets,
    ): DailyTargetReadModel {
        return DailyTargetReadModel(
            planScheduleSummary = PlanScheduleSummaryReadModel(
                type = type,
                date = activeOn,
                source = source,
                sourceDetail = when (source) {
                    DailyTargetSource.WEEKDAY_RULE -> activeOn.dayOfWeek.name
                    DailyTargetSource.DATE_OVERRIDE -> activeOn.toString()
                    DailyTargetSource.BASE_PLAN,
                    DailyTargetSource.DEGRADED_AVERAGE,
                    -> null
                },
            ),
            targets = NutritionTargetsReadModel(
                calories = partial.calories ?: plan.calories,
                protein = partial.protein ?: plan.protein,
                carbs = partial.carbs ?: plan.carbs,
                fat = partial.fat ?: plan.fat,
                fiber = partial.fiber ?: plan.fiber,
            ),
        )
    }

    private fun PlanScheduleEntity.isActiveOn(activeOn: LocalDate): Boolean {
        if (activeFrom.isAfter(activeOn)) {
            return false
        }
        return activeTo == null || !activeTo!!.isBefore(activeOn)
    }

    private fun partialTargets(rule: Any?): PartialTargets? {
        if (rule !is Map<*, *>) {
            return null
        }

        val partial = PartialTargets(
            calories = rule.decimal("calories")?.let(NutritionDecimal::calories),
            protein = (rule.decimal("protein") ?: rule.decimal("proteinGrams"))?.let(NutritionDecimal::macro),
            carbs = (rule.decimal("carbs") ?: rule.decimal("carbohydrateGrams"))?.let(NutritionDecimal::macro),
            fat = (rule.decimal("fat") ?: rule.decimal("fatGrams"))?.let(NutritionDecimal::macro),
            fiber = (rule.decimal("fiber") ?: rule.decimal("fiberGrams"))?.let(NutritionDecimal::macro),
        )

        return if (partial.isEmpty()) null else partial
    }

    private fun Map<*, *>.decimal(key: String): BigDecimal? {
        val value = this[key] ?: return null
        return when (value) {
            is BigDecimal -> value
            is Int -> BigDecimal.valueOf(value.toLong())
            is Long -> BigDecimal.valueOf(value)
            is Double -> BigDecimal.valueOf(value)
            is Float -> BigDecimal.valueOf(value.toDouble())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }
    }
}

private data class PartialTargets(
    val calories: BigDecimal? = null,
    val protein: BigDecimal? = null,
    val carbs: BigDecimal? = null,
    val fat: BigDecimal? = null,
    val fiber: BigDecimal? = null,
) {
    fun isEmpty(): Boolean {
        return calories == null &&
            protein == null &&
            carbs == null &&
            fat == null &&
            fiber == null
    }
}

private const val GOAL_LOG_EVENT = "goal"
private const val GOALS_ROUTE_TEMPLATE = "/api/v1/goals"
private const val PREMIUM_SCHEDULES_FEATURE = "premium_schedules"
private val logger = LoggerFactory.getLogger(PlanScheduleService::class.java)
