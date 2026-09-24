package com.gyro.api.user.web

import java.time.Instant
import java.util.UUID

data class MeResponse(
    val id: UUID,
    val email: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val timezone: String,
    val locale: String,
    val role: String,
    val status: String,
    val emailVerificationStatus: String,
    val phoneVerificationStatus: String,
    val hasPassword: Boolean,
    val onboardingWelcomeSeenAt: Instant?,
    val calculatorRerunPromptAcknowledgedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
