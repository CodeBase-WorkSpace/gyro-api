package com.gyro.api.notification.application.policy

import com.gyro.api.notification.application.template.NotificationTemplateCatalog
import com.gyro.api.notification.domain.*
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class NotificationPolicyRegistry(
    templateCatalog: NotificationTemplateCatalog,
) {
    private val policies = listOf(
        NotificationPolicy(
            type = NotificationType.TELEGRAM_LINK_CONFIRMATION,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.TELEGRAM),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "telegram-link-confirmation",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "telegram-user",
            defaultExpiry = Duration.ofMinutes(10),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 1, backoff = emptyList()),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.TELEGRAM_TEST,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.TELEGRAM),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "telegram-test",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "telegram-user",
            defaultExpiry = Duration.ofMinutes(10),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 1, backoff = emptyList()),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.PUSH_TEST,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "push-test",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofMinutes(10),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 1, backoff = emptyList()),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.CORE_PROBE,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.EMAIL),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "notification-core-probe",
            templateVersion = 1,
            templateLocale = "en",
            adapterKey = "log-only",
            defaultExpiry = Duration.ofMinutes(10),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 1, backoff = emptyList()),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.PAYMENT_VERIFIED,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.MEDIUM,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.EMAIL, NotificationChannel.SMS),
            routeStrategy = NotificationRouteStrategy.PREFERRED_AVAILABLE,
            templateKey = "payment-verified",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "smtp-email",
            adapterKeys = mapOf(NotificationChannel.EMAIL to "smtp-email", NotificationChannel.SMS to "sms-ir"),
            defaultExpiry = Duration.ofHours(24),
            // SMTP does not expose a recipient-safe reconciliation path. Retrying an ambiguous
            // send failure could duplicate a payment receipt, so retries remain disabled until
            // the adapter can distinguish pre-send failures or reconcile provider acceptance.
            retryPolicy = NotificationRetryPolicy(maxAttempts = 1, backoff = emptyList()),
            smsAllowed = true,
            requiredVariables = mapOf(
                "amount" to TemplateVariableRule(TemplateVariableType.NUMBER, TemplateVariableSensitivity.PRIVATE, TemplateVariablePersistence.ALLOWED),
                "currency" to TemplateVariableRule(TemplateVariableType.TEXT, TemplateVariableSensitivity.PUBLIC, TemplateVariablePersistence.ALLOWED),
            ),
        ),
        NotificationPolicy(
            type = NotificationType.RECALIBRATION_SUGGESTION,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "recalibration-suggestion",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofHours(48),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.COACH_DATA_NUDGE,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "coach-data-nudge",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofHours(12),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = com.gyro.api.notification.application.template.NotificationTemplateCatalog.COACH_DATA_NUDGE_VARIABLES,
        ),
        NotificationPolicy(
            type = NotificationType.PREMIUM_ACCESS_DOWNGRADED,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "premium-access-downgraded",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofHours(48),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.FOOD_MEAL_REMINDER,
            category = NotificationCategory.OPTIONAL_FOOD_LOGGING,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "food-meal-reminder",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofMinutes(90),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.FOOD_INCOMPLETE_DAY_REMINDER,
            category = NotificationCategory.OPTIONAL_FOOD_LOGGING,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "food-incomplete-day-reminder",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofMinutes(90),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.WEIGHT_REMINDER,
            category = NotificationCategory.OPTIONAL_WEIGHT_LOGGING,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "weight-reminder",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofMinutes(90),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = emptyMap(),
        ),
        NotificationPolicy(
            type = NotificationType.ADMIN_ANNOUNCEMENT,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            risk = NotificationRisk.LOW,
            userConfigurable = true,
            allowedChannels = setOf(NotificationChannel.PUSH),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "admin-announcement",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "web-push",
            defaultExpiry = Duration.ofHours(24),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = com.gyro.api.notification.application.template.NotificationTemplateCatalog.ADMIN_ANNOUNCEMENT_VARIABLES,
        ),
        NotificationPolicy(
            type = NotificationType.TELEGRAM_CHANNEL_POST,
            // The recipient is an operator-configured Telegram channel, not a user endpoint, so
            // user consent and quiet hours must not gate or defer these posts.
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            userConfigurable = false,
            allowedChannels = setOf(NotificationChannel.TELEGRAM),
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            templateKey = "admin-announcement",
            templateVersion = 1,
            templateLocale = "fa",
            adapterKey = "telegram-bot",
            defaultExpiry = Duration.ofHours(24),
            retryPolicy = NotificationRetryPolicy(maxAttempts = 3, backoff = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5))),
            smsAllowed = false,
            requiredVariables = com.gyro.api.notification.application.template.NotificationTemplateCatalog.ADMIN_ANNOUNCEMENT_VARIABLES,
        ),
    ).associateBy(NotificationPolicy::type)

    init {
        check(policies.keys == NotificationType.entries.toSet()) { "Every notification type must have exactly one policy" }
        policies.values.forEach { policy ->
            check(policy.allowedChannels.isNotEmpty()) { "Notification policy ${policy.type} must allow a channel" }
            check(policy.retryPolicy.maxAttempts > 0) { "Notification policy ${policy.type} must have bounded attempts" }
            check(policy.retryPolicy.backoff.size <= policy.retryPolicy.maxAttempts - 1) {
                "Notification policy ${policy.type} has more backoff entries than retries"
            }
            check(policy.category != NotificationCategory.MANDATORY_TRANSACTIONAL || !policy.userConfigurable) {
                "Mandatory notification ${policy.type} cannot be user configurable"
            }
            check(policy.category != NotificationCategory.OPTIONAL_FOOD_LOGGING || !policy.smsAllowed) {
                "Food notification ${policy.type} cannot permit SMS"
            }
            check(policy.smsAllowed || NotificationChannel.SMS !in policy.allowedChannels) {
                "Notification policy ${policy.type} allows SMS without SMS permission"
            }
            check(policy.adapterKeys.keys == policy.allowedChannels) {
                "Notification policy ${policy.type} must define one adapter per allowed channel"
            }
            policy.allowedChannels.forEach { channel ->
                val template = templateCatalog.find(policy.templateKey, policy.templateVersion, channel, policy.templateLocale)
                    ?: error("Notification policy ${policy.type} references a missing $channel template")
                check(template.requiredVariables == policy.requiredVariables) {
                    "Notification policy ${policy.type} does not match its template variable schema"
                }
            }
        }
    }

    fun policyFor(type: NotificationType): NotificationPolicy = requireNotNull(policies[type])
}
