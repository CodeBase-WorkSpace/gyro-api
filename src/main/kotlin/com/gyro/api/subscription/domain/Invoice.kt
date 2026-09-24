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
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "invoices")
class Invoice(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "plan_id", nullable = false)
    val planId: Long,

    /** The billing period this invoice covers. */
    @Column(name = "period_start", nullable = false)
    val periodStart: Instant,

    @Column(name = "period_end", nullable = false)
    val periodEnd: Instant,

    /** Amount originally owed (plan price). */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "amount_due", nullable = false, precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "currency", nullable = false, length = 3)),
    )
    val amountDue: Money,

    /** Amount after promotions. What the user actually pays. */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "amount_after_discount", nullable = false, precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "discount_currency", nullable = false, length = 3)),
    )
    val amountAfterDiscount: Money,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InvoiceStatus = InvoiceStatus.OPEN,

    /** Promotion code applied at invoice creation. Null if none. */
    val promotionCode: String? = null,

    /** Whether this was created by a manual grant (admin/offline). */
    @Column(nullable = false)
    val manual: Boolean = false,

    /** FK to subscription_prices. Used to snapshot the locked price on subscription creation. */
    @Column(name = "subscription_price_id")
    val subscriptionPriceId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
