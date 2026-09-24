package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class TrialRedemptionSource {
    SIGNUP,
    EXISTING_USER,
}

/**
 * One row per person who has ever received the self-serve ADVANCED trial.
 * The identifier is stored only as a SHA-256 hash of the normalized contact
 * (lowercased email or E.164 phone) so the anti-re-farming record survives
 * account deletion without retaining identifying attributes.
 */
@Entity
@Table(name = "trial_redemptions")
class TrialRedemption(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "identifier_hash", nullable = false)
    val identifierHash: String,

    @Column(name = "manual_grant_id", nullable = false)
    val manualGrantId: UUID,

    @Column(name = "granted_at", nullable = false)
    val grantedAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "notice_sent_at")
    var noticeSentAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: TrialRedemptionSource,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
