package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_events")
class PaymentEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "payment_attempt_id")
    val paymentAttemptId: UUID? = null,

    @Column(nullable = false)
    val provider: String,

    @Column(name = "event_type", nullable = false)
    val eventType: String,

    @Column(name = "provider_ref_id")
    val providerRefId: String? = null,

    /** Raw provider payload. Never store secrets or card data here. */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    val rawPayload: String? = null,

    /** Sanitized summary for support/debug. */
    @Column(name = "safe_summary", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    val safeSummary: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
