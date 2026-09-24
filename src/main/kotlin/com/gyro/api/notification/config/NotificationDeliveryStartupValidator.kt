package com.gyro.api.notification.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class NotificationDeliveryStartupValidator(
    private val properties: NotificationProperties,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (properties.emailEnabled) {
            require(environment.getProperty("spring.mail.host").orEmpty().isNotBlank()) {
                "MAIL_HOST must be configured when notification email is enabled."
            }
            require(environment.getProperty("gyro.email.from").orEmpty().isNotBlank()) {
                "MAIL_FROM must be configured when notification email is enabled."
            }
        }
        if (properties.productSmsEnabled) {
            require(properties.productSmsApiKey.isNotBlank()) {
                "NOTIFICATION_PRODUCT_SMS_API_KEY must be configured when product SMS is enabled."
            }
            require(properties.productSmsLineNumber.isNotBlank()) {
                "NOTIFICATION_PRODUCT_SMS_LINE_NUMBER must be configured when product SMS is enabled."
            }
        }
        if (properties.telegramEnabled) {
            require(properties.telegramBotToken.isNotBlank()) {
                "NOTIFICATION_TELEGRAM_BOT_TOKEN must be configured when Telegram is enabled."
            }
            require(properties.telegramChatId.isNotBlank()) {
                "NOTIFICATION_TELEGRAM_CHAT_ID must be configured when Telegram is enabled."
            }
        }
        if (properties.telegramEnabled && properties.telegramLinkingEnabled) {
            require(properties.telegramBotUsername.matches(Regex("^[A-Za-z0-9_]{5,32}$"))) {
                "NOTIFICATION_TELEGRAM_BOT_USERNAME must be configured when Telegram linking is enabled."
            }
            require(properties.telegramWebhookSecret.matches(Regex("^[A-Za-z0-9_-]{16,256}$"))) {
                "NOTIFICATION_TELEGRAM_WEBHOOK_SECRET must contain 16-256 Bot API safe characters when Telegram linking is enabled."
            }
            require(runCatching {
                val uri = java.net.URI(properties.telegramWebhookUrl)
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null
            }.getOrDefault(false)) {
                "NOTIFICATION_TELEGRAM_WEBHOOK_URL must be a public HTTPS URL when Telegram linking is enabled."
            }
        }
        if (properties.egressProxyType != NotificationEgressProxyType.NONE) {
            require(properties.egressProxyHost.isNotBlank()) {
                "NOTIFICATION_EGRESS_PROXY_HOST must be configured when notification proxy egress is enabled."
            }
            require(properties.egressProxyPort in 1..65_535) {
                "NOTIFICATION_EGRESS_PROXY_PORT must be a valid port when notification proxy egress is enabled."
            }
        }
    }
}
