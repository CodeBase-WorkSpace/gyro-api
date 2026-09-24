package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationEgressProxyType
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.delivery.TelegramBotClient
import com.gyro.api.notification.infrastructure.delivery.TelegramWebhookResult
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

enum class AdminNotificationTestStatus { QUEUED, ALREADY_QUEUED, CHANNEL_DISABLED, NO_ACTIVE_ENDPOINT }

data class AdminNotificationChannelTestResult(
    val status: AdminNotificationTestStatus,
    val intentId: UUID? = null,
)

data class AdminNotificationTestResult(
    val userId: UUID,
    val webPush: AdminNotificationChannelTestResult?,
    val telegram: AdminNotificationChannelTestResult?,
)

data class NotificationProxyRuntime(
    val type: NotificationEgressProxyType,
    val host: String?,
    val port: Int?,
    val authenticated: Boolean,
)

data class TelegramWebhookRuntime(
    val enabled: Boolean,
    val expectedUrl: String?,
    val registeredUrl: String?,
    val matchesExpectedUrl: Boolean,
    val pendingUpdateCount: Int?,
    val lastErrorMessage: String?,
    val providerReachable: Boolean,
)

data class NotificationRuntime(
    val webPushEnabled: Boolean,
    val telegramEnabled: Boolean,
    val telegramLinkingEnabled: Boolean,
    val proxy: NotificationProxyRuntime,
    val telegramWebhook: TelegramWebhookRuntime,
)

@Service
class AdminNotificationTestService(
    private val properties: NotificationProperties,
    private val pushSubscriptions: PushSubscriptionService,
    private val telegramLinking: TelegramAccountLinkingService,
    private val notifications: NotificationService,
    private val telegramBotProvider: ObjectProvider<TelegramBotClient>,
    private val time: TimeProvider,
) {
    fun runtime(): NotificationRuntime = NotificationRuntime(
        webPushEnabled = properties.webPushEnabled,
        telegramEnabled = properties.telegramEnabled,
        telegramLinkingEnabled = properties.telegramLinkingEnabled,
        proxy = NotificationProxyRuntime(
            type = properties.egressProxyType,
            host = properties.egressProxyHost.takeIf { properties.egressProxyType != NotificationEgressProxyType.NONE },
            port = properties.egressProxyPort.takeIf { properties.egressProxyType != NotificationEgressProxyType.NONE },
            authenticated = properties.egressProxyUsername.isNotBlank(),
        ),
        telegramWebhook = webhookRuntime(fetchWebhookInfo()),
    )

    fun ensureTelegramWebhook(): TelegramWebhookRuntime {
        if (!properties.telegramEnabled || !properties.telegramLinkingEnabled) return webhookRuntime(null)
        val bot = telegramBotProvider.ifAvailable ?: return webhookRuntime(TelegramWebhookResult.Unavailable)
        return webhookRuntime(bot.ensureWebhook(properties.telegramWebhookUrl, properties.telegramWebhookSecret))
    }

    fun send(userId: UUID, webPush: Boolean, telegram: Boolean, requestId: UUID): AdminNotificationTestResult {
        require(webPush || telegram) { "Select at least one notification test channel." }
        return AdminNotificationTestResult(
            userId = userId,
            webPush = if (webPush) queueWebPush(userId, requestId) else null,
            telegram = if (telegram) queueTelegram(userId, requestId) else null,
        )
    }

    private fun queueWebPush(userId: UUID, requestId: UUID): AdminNotificationChannelTestResult {
        if (!properties.webPushEnabled || properties.webPushPublicKey.isNullOrBlank()) {
            return AdminNotificationChannelTestResult(AdminNotificationTestStatus.CHANNEL_DISABLED)
        }
        if (!pushSubscriptions.hasActiveSubscription(userId)) {
            return AdminNotificationChannelTestResult(AdminNotificationTestStatus.NO_ACTIVE_ENDPOINT)
        }
        return queue(userId, NotificationType.PUSH_TEST, requestId, "ADMIN_PUSH_TEST")
    }

    private fun queueTelegram(userId: UUID, requestId: UUID): AdminNotificationChannelTestResult {
        if (!properties.telegramEnabled || !properties.telegramLinkingEnabled) {
            return AdminNotificationChannelTestResult(AdminNotificationTestStatus.CHANNEL_DISABLED)
        }
        if (!telegramLinking.hasActiveEndpoint(userId)) {
            return AdminNotificationChannelTestResult(AdminNotificationTestStatus.NO_ACTIVE_ENDPOINT)
        }
        return queue(userId, NotificationType.TELEGRAM_TEST, requestId, "ADMIN_TELEGRAM_TEST")
    }

    private fun queue(
        userId: UUID,
        type: NotificationType,
        requestId: UUID,
        sourceType: String,
    ): AdminNotificationChannelTestResult {
        val now = time.now()
        val outcome = notifications.createWithOutcome(
            NotificationRequest(
                recipientUserId = userId,
                type = type,
                templateData = emptyMap(),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofMinutes(10)),
                idempotencyKey = "${sourceType.lowercase()}:$userId:$requestId",
                requestId = requestId.toString(),
                sourceType = sourceType,
                sourceReference = "user:$userId",
            ),
        )
        return AdminNotificationChannelTestResult(
            if (outcome.created) AdminNotificationTestStatus.QUEUED else AdminNotificationTestStatus.ALREADY_QUEUED,
            outcome.intentId,
        )
    }

    private fun fetchWebhookInfo(): TelegramWebhookResult? {
        if (!properties.telegramEnabled || !properties.telegramLinkingEnabled) return null
        return telegramBotProvider.ifAvailable?.webhookInfo() ?: TelegramWebhookResult.Unavailable
    }

    private fun webhookRuntime(result: TelegramWebhookResult?): TelegramWebhookRuntime {
        val info = (result as? TelegramWebhookResult.Success)?.info
        val expected = properties.telegramWebhookUrl.takeIf(String::isNotBlank)
        return TelegramWebhookRuntime(
            enabled = properties.telegramEnabled && properties.telegramLinkingEnabled,
            expectedUrl = expected,
            registeredUrl = info?.url,
            matchesExpectedUrl = expected != null && info?.url == expected,
            pendingUpdateCount = info?.pendingUpdateCount,
            lastErrorMessage = info?.lastErrorMessage,
            providerReachable = result is TelegramWebhookResult.Success,
        )
    }
}
