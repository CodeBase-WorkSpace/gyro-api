package com.gyro.api.goal.infrastructure

import com.gyro.api.goal.domain.PlanScheduleEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface PlanScheduleRepository : JpaRepository<PlanScheduleEntity, UUID> {
    fun findByNutritionPlanIdAndUserId(
        nutritionPlanId: UUID,
        userId: UUID,
    ): PlanScheduleEntity?

    fun findFirstByUserIdAndActiveFromLessThanEqualAndActiveToIsNullOrderByActiveFromDesc(
        userId: UUID,
        activeOn: LocalDate,
    ): PlanScheduleEntity?

    fun findFirstByUserIdAndActiveFromLessThanEqualAndActiveToGreaterThanEqualOrderByActiveFromDesc(
        userId: UUID,
        activeOn: LocalDate,
        activeOnForEnd: LocalDate,
    ): PlanScheduleEntity?
}
