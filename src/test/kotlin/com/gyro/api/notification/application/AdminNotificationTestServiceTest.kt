package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.delivery.TelegramBotClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun <T> anyNotificationTestRequest(): T = Mockito.any()

class AdminNotificationTestServiceTest {
    private val push = Mockito.mock(PushSubscriptionService::class.java)
    private val telegram = Mockito.mock(TelegramAccountLinkingService::class.java)
    private val notifications = Mockito.mock(NotificationService::class.java)
    private val botProvider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<TelegramBotClient>
    private val time = Mockito.mock(TimeProvider::class.java)

    @Test
    fun `queues each selected channel only for an active user endpoint`() {
        val userId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val now = Instant.parse("2026-07-18T00:00:00Z")
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(push.hasActiveSubscription(userId)).thenReturn(true)
        Mockito.`when`(telegram.hasActiveEndpoint(userId)).thenReturn(false)
        var captured: com.gyro.api.notification.domain.NotificationRequest? = null
        Mockito.`when`(notifications.createWithOutcome(anyNotificationTestRequest())).thenAnswer { invocation ->
            captured = invocation.getArgument(0)
            NotificationCreateOutcome(UUID.randomUUID(), true)
        }
        val service = service(enabledProperties())

        val result = service.send(userId, webPush = true, telegram = true, requestId)

        assertEquals(AdminNotificationTestStatus.QUEUED, result.webPush?.status)
        assertEquals(AdminNotificationTestStatus.NO_ACTIVE_ENDPOINT, result.telegram?.status)
        assertEquals(NotificationType.PUSH_TEST, captured?.type)
        assertEquals(userId, captured?.recipientUserId)
    }

    @Test
    fun `reports disabled channels without creating an intent`() {
        val result = service(NotificationProperties()).send(
            UUID.randomUUID(),
            webPush = true,
            telegram = true,
            requestId = UUID.randomUUID(),
        )

        assertEquals(AdminNotificationTestStatus.CHANNEL_DISABLED, result.webPush?.status)
        assertEquals(AdminNotificationTestStatus.CHANNEL_DISABLED, result.telegram?.status)
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `runtime never exposes proxy credentials`() {
        val runtime = service(
            NotificationProperties(
                egressProxyType = com.gyro.api.notification.config.NotificationEgressProxyType.HTTP,
                egressProxyHost = "proxy.internal",
                egressProxyPort = 1080,
                egressProxyUsername = "user",
                egressProxyPassword = "secret",
            ),
        ).runtime()

        assertEquals("proxy.internal", runtime.proxy.host)
        assertEquals(1080, runtime.proxy.port)
        assertEquals(true, runtime.proxy.authenticated)
        assertNull(runtime.telegramWebhook.registeredUrl)
    }

    private fun service(properties: NotificationProperties) = AdminNotificationTestService(
        properties,
        push,
        telegram,
        notifications,
        botProvider,
        time,
    )

    private fun enabledProperties() = NotificationProperties(
        webPushEnabled = true,
        webPushPublicKey = "public-key",
        telegramEnabled = true,
        telegramBotToken = "token",
        telegramChatId = "@channel",
        telegramLinkingEnabled = true,
        telegramBotUsername = "gyro_bot",
        telegramWebhookSecret = "test-webhook-secret",
        telegramWebhookUrl = "https://api.example.com/api/v1/integrations/telegram/webhook",
    )
}
