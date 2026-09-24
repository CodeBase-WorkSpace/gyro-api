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
    name = "plan_features",
    uniqueConstraints = [UniqueConstraint(
        columnNames = ["plan_id", "feature_key"],
        name = "uk_plan_feature",
    )],
)
class PlanFeature(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plan_id", nullable = false)
    val planId: Long,

    @Column(name = "feature_key", nullable = false)
    val featureKey: String,

    /** Whether this feature is enabled for this plan. */
    @Column(nullable = false)
    val enabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
