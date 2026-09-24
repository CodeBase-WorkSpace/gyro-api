package com.gyro.api.user.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class AdminUserSummaryResponse(
    val id: String,
    val email: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val role: String,
    val status: String,
    val emailVerificationStatus: String,
    val phoneVerificationStatus: String,
    val subscriptionStatus: String?,
    val promotionRedemptions: Long,
    val createdAt: Instant,
)

data class AdminUserIdentityResponse(
    val id: String,
    val email: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val timezone: String?,
    val locale: String?,
    val role: String,
    val status: String,
    val emailVerificationStatus: String,
    val phoneVerificationStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deactivatedAt: Instant?,
)

data class AdminUserHealthCounts(
    val nutritionPlans: Long,
    val planSchedules: Long,
    val weightEntries: Long,
    val dailyScores: Long,
)

data class AdminUserFoodDiaryCounts(
    val diaryDays: Long,
    val diaryEntries: Long,
    val meals: Long,
    val mealItems: Long,
    val customFoods: Long,
    val foodFavorites: Long,
    val recentFoods: Long,
)

data class AdminUserAuthenticationCounts(
    val activeSessions: Long,
    val totalSessions: Long,
)

data class AdminUserBillingCounts(
    val subscriptions: Long,
    val invoices: Long,
    val paymentAttempts: Long,
    val manualGrants: Long,
    val promotionRedemptions: Long,
    val currentSubscriptionStatus: String?,
    val currentSubscription: AdminUserSubscriptionSummary?,
)

data class AdminUserSubscriptionSummary(
    val planCode: String,
    val planName: String,
    val status: String,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val gracePeriodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
)

data class AdminUserAuditCounts(
    val auditEvents: Long,
    val deletionOperations: Long,
)

data class AdminUserDetailResponse(
    val identity: AdminUserIdentityResponse,
    val hasProfile: Boolean,
    val health: AdminUserHealthCounts,
    val foodAndDiary: AdminUserFoodDiaryCounts,
    val authentication: AdminUserAuthenticationCounts,
    val billing: AdminUserBillingCounts,
    val auditAndOperations: AdminUserAuditCounts,
)

data class AdminDeletionPlanEntry(
    val domain: String,
    val action: String,
    val records: Long,
)

data class AdminUserDeletionPreviewResponse(
    val operationId: String,
    val confirmationToken: String,
    val expiresAt: Instant,
    val target: AdminUserIdentityResponse,
    val confirmationValue: String,
    val deletePlan: List<AdminDeletionPlanEntry>,
    val retainPlan: List<AdminDeletionPlanEntry>,
)

data class AdminUserDeletionConfirmRequest(
    @field:NotBlank
    val operationId: String,

    @field:NotBlank
    @field:Size(max = 128)
    val confirmationToken: String,

    @field:NotBlank
    @field:Size(max = 320)
    val confirmation: String,

    @field:NotBlank
    @field:Size(min = 5, max = 500)
    val reason: String,
)

data class AdminUserDeletionResultResponse(
    val operationId: String,
    val targetUserId: String,
    val status: String,
    val deletedCounts: Map<String, Long>,
    val retainedCounts: Map<String, Long>,
    val completedAt: Instant?,
)

data class AdminUserDeletionOperationRecord(
    val id: UUID,
    val targetUserId: UUID,
    val actingAdminId: UUID,
    val status: String,
    val previewTokenHash: String,
    val previewExpiresAt: Instant,
)
