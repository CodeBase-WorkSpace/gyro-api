package com.gyro.api.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class GyroUser(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(unique = true)
    var email: String? = null,

    @Column(name = "phone_number", unique = true)
    var phoneNumber: String? = null,

    @Column(name = "password_hash", nullable = true)
    var passwordHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: UserRole = UserRole.USER,

    @Enumerated(EnumType.STRING)
    @Column(name = "email_verification_status", nullable = false)
    var emailVerificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,

    @Enumerated(EnumType.STRING)
    @Column(name = "phone_verification_status", nullable = false)
    var phoneVerificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: UserStatus = UserStatus.ACTIVE,

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null,

    @Column(name = "onboarding_welcome_seen_at")
    var onboardingWelcomeSeenAt: Instant? = null,

    @Column(name = "calculator_rerun_prompt_acknowledged_at")
    var calculatorRerunPromptAcknowledgedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val hasPassword: Boolean
        get() = passwordHash != null

    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}
