package com.gyro.api.goal.application.goal_schedule

import com.gyro.api.goal.application.nutrition_plan.DailyTargetReadModel
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

data class ScheduleAwareDailyTarget(
    val planId: UUID,
    val goalType: com.gyro.api.goal.domain.GoalType?,
    val target: DailyTargetReadModel,
)

/** Resolves historical dates against the plan and schedule active on each date. */
@Service
class ScheduleAwareDailyTargetLoader(
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val planScheduleService: PlanScheduleService,
) {
    @Transactional(readOnly = true)
    fun load(userId: UUID, dates: List<LocalDate>): Map<LocalDate, ScheduleAwareDailyTarget> {
        val latestDate = dates.maxOrNull() ?: return emptyMap()
        val plans = nutritionPlanRepository.findByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, latestDate)
        if (plans.isEmpty()) return emptyMap()

        return dates.mapNotNull { date ->
            plans.firstOrNull { !it.startDate.isAfter(date) }?.let { date to it }
        }.groupBy(
            keySelector = { (_, plan) -> requireNotNull(plan.id) },
            valueTransform = { (date, plan) -> date to plan },
        ).values.flatMap { datePlans ->
            val plan = datePlans.first().second
            planScheduleService.resolveDailyTargets(
                userId = userId,
                plan = plan,
                activeDates = datePlans.map { it.first },
            ).map { (date, target) ->
                date to ScheduleAwareDailyTarget(requireNotNull(plan.id), plan.calculatorGoalType, target)
            }
        }.toMap()
    }
}
