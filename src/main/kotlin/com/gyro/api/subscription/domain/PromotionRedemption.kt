package com.gyro.api.subscription.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "promotion_redemptions")
class PromotionRedemption(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "promotion_id", nullable = false)
    val promotionId: Long,

    @Column(name = "subscription_id")
    var subscriptionId: Long? = null,

    @Column(name = "payment_attempt_id")
    var paymentAttemptId: UUID? = null,

    @Column(name = "invoice_id")
    var invoiceId: UUID? = null,

    @Column(name = "manual_grant_id")
    var manualGrantId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var provider: PaymentProvider? = null,

    @Column(name = "redeemed_at")
    var redeemedAt: Instant? = null,

    @Column(name = "reserved_at", nullable = false)
    val reservedAt: Instant,

    @Column(name = "reservation_expires_at", nullable = false)
    val reservationExpiresAt: Instant,

    @Column(name = "released_at")
    var releasedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: PromotionRedemptionStatus = PromotionRedemptionStatus.RESERVED,

    @Column(name = "idempotency_key", length = 255)
    val idempotencyKey: String? = null,

    @Column(name = "safe_audit_reference", nullable = false, length = 128)
    val safeAuditReference: String,

    @Column(name = "discount_percentage_snapshot", precision = 5, scale = 2, updatable = false)
    val discountPercentageSnapshot: java.math.BigDecimal? = null,

    @Column(name = "commission_percentage_snapshot", precision = 5, scale = 2, updatable = false)
    val commissionPercentageSnapshot: java.math.BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
