package com.gyro.api.subscription.domain

import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "subscription_prices")
class SubscriptionPrice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plan_id", nullable = false)
    val planId: Long,

    /** Billing period. 0 for free/lifetime plans. */
    @Column(name = "billing_period_days", nullable = false)
    val billingPeriodDays: Int,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "amount", nullable = false, precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "currency", nullable = false, length = 3)),
    )
    val price: Money,

    /** Normal/list amount before a permanent catalog discount. */
    @Column(name = "base_amount", nullable = false, precision = 12, scale = 2)
    val baseAmount: BigDecimal = price.amount,

    /** Permanent catalog discount. Promotion codes are applied separately at checkout. */
    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    val discountPercent: BigDecimal = BigDecimal.ZERO,

    /** Display badge: RECOMMENDED, BEST_VALUE, or null. */
    val badge: String? = null,

    /** Whether this price is available for new invoices. */
    @Column(nullable = false)
    var active: Boolean = true,

    /** When this price became valid. */
    @Column(name = "valid_from", nullable = false)
    val validFrom: Instant = Instant.now(),

    /** When this price was deactivated. Null if still active. */
    @Column(name = "valid_until")
    var validUntil: Instant? = null,

    @Column(name = "created_by")
    val createdBy: UUID? = null,

    @Column(name = "deactivated_by")
    var deactivatedBy: UUID? = null,

    @Column(name = "deactivation_reason")
    var deactivationReason: String? = null,

    @Column(name = "internal_notes")
    val internalNotes: String? = null,

    @Column(nullable = false)
    var scheduled: Boolean = false,

    /** Active price approved as this schedule's predecessor. Rechecked at activation time. */
    @Column(name = "expected_predecessor_id")
    val expectedPredecessorId: Long? = null,

    @Column(name = "activation_conflicted_at")
    var activationConflictedAt: Instant? = null,

    @Column(name = "activation_conflict_reason")
    var activationConflictReason: String? = null,

    @Version
    val version: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
