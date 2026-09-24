package com.gyro.api.goal.domain

import com.gyro.api.weight.domain.WeightUnit
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

enum class DailyEnergyDeltaSource {
    FORMULA_WIZARD,
    OBSERVED_WIZARD,
    RECALIBRATION_AUDIT,
}

enum class CalculatorMaintenanceSource {
    FORMULA,
    OBSERVED,
}

@Entity
@Table(name = "nutrition_plans")
class NutritionPlanEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(nullable = false)
    var timezone: String,

    @Column(nullable = false, precision = 10, scale = 2)
    var calories: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 3)
    var protein: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 3)
    var carbs: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 3)
    var fat: BigDecimal,

    @Column(precision = 10, scale = 3)
    var fiber: BigDecimal? = null,

    @Column(name = "target_weight", precision = 10, scale = 3)
    var targetWeight: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_weight_unit")
    var targetWeightUnit: WeightUnit? = null,

    @Column(name = "target_date")
    var targetDate: LocalDate? = null,

    @Column(name = "calculator_formula")
    var calculatorFormula: String? = null,

    @Column(name = "calculator_formula_version")
    var calculatorFormulaVersion: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculator_sex")
    var calculatorSex: GoalCalculatorSex? = null,

    @Column(name = "calculator_birth_date")
    var calculatorBirthDate: LocalDate? = null,

    @Column(name = "calculator_height_cm", precision = 6, scale = 2)
    var calculatorHeightCm: BigDecimal? = null,

    @Column(name = "calculator_current_weight_kg", precision = 6, scale = 3)
    var calculatorCurrentWeightKg: BigDecimal? = null,

    @Column(name = "calculator_target_weight_kg", precision = 6, scale = 3)
    var calculatorTargetWeightKg: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculator_daily_movement_level")
    var calculatorDailyMovementLevel: DailyMovementLevel? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculator_workout_frequency")
    var calculatorWorkoutFrequency: WorkoutFrequency? = null,

    @Column(name = "calculator_change_speed")
    var calculatorChangeSpeed: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculator_goal_type")
    var calculatorGoalType: GoalType? = null,

    @Column(name = "maintenance_calories", precision = 10, scale = 2)
    var maintenanceCalories: BigDecimal? = null,

    @Column(name = "target_calories", precision = 10, scale = 2)
    var targetCalories: BigDecimal? = null,

    @Column(name = "activity_factor", precision = 6, scale = 3)
    var activityFactor: BigDecimal? = null,

    @Column(name = "daily_energy_delta", precision = 10, scale = 2)
    var dailyEnergyDelta: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "daily_energy_delta_source")
    var dailyEnergyDeltaSource: DailyEnergyDeltaSource? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculator_maintenance_source")
    var calculatorMaintenanceSource: CalculatorMaintenanceSource? = null,

    @Column(name = "formula_maintenance_calories", precision = 10, scale = 2)
    var formulaMaintenanceCalories: BigDecimal? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculator_observation_basis", columnDefinition = "jsonb")
    var calculatorObservationBasis: Map<String, Any?>? = null,

    @Column(name = "weekly_weight_change_kg", precision = 8, scale = 3)
    var weeklyWeightChangeKg: BigDecimal? = null,

    @Column(name = "estimated_weeks_min")
    var estimatedWeeksMin: Int? = null,

    @Column(name = "estimated_weeks_max")
    var estimatedWeeksMax: Int? = null,

    @Column(name = "estimated_target_date")
    var estimatedTargetDate: LocalDate? = null,

    @Column(name = "recommended_protein", precision = 10, scale = 3)
    var recommendedProtein: BigDecimal? = null,

    @Column(name = "recommended_carbs", precision = 10, scale = 3)
    var recommendedCarbs: BigDecimal? = null,

    @Column(name = "recommended_fat", precision = 10, scale = 3)
    var recommendedFat: BigDecimal? = null,

    @Column(name = "recommended_fiber", precision = 10, scale = 3)
    var recommendedFiber: BigDecimal? = null,

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "safety_warning_codes", columnDefinition = "text[]")
    var safetyWarningCodes: Array<String>? = null,

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
