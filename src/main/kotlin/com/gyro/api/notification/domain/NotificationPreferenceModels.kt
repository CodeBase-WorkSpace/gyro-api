package com.gyro.api.notification.domain

import java.time.Instant
import java.time.LocalTime
import java.util.UUID

enum class NotificationEndpointSource { ACCOUNT_EMAIL, ACCOUNT_PHONE }
enum class NotificationEndpointHealthState { HEALTHY, INVALID }
enum class NotificationEndpointInvalidReason { MAILBOX_INVALID, DOMAIN_INVALID, DESTINATION_POLICY_REJECTED }

data class NotificationPreferencesView(
    val timezone: String,
    val quietHoursStart: LocalTime,
    val quietHoursEnd: LocalTime,
    val categories: List<NotificationCategoryPreferenceView>,
    val emailEligible: Boolean,
    val smsEligible: Boolean,
)

data class NotificationCategoryPreferenceView(
    val category: NotificationCategory,
    val enabled: Boolean,
    val mutable: Boolean,
)

data class UpdateNotificationPreferencesCommand(
    val quietHoursStart: LocalTime,
    val quietHoursEnd: LocalTime,
    val categories: Map<NotificationCategory, Boolean>,
)

data class NotificationEndpointCandidate(
    val userId: UUID,
    val channel: NotificationChannel,
    val source: NotificationEndpointSource,
    val value: String,
)

data class NotificationEndpointHealthSnapshot(
    val state: NotificationEndpointHealthState,
    val invalidReason: NotificationEndpointInvalidReason?,
    val lastFailureAt: Instant?,
)

data class NotificationDeliveryEligibility(
    val enabled: Boolean,
    val timezone: String,
    val quietHoursStart: LocalTime,
    val quietHoursEnd: LocalTime,
)
