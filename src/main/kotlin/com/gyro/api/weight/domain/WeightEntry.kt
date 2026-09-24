package com.gyro.api.weight.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class WeightUnit {
    KG,
    LB,
}

fun WeightUnit.toKilograms(weight: BigDecimal): BigDecimal = when (this) {
    WeightUnit.KG -> weight
    WeightUnit.LB -> weight.divide(POUNDS_PER_KILOGRAM, 3, RoundingMode.HALF_UP)
}

private val POUNDS_PER_KILOGRAM = BigDecimal("2.2046226218")

enum class WeightEntrySource {
    MANUAL,
    IMPORT,
}

@Entity
@Table(name = "weight_entries")
class WeightEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "recorded_date", nullable = false)
    var recordedDate: LocalDate,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant,

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 3)
    var weightKg: BigDecimal,

    @Column(name = "display_weight", nullable = false, precision = 10, scale = 3)
    var displayWeight: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "display_unit", nullable = false)
    var displayUnit: WeightUnit,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: WeightEntrySource,

    @Column(name = "notes")
    var notes: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
