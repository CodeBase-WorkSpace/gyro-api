package com.gyro.api.subscription.web

import com.gyro.api.subscription.domain.ManualGrantReason
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.*

data class CreateManualGrantRequest(
    @field:NotNull(message = "userId is required.")
    val userId: UUID? = null,

    @field:NotNull(message = "planId is required.")
    val planId: Long? = null,

    @field:NotNull(message = "durationDays is required.")
    @field:Positive(message = "durationDays must be greater than zero.")
    val durationDays: Int? = null,

    @field:NotNull(message = "reason is required.")
    val reason: ManualGrantReason? = null,

    @field:NotBlank(message = "reasonNote is required.")
    val reasonNote: String? = null,
    val promotionCode: String? = null,
)

data class ExtendManualGrantRequest(
    @field:NotNull(message = "additionalDays is required.")
    @field:Positive(message = "additionalDays must be greater than zero.")
    val additionalDays: Int? = null,

    @field:NotBlank(message = "reason is required.")
    val reason: String? = null,
)

data class RevokeManualGrantRequest(
    @field:NotBlank(message = "reason is required.")
    val reason: String? = null,
)

data class ManualGrantResponse(
    val id: UUID,
    val userId: UUID,
    val planId: Long,
    val durationDays: Int?,
    val periodStart: Instant?,
    val expiresAt: Instant?,
    val reason: ManualGrantReason,
    val reasonNote: String?,
    val grantedBy: UUID,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
    val revokeReason: String?,
    val createdAt: Instant,
    val promotionCode: String? = null,
)

data class AdminGrantPlanResponse(
    val id: Long,
    val code: String,
    val name: String,
)

data class RecalculateEntitlementRequest(
    @field:NotBlank(message = "reason is required.")
    val reason: String? = null,
)
