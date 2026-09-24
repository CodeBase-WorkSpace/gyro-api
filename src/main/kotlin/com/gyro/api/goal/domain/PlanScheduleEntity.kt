package com.gyro.api.goal.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "plan_schedules")
class PlanScheduleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "nutrition_plan_id", nullable = false)
    var nutritionPlanId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false)
    var scheduleType: GoalScheduleType,

    @Column(name = "active_from", nullable = false)
    var activeFrom: LocalDate,

    @Column(name = "active_to")
    var activeTo: LocalDate? = null,

    @Column(name = "weekly_calorie_budget", precision = 12, scale = 2)
    var weeklyCalorieBudget: BigDecimal? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weekday_targets", nullable = false, columnDefinition = "jsonb")
    var weekdayTargets: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "date_overrides", nullable = false, columnDefinition = "jsonb")
    var dateOverrides: Map<String, Any?> = emptyMap(),

    @Enumerated(EnumType.STRING)
    @Column(name = "macro_adjustment_mode", nullable = false)
    var macroAdjustmentMode: MacroTargetAdjustmentMode,

    @Column(name = "diet_mode", columnDefinition = "text")
    var dietMode: String? = null,

    @Column(name = "formula_name")
    var formulaName: String? = null,

    @Column(name = "formula_version")
    var formulaVersion: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule_snapshot", nullable = false, columnDefinition = "jsonb")
    var scheduleSnapshot: Map<String, Any?> = emptyMap(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PrePersist
    fun markPrePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}
