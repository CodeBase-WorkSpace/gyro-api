package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "promotions")
class Promotion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true, length = 64)
    val code: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val type: PromotionType,

    @Column(nullable = false, precision = 12, scale = 2)
    var value: BigDecimal,

    @Column(name = "applicable_plan_id")
    var applicablePlanId: Long? = null,

    @Column(name = "applicable_subscription_price_id")
    var applicableSubscriptionPriceId: Long? = null,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    @Column(name = "ends_at")
    var endsAt: Instant? = null,

    @Column(name = "max_redemptions")
    var maxRedemptions: Int? = null,

    @Column(name = "per_user_redemption_limit", nullable = false)
    var perUserRedemptionLimit: Int = 1,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "internal_notes")
    var internalNotes: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    val version: Long = 0,
)
