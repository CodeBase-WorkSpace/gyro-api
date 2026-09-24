package com.gyro.api.goal.application.nutrition_plan

import com.gyro.api.common.date.DateAccessPolicy
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.goal.application.goal_schedule.PlanScheduleService
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import com.gyro.api.weight.domain.WeightUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NutritionPlanServiceTargetWeightTest {
    private val userId = UUID.randomUUID()
    private val startDate = LocalDate.parse("2026-08-02")
    private val repository = Mockito.mock(NutritionPlanRepository::class.java)
    private val scheduleService = Mockito.mock(PlanScheduleService::class.java)
    private val profileRepository = Mockito.mock(UserProfileRepository::class.java)
    private val preferences = Mockito.mock(UserPreferencesProperties::class.java)
    private val dateAccessPolicy = Mockito.mock(DateAccessPolicy::class.java)
    private val dashboardPublisher = Mockito.mock(UserDashboardDataChangedPublisher::class.java)

    @Test
    fun `kilogram target reaches observed validation unchanged`() {
        assertEquals(
            BigDecimal("75.000"),
            capturedValidatorTarget(command(BigDecimal("75.000"), WeightUnit.KG)),
        )
    }

    @Test
    fun `equivalent pounds target reaches observed validation in kilograms`() {
        assertEquals(
            BigDecimal("74.843"),
            capturedValidatorTarget(command(BigDecimal("165.000"), WeightUnit.LB)),
        )
    }

    @Test
    fun `missing target reaches observed validation as null`() {
        assertNull(capturedValidatorTarget(command(null, null)))
    }

    private fun capturedValidatorTarget(command: SaveNutritionPlanCommand): BigDecimal? {
        var captured: BigDecimal? = null
        Mockito.`when`(preferences.normalizedDefaultTimezone).thenReturn("Asia/Tehran")
        val observedValidator = Mockito.mock(ObservedCalculatorSnapshotValidator::class.java) { invocation ->
            if (invocation.method.name == "validate") {
                captured = invocation.getArgument(3)
                throw StopAfterValidation()
            }
            Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        val service = NutritionPlanService(
            nutritionPlanRepository = repository,
            planScheduleService = scheduleService,
            userProfileRepository = profileRepository,
            userPreferencesProperties = preferences,
            dateAccessPolicy = dateAccessPolicy,
            dashboardDataChangedPublisher = dashboardPublisher,
            observedCalculatorSnapshotValidator = observedValidator,
        )

        assertThrows(StopAfterValidation::class.java) {
            service.savePlan(userId, command)
        }
        return captured
    }

    private fun command(
        targetWeight: BigDecimal?,
        targetWeightUnit: WeightUnit?,
    ) = SaveNutritionPlanCommand(
        startDate = startDate,
        goalType = GoalType.LOSE_WEIGHT,
        calories = BigDecimal("2200"),
        protein = BigDecimal("140"),
        carbs = BigDecimal("220"),
        fat = BigDecimal("70"),
        targetWeight = targetWeight,
        targetWeightUnit = targetWeightUnit,
    )

    private class StopAfterValidation : RuntimeException()
}
