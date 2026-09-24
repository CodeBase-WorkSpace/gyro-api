package com.gyro.api.goal.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanService
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.util.UUID

@Service
class GoalQueryService(
    private val nutritionPlanService: NutritionPlanService,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val timeProvider: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun getCurrentGoal(userId: UUID): CurrentGoalAggregateView {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        val today = timeProvider.today(ZoneId.of(timezone))
        val activePlan = nutritionPlanService.findActivePlan(
            userId = userId,
            activeOn = today,
        ) ?: return unconfiguredGoalView()

        return activePlan.toCurrentGoalAggregateView()
    }
}
