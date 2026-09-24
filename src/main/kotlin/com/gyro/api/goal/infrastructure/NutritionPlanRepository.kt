package com.gyro.api.goal.infrastructure

import com.gyro.api.goal.domain.NutritionPlanEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.*

interface NutritionPlanRepository : JpaRepository<NutritionPlanEntity, UUID> {
    fun existsByIdAndUserId(id: UUID, userId: UUID): Boolean

    fun findByUserIdAndStartDate(
        userId: UUID,
        startDate: LocalDate,
    ): NutritionPlanEntity?

    fun findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(
        userId: UUID,
        startDate: LocalDate,
    ): NutritionPlanEntity?

    fun findByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(
        userId: UUID,
        startDate: LocalDate,
    ): List<NutritionPlanEntity>

    @Modifying
    @Query("delete from NutritionPlanEntity plan where plan.userId = :userId")
    fun deleteAllByUserId(userId: UUID): Int
}
