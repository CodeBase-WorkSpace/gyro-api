package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "subscription_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_subscription_event_natural_key",
            columnNames = ["source_type", "source_id", "transition_type"],
        ),
    ],
)
class SubscriptionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_type", nullable = false)
    val transitionType: SubscriptionTransitionType,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    val sourceType: EventSourceType,

    @Column(name = "source_id", nullable = false)
    val sourceId: String,

    /** Unique constraint: sourceType + sourceId + transitionType */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,

    // Before snapshot
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before")
    val statusBefore: SubscriptionStatus? = null,

    @Column(name = "plan_id_before")
    val planIdBefore: Long? = null,

    @Column(name = "period_start_before")
    val periodStartBefore: Instant? = null,

    @Column(name = "period_end_before")
    val periodEndBefore: Instant? = null,

    @Column(name = "cancel_at_period_end_before")
    val cancelAtPeriodEndBefore: Boolean? = null,

    @Column(name = "grace_period_end_before")
    val gracePeriodEndBefore: Instant? = null,

    @Column(name = "grace_reason_before")
    val graceReasonBefore: String? = null,

    // After snapshot
    @Enumerated(EnumType.STRING)
    @Column(name = "status_after")
    val statusAfter: SubscriptionStatus? = null,

    @Column(name = "plan_id_after")
    val planIdAfter: Long? = null,

    @Column(name = "period_start_after")
    val periodStartAfter: Instant? = null,

    @Column(name = "period_end_after")
    val periodEndAfter: Instant? = null,

    @Column(name = "cancel_at_period_end_after")
    val cancelAtPeriodEndAfter: Boolean? = null,

    @Column(name = "grace_period_end_after")
    val gracePeriodEndAfter: Instant? = null,

    @Column(name = "grace_reason_after")
    val graceReasonAfter: String? = null,

    // Actor and reason
    @Column(name = "actor_id")
    val actorId: UUID? = null,

    @Column(name = "reason")
    val reason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
