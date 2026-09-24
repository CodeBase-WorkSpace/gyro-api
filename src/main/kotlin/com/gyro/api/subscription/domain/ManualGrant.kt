package com.gyro.api.subscription.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "manual_grants")
class ManualGrant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "plan_id", nullable = false)
    val planId: Long,

    /** Duration of the grant in days. */
    @Column(name = "duration_days")
    var durationDays: Int? = null,

    /** Effective period start for this grant after lifecycle stacking/recompute. */
    @Column(name = "period_start")
    var periodStart: Instant? = null,

    /** Computed effective expiry. */
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val reason: ManualGrantReason,

    @Column(name = "reason_note")
    val reasonNote: String? = null,

    @Column(name = "granted_by", nullable = false)
    val grantedBy: UUID,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "revoked_by")
    var revokedBy: UUID? = null,

    @Column(name = "revoke_reason")
    var revokeReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun update(
        durationDays: Int? = this.durationDays,
        periodStart: Instant? = this.periodStart,
        expiresAt: Instant? = this.expiresAt,
        revokedAt: Instant? = this.revokedAt,
        revokedBy: UUID? = this.revokedBy,
        revokeReason: String? = this.revokeReason,
    ): ManualGrant = apply {
        this.durationDays = durationDays
        this.periodStart = periodStart
        this.expiresAt = expiresAt
        this.revokedAt = revokedAt
        this.revokedBy = revokedBy
        this.revokeReason = revokeReason
    }
}
