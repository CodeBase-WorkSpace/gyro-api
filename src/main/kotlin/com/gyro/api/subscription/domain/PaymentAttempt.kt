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
@Table(name = "payment_attempts")
class PaymentAttempt(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** FK to invoices. One invoice can have multiple attempts. */
    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: PaymentProvider,

    /** Correlation key sent to/received from provider. */
    @Column(name = "client_ref_id", nullable = false, unique = true)
    val clientRefId: String,

    /** Provider's internal payment code. Used in gateway URL. */
    @Column(name = "provider_code")
    var providerCode: String? = null,

    /** Provider's reference ID from verification. */
    @Column(name = "provider_ref_id")
    var providerRefId: String? = null,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "amount", nullable = false, precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "currency", nullable = false, length = 3)),
    )
    val amount: Money,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentAttemptStatus = PaymentAttemptStatus.PENDING,

    /** Whether this payment is reversible/refundable. */
    @Column(nullable = false)
    var reversible: Boolean = false,

    @Column(name = "provider_request_id")
    var providerRequestId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
