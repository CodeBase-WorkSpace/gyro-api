package com.gyro.api.notification.config

import com.gyro.api.notification.domain.NotificationChannel
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

enum class NotificationEgressProxyType { NONE, HTTP, SOCKS }

@ConfigurationProperties("app.notification")
data class NotificationProperties(
    val jobsEnabled: Boolean = true,
    val pollDelay: Duration = Duration.ofSeconds(5),
    val batchSize: Int = 50,
    val claimDuration: Duration = Duration.ofMinutes(1),
    val workerIdentity: String = "local",
    val contentRetention: Duration = Duration.ofDays(30),
    val endpointFingerprintKey: String = "gyro-development-notification-fingerprint-key",
    val processingFailureBackoff: Duration = Duration.ofMinutes(1),
    val processingFailureMaxAttempts: Int = 3,
    val endpointHealthCleanupBatchSize: Int = 250,
    val foodScheduleProcessingBackoff: Duration = Duration.ofMinutes(5),
    val foodScheduleProcessingMaxAttempts: Int = 3,
    val pushSubscriptionEncryptionKey: String = DEFAULT_PUSH_SUBSCRIPTION_ENCRYPTION_KEY,
    val webPushEnabled: Boolean = false,
    val webPushPublicKey: String? = null,
    val webPushPrivateKey: String? = null,
    val webPushSubject: String = "mailto:info@gyrohealth.ir",
    val webPushAllowedEndpointHosts: Set<String> = DEFAULT_WEB_PUSH_ALLOWED_ENDPOINT_HOSTS,
    val webPushMaxSubscriptionsPerUser: Int = 10,
    val webPushTimeout: Duration = Duration.ofSeconds(10),
    val pushMinimumGap: Duration = Duration.ofMinutes(5),
    val coachDataNudgeEnabled: Boolean = false,
    val coachDataNudgeLocalHour: Int = 18,
    val coachDataNudgeBatchSize: Int = 200,
    val coachDataNudgeReminderLookback: Duration = Duration.ofHours(24),
    val emailEnabled: Boolean = false,
    val telegramEnabled: Boolean = false,
    val telegramApiBaseUrl: String = "https://api.telegram.org",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val telegramLinkingEnabled: Boolean = false,
    val telegramBotUsername: String = "",
    val telegramWebhookSecret: String = "",
    val telegramWebhookUrl: String = "",
    val telegramEndpointEncryptionKey: String = DEFAULT_TELEGRAM_ENDPOINT_ENCRYPTION_KEY,
    val telegramLinkTokenTtl: Duration = Duration.ofMinutes(10),
    val telegramUnclaimedLinkCodeRetention: Duration = Duration.ofHours(1),
    val telegramLinkDataRetention: Duration = Duration.ofDays(7),
    val telegramCleanupBatchSize: Int = 500,
    val telegramTimeout: Duration = Duration.ofSeconds(10),
    val egressProxyType: NotificationEgressProxyType = NotificationEgressProxyType.NONE,
    val egressProxyHost: String = "",
    val egressProxyPort: Int = 0,
    val egressProxyUsername: String = "",
    val egressProxyPassword: String = "",
    val productSmsEnabled: Boolean = false,
    val productSmsBaseUrl: String = "https://api.sms.ir/v1",
    val productSmsApiKey: String = "",
    val productSmsLineNumber: String = "",
    val productSmsMandatoryUserDailyCap: Int = 5,
    val productSmsDailyGlobalCap: Int = 500,
) {
    val deliveryChannels: Set<NotificationChannel>
        get() = DEFAULT_DELIVERY_CHANNELS + if (telegramEnabled) setOf(NotificationChannel.TELEGRAM) else emptySet()

    init {
        require(batchSize in 1..500) { "Notification batch size must be between 1 and 500" }
        require(!claimDuration.isNegative && !claimDuration.isZero) { "Notification claim duration must be positive" }
        require(workerIdentity.isNotBlank()) { "Notification worker identity must not be blank" }
        require(endpointFingerprintKey.length >= 32) { "Notification endpoint fingerprint key must be at least 32 characters" }
        require(!processingFailureBackoff.isNegative && !processingFailureBackoff.isZero) {
            "Notification processing failure backoff must be positive"
        }
        require(processingFailureMaxAttempts in 1..20) {
            "Notification processing failure attempts must be between 1 and 20"
        }
        require(endpointHealthCleanupBatchSize in 1..1_000) {
            "Notification endpoint health cleanup batch size must be between 1 and 1000"
        }
        require(!foodScheduleProcessingBackoff.isNegative && !foodScheduleProcessingBackoff.isZero) {
            "Food schedule processing backoff must be positive"
        }
        require(foodScheduleProcessingMaxAttempts in 1..20) {
            "Food schedule processing attempts must be between 1 and 20"
        }
        require(java.util.Base64.getDecoder().decode(pushSubscriptionEncryptionKey).size == 32) {
            "Notification Push subscription encryption key must decode to 32 bytes"
        }
        require(webPushAllowedEndpointHosts.isNotEmpty()) { "Web Push endpoint host allowlist must not be empty" }
        require(webPushAllowedEndpointHosts.all { it.isNotBlank() && it == it.lowercase() }) {
            "Web Push endpoint host allowlist entries must be non-blank lowercase host names"
        }
        require(webPushMaxSubscriptionsPerUser in 1..25) {
            "Web Push subscriptions per user must be between 1 and 25"
        }
        require(!webPushTimeout.isNegative && !webPushTimeout.isZero && webPushTimeout <= Duration.ofMinutes(1)) {
            "Web Push timeout must be between 1 millisecond and 1 minute"
        }
        require(!pushMinimumGap.isNegative && !pushMinimumGap.isZero && pushMinimumGap <= Duration.ofHours(1)) {
            "Push minimum gap must be between 1 millisecond and 1 hour"
        }
        require(coachDataNudgeLocalHour in 0..23) { "Coach data nudge local hour must be between 0 and 23" }
        require(coachDataNudgeBatchSize in 1..1_000) { "Coach data nudge batch size must be between 1 and 1000" }
        require(
            !coachDataNudgeReminderLookback.isNegative &&
                !coachDataNudgeReminderLookback.isZero &&
                coachDataNudgeReminderLookback <= Duration.ofDays(7),
        ) { "Coach data nudge reminder lookback must be between 1 millisecond and 7 days" }
        require((egressProxyUsername.isBlank() && egressProxyPassword.isBlank()) ||
            (egressProxyUsername.isNotBlank() && egressProxyPassword.isNotBlank())) {
            "Notification egress proxy username and password must both be configured or both be blank"
        }
        require(egressProxyType != NotificationEgressProxyType.NONE || egressProxyUsername.isBlank()) {
            "Notification egress proxy credentials must not be configured when proxy type is NONE"
        }
        require(egressProxyType != NotificationEgressProxyType.SOCKS || egressProxyUsername.isBlank()) {
            "Authenticated SOCKS notification proxy egress is not supported; use HTTP or remove the credentials"
        }
        require(!telegramTimeout.isNegative && !telegramTimeout.isZero) { "Telegram timeout must be positive" }
        require(!(telegramEnabled && telegramLinkingEnabled) || runCatching {
            java.util.Base64.getDecoder().decode(telegramEndpointEncryptionKey).size == 32
        }.getOrDefault(false)) {
            "Notification Telegram endpoint encryption key must decode to 32 bytes when linking is enabled"
        }
        require(!telegramLinkTokenTtl.isNegative && !telegramLinkTokenTtl.isZero && telegramLinkTokenTtl <= Duration.ofHours(1)) {
            "Telegram link token TTL must be between 1 millisecond and 1 hour"
        }
        require(!telegramUnclaimedLinkCodeRetention.isNegative && !telegramUnclaimedLinkCodeRetention.isZero &&
            telegramUnclaimedLinkCodeRetention <= Duration.ofDays(1)) {
            "Unclaimed Telegram link code retention must be between 1 millisecond and 1 day"
        }
        require(!telegramLinkDataRetention.isNegative && !telegramLinkDataRetention.isZero) {
            "Telegram link data retention must be positive"
        }
        require(telegramCleanupBatchSize in 1..5_000) {
            "Telegram cleanup batch size must be between 1 and 5000"
        }
        require(productSmsMandatoryUserDailyCap in 1..100) { "Product SMS user daily cap must be between 1 and 100" }
        require(productSmsDailyGlobalCap in 1..100_000) { "Product SMS global daily cap must be between 1 and 100000" }
    }

    companion object {
        private val DEFAULT_DELIVERY_CHANNELS = setOf(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH)
        const val DEFAULT_PUSH_SUBSCRIPTION_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        const val DEFAULT_TELEGRAM_ENDPOINT_ENCRYPTION_KEY = "dGVsZWdyYW0tZGV2ZWxvcG1lbnQta2V5LTMyYnl0ZSE="
        val DEFAULT_WEB_PUSH_ALLOWED_ENDPOINT_HOSTS = setOf(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "push.services.mozilla.com",
            "web.push.apple.com",
            ".notify.windows.com",
        )
    }
}
