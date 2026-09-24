package com.gyro.api.user.domain

import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "user_profiles")
class UserProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    var user: GyroUser,

    @Column(name = "display_name")
    var displayName: String? = null,

    @Column(nullable = false)
    var timezone: String,

    @Column(nullable = false)
    var locale: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "sex")
    var sex: GoalCalculatorSex? = null,

    @Column(name = "birth_date")
    var birthDate: LocalDate? = null,

    @Column(name = "height_cm", precision = 6, scale = 2)
    var heightCm: BigDecimal? = null,

    @Column(name = "current_weight_kg", precision = 6, scale = 3)
    var currentWeightKg: BigDecimal? = null,

    @Column(name = "target_weight_kg", precision = 6, scale = 3)
    var targetWeightKg: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "daily_movement_level")
    var dailyMovementLevel: DailyMovementLevel? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "workout_frequency")
    var workoutFrequency: WorkoutFrequency? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type")
    var goalType: GoalType? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }

    @PrePersist
    fun markPrePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }
}
