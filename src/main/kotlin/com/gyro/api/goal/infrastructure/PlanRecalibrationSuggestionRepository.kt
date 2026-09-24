package com.gyro.api.goal.infrastructure

import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PlanRecalibrationSuggestionRepository : JpaRepository<PlanRecalibrationSuggestion, UUID> {
    fun findFirstByUserIdAndStatus(
        userId: UUID,
        status: RecalibrationSuggestionStatus,
    ): PlanRecalibrationSuggestion?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PlanRecalibrationSuggestion s where s.id = :id and s.userId = :userId")
    fun findByIdAndUserIdForUpdate(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID,
    ): PlanRecalibrationSuggestion?

    fun findFirstByUserIdOrderByCreatedAtDesc(userId: UUID): PlanRecalibrationSuggestion?

    fun findFirstByUserIdAndNutritionPlanIdAndStatusOrderByDecidedAtDesc(
        userId: UUID,
        nutritionPlanId: UUID,
        status: RecalibrationSuggestionStatus,
    ): PlanRecalibrationSuggestion?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select s from PlanRecalibrationSuggestion s
        where s.status = :status and s.expiresAt <= :expiresAt
        """,
    )
    fun findAllExpiredForUpdate(
        @Param("status") status: RecalibrationSuggestionStatus,
        @Param("expiresAt") expiresAt: Instant,
    ): List<PlanRecalibrationSuggestion>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select s from PlanRecalibrationSuggestion s
        where s.userId = :userId and s.status = :status and s.expiresAt <= :expiresAt
        """,
    )
    fun findAllExpiredForUserForUpdate(
        @Param("userId") userId: UUID,
        @Param("status") status: RecalibrationSuggestionStatus,
        @Param("expiresAt") expiresAt: Instant,
    ): List<PlanRecalibrationSuggestion>
}
