package com.gyro.api.goal.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class RecalibrationSuggestionStatus {
    PENDING,
    ACCEPTED,
    DISMISSED,
    EXPIRED,
    SUPERSEDED,
}

enum class RecalibrationDismissReason {
    TOO_AGGRESSIVE,
    DOESNT_FEEL_RIGHT,
    DATA_IS_WRONG,
    NOT_NOW,
}

@Entity
@Table(name = "plan_recalibration_suggestions")
class PlanRecalibrationSuggestion(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "nutrition_plan_id", nullable = false)
    val nutritionPlanId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RecalibrationSuggestionStatus = RecalibrationSuggestionStatus.PENDING,

    @Column(name = "suggested_calories", nullable = false)
    val suggestedCalories: BigDecimal,

    @Column(name = "suggested_protein", nullable = false)
    val suggestedProtein: BigDecimal,

    @Column(name = "suggested_carbs", nullable = false)
    val suggestedCarbs: BigDecimal,

    @Column(name = "suggested_fat", nullable = false)
    val suggestedFat: BigDecimal,

    @Column(name = "previous_calories", nullable = false)
    val previousCalories: BigDecimal,

    @Column(name = "previous_protein", nullable = false)
    val previousProtein: BigDecimal,

    @Column(name = "previous_carbs", nullable = false)
    val previousCarbs: BigDecimal,

    @Column(name = "previous_fat", nullable = false)
    val previousFat: BigDecimal,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val basis: Map<String, Any?> = emptyMap(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "dismiss_reason")
    var dismissReason: RecalibrationDismissReason? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
)
