package com.gyro.api.notification.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class NotificationType { CORE_PROBE, PAYMENT_VERIFIED, PREMIUM_ACCESS_DOWNGRADED, RECALIBRATION_SUGGESTION, COACH_DATA_NUDGE, FOOD_MEAL_REMINDER, FOOD_INCOMPLETE_DAY_REMINDER, WEIGHT_REMINDER, ADMIN_ANNOUNCEMENT, TELEGRAM_CHANNEL_POST, PUSH_TEST, TELEGRAM_LINK_CONFIRMATION, TELEGRAM_TEST }
enum class NotificationChannel { EMAIL, SMS, PUSH, TELEGRAM, IRANIAN_MESSENGER, IN_APP }
enum class NotificationCategory { MANDATORY_TRANSACTIONAL, OPTIONAL_BILLING, OPTIONAL_FOOD_LOGGING, OPTIONAL_WEIGHT_LOGGING, OPTIONAL_ANNOUNCEMENTS }
enum class NotificationRisk { LOW, MEDIUM, HIGH, CRITICAL }
enum class NotificationRouteStrategy { EXPLICIT_CHANNELS, ALL_ELIGIBLE, PREFERRED_AVAILABLE }
enum class NotificationIntentStatus { PENDING, ROUTED, COMPLETED, PARTIALLY_COMPLETED, SUPPRESSED, UNDELIVERABLE, EXPIRED, FAILED }
enum class NotificationDeliveryStatus { PENDING, CLAIMED, RETRY_SCHEDULED, DELIVERED, SUPPRESSED, EXPIRED, PERMANENT_FAILURE, DEAD_LETTER }
enum class NotificationReason {
    NO_VERIFIED_ENDPOINT,
    PREFERENCE_DISABLED,
    MISSED_ALLOWED_WINDOW,
    CHANNEL_DISABLED,
    ENDPOINT_INVALID,
    RATE_LIMIT,
    BUDGET_LIMIT,
    PROVIDER_TRANSIENT,
    PROVIDER_PERMANENT,
    RETRY_EXHAUSTED,
    PROCESSING_FAILURE,
}
enum class AdapterOutcome { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, INVALID_ENDPOINT, THROTTLED, UNKNOWN_FAILURE, UNKNOWN_AFTER_SEND }
enum class AdapterClassification {
    LOG_ONLY_SUCCESS,
    PROVIDER_TRANSIENT,
    PROVIDER_PERMANENT,
    ENDPOINT_INVALID,
    RATE_LIMIT,
    UNKNOWN_FAILURE,
    UNKNOWN_AFTER_SEND,
    INTERNAL_PROCESSING_FAILURE,
}
enum class AdapterOperation { RECONCILE, DELIVER }
enum class TemplateVariableType { TEXT, NUMBER, INSTANT, BOOLEAN }
enum class TemplateVariableSensitivity { PUBLIC, PRIVATE }
enum class TemplateVariablePersistence { ALLOWED, FORBIDDEN }

sealed interface TemplateVariableValue {
    data class Text(val value: String) : TemplateVariableValue
    data class Number(val value: BigDecimal) : TemplateVariableValue
    data class Timestamp(val value: Instant) : TemplateVariableValue
    data class Flag(val value: Boolean) : TemplateVariableValue
}

data class TemplateVariableRule(
    val type: TemplateVariableType,
    val sensitivity: TemplateVariableSensitivity,
    val persistence: TemplateVariablePersistence,
)

data class NotificationRetryPolicy(
    val maxAttempts: Int,
    val backoff: List<Duration>,
)

data class NotificationPolicy(
    val type: NotificationType,
    val category: NotificationCategory,
    val risk: NotificationRisk,
    val userConfigurable: Boolean,
    val allowedChannels: Set<NotificationChannel>,
    val routeStrategy: NotificationRouteStrategy,
    val templateKey: String,
    val templateVersion: Int,
    val templateLocale: String,
    val adapterKey: String,
    val defaultExpiry: Duration,
    val retryPolicy: NotificationRetryPolicy,
    val smsAllowed: Boolean,
    val requiredVariables: Map<String, TemplateVariableRule>,
    val adapterKeys: Map<NotificationChannel, String> = allowedChannels.associateWith { adapterKey },
)

data class NotificationRequest(
    val recipientUserId: UUID,
    val type: NotificationType,
    val templateData: Map<String, TemplateVariableValue>,
    val occurredAt: Instant,
    val scheduledAt: Instant,
    val expiresAt: Instant,
    val idempotencyKey: String,
    val requestId: String,
    val sourceType: String,
    val sourceReference: String,
)

data class RenderedNotification(
    val intentId: UUID,
    val deliveryId: UUID,
    val type: NotificationType,
    val channel: NotificationChannel,
    val endpointReference: String,
    val providerRequestId: String,
    val subject: String?,
    val plainBody: String,
    val htmlBody: String?,
)

data class NotificationDeliveryClaim(
    val deliveryId: UUID,
    val token: UUID,
    val owner: String,
    val expiresAt: Instant,
)

data class AdapterResult(
    val outcome: AdapterOutcome,
    val classification: AdapterClassification,
    val providerReferenceDigest: String? = null,
    val retryAfter: Duration? = null,
)
