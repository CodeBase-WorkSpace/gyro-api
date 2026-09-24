package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "plan_localizations",
    uniqueConstraints = [UniqueConstraint(
        columnNames = ["plan_id", "locale"],
        name = "uk_plan_locale",
    )],
)
class PlanLocalization(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plan_id", nullable = false)
    val planId: Long,

    @Column(nullable = false, length = 10)
    val locale: String,

    @Column(name = "display_name", nullable = false)
    val displayName: String,

    @Column(name = "short_description")
    val shortDescription: String? = null,

    @Column(name = "feature_summary")
    val featureSummary: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
