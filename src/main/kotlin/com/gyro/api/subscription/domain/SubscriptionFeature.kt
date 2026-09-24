package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "subscription_features")
class SubscriptionFeature(
    @Id
    @Column(nullable = false, unique = true)
    val key: String,

    @Column(nullable = false)
    val description: String,

    /** Whether this feature exists in the system. */
    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
