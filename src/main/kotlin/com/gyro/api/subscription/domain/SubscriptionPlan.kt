package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "subscription_plans")
class SubscriptionPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Stable server-owned key. Not an enum — driven by configuration. */
    @Column(nullable = false, unique = true)
    val code: String,

    /** Internal/admin fallback name. Not user-facing. */
    @Column(nullable = false)
    val name: String,

    /** Whether this is a free plan (no billing required). */
    @Column(nullable = false)
    val free: Boolean = true,

    /** Whether this plan is available for new subscriptions. */
    @Column(nullable = false)
    val active: Boolean = true,

    /** Grace period in days after payment failure. 0 = no grace. */
    @Column(name = "grace_period_days", nullable = false)
    val gracePeriodDays: Int = 7,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
