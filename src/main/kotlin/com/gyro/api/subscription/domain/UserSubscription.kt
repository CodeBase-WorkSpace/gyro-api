package com.gyro.api.subscription.domain

import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "user_subscriptions",
    uniqueConstraints = [UniqueConstraint(
        columnNames = ["user_id"],
        name = "uk_user_subscription",
    )],
)
class UserSubscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    /** FK to subscription_plans. Resolve in service layer, not JPA relationship. */
    @Column(name = "plan_id", nullable = false)
    var planId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE,

    @Column(name = "period_start", nullable = false)
    var periodStart: Instant,

    /** Null for lifetime/manual grants. */
    @Column(name = "period_end")
    var periodEnd: Instant? = null,

    /** True if user or admin requested cancellation. Access continues until periodEnd. */
    @Column(name = "cancel_at_period_end", nullable = false)
    var cancelAtPeriodEnd: Boolean = false,

    /** Null unless status = GRACE_PERIOD. */
    @Column(name = "grace_period_end")
    var gracePeriodEnd: Instant? = null,

    /** Reason for grace period entry. */
    @Column(name = "grace_reason")
    var graceReason: String? = null,

    /** FK to subscription_prices. Locks the renewal price at subscription creation time. */
    @Column(name = "locked_price_id")
    var lockedPriceId: Long? = null,

    /** Snapshot of the locked price amount and currency at subscription creation time. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "locked_price_amount", precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "locked_price_currency", length = 3)),
    )
    var lockedPrice: Money? = null,

    @Version
    val version: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun update(
        planId: Long = this.planId,
        status: SubscriptionStatus = this.status,
        periodStart: Instant = this.periodStart,
        periodEnd: Instant? = this.periodEnd,
        cancelAtPeriodEnd: Boolean = this.cancelAtPeriodEnd,
        gracePeriodEnd: Instant? = this.gracePeriodEnd,
        graceReason: String? = this.graceReason,
        lockedPriceId: Long? = this.lockedPriceId,
        lockedPrice: Money? = this.lockedPrice,
        updatedAt: Instant = this.updatedAt,
    ): UserSubscription = apply {
        this.planId = planId
        this.status = status
        this.periodStart = periodStart
        this.periodEnd = periodEnd
        this.cancelAtPeriodEnd = cancelAtPeriodEnd
        this.gracePeriodEnd = gracePeriodEnd
        this.graceReason = graceReason
        this.lockedPriceId = lockedPriceId
        this.lockedPrice = lockedPrice
        this.updatedAt = updatedAt
    }
}
