package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.ActiveTelegramEndpoint
import com.gyro.api.notification.application.TelegramAccountLinkingService
import com.gyro.api.notification.config.NotificationEgressProxyType
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationRisk
import com.gyro.api.notification.domain.NotificationRouteStrategy
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.RenderedNotification
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramNotificationAdapterTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `webhook registration and inspection use Bot API through the shared client`() {
        server.enqueue(MockResponse(code = 200, body = """{"ok":true,"result":true}"""))
        server.enqueue(MockResponse(code = 200, body = """{"ok":true,"result":{"url":"https://api.example.com/api/v1/integrations/telegram/webhook","pending_update_count":2}}"""))
        server.start()
        val properties = telegramProperties()
        val bot = TelegramBotClient(properties, ObjectMapper(), tracked(OkHttpClient()))

        val result = bot.ensureWebhook(
            "https://api.example.com/api/v1/integrations/telegram/webhook",
            "test-webhook-secret",
        )

        val success = result as TelegramWebhookResult.Success
        assertEquals("https://api.example.com/api/v1/integrations/telegram/webhook", success.info.url)
        assertEquals(2, success.info.pendingUpdateCount)
        val registration = server.takeRequest()
        assertTrue(registration.url.encodedPath.endsWith("/bottest-token/setWebhook"))
        assertTrue(registration.body?.utf8().orEmpty().contains("test-webhook-secret"))
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/bottest-token/getWebhookInfo"))
    }

    @Test
    fun `webhook inspection does not expose malformed provider responses`() {
        server.enqueue(MockResponse(code = 200, body = "not-json"))
        server.start()
        val bot = TelegramBotClient(telegramProperties(), ObjectMapper(), tracked(OkHttpClient()))

        assertEquals(TelegramWebhookResult.Unavailable, bot.webhookInfo())
    }

    @Test
    fun `successful send posts subject and body to the configured chat`() {
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = """{"ok":true,"result":{"message_id":42}}"""))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.SUCCESS, result.outcome)
        assertEquals(AdapterClassification.LOG_ONLY_SUCCESS, result.classification)
        assertEquals(64, result.providerReferenceDigest?.length)
        val recorded = server.takeRequest()
        assertTrue(recorded.url.encodedPath.endsWith("/bottest-token/sendMessage"))
        val payload = recorded.body?.utf8().orEmpty()
        assertTrue(payload.contains("\"chat_id\":\"@gyro_channel\""))
        assertTrue(payload.contains("عنوان"))
        assertTrue(payload.contains("متن اعلان"))
    }

    @Test
    fun `large successful response remains successful for long operator announcement`() {
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = largeSuccessBody()))
        val longAnnouncement = notification().copy(plainBody = "x".repeat(3_900))

        val result = adapter.deliver(longAnnouncement)

        assertEquals(AdapterOutcome.SUCCESS, result.outcome)
        assertEquals(AdapterClassification.LOG_ONLY_SUCCESS, result.classification)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("x".repeat(3_900)))
    }

    @Test
    fun `overflowing successful response is uncertain and is never parsed as truncated JSON`() {
        val oversized = """{"ok":true,"result":{"message_id":42,"text":"${"x".repeat(300_000)}"}}"""
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = oversized))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed successful response is uncertain after provider acceptance`() {
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = """{"ok":true,"result":"""))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `HTTP 429 is throttled with the provider retry delay`() {
        val adapter = startAndCreateAdapter(
            MockResponse(code = 429, body = """{"ok":false,"parameters":{"retry_after":17}}"""),
        )

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.THROTTLED, result.outcome)
        assertEquals(AdapterClassification.RATE_LIMIT, result.classification)
        assertEquals(Duration.ofSeconds(17), result.retryAfter)
    }

    @Test
    fun `HTTP 500 is transient`() {
        val adapter = startAndCreateAdapter(MockResponse(code = 500, body = "{}"))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `HTTP 400 bad chat is permanent for operator channel`() {
        val adapter = startAndCreateAdapter(
            MockResponse(code = 400, body = """{"ok":false,"description":"chat not found"}"""),
        )

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_PERMANENT, result.classification)
    }

    @Test
    fun `ok false body is permanent`() {
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = """{"ok":false}"""))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_PERMANENT, result.classification)
    }

    @Test
    fun `timeout after dispatch is unknown because the post may already be published`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"ok":true,"result":{"message_id":1}}""")
                .bodyDelay(1, TimeUnit.SECONDS)
                .build(),
        )
        server.start()
        val client = tracked(OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build())
        val adapter = createAdapter(client)

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
    }

    @Test
    fun `connection failure before dispatch is transient and retryable`() {
        server.start()
        val port = server.port
        server.close()
        val properties = NotificationProperties(
            telegramEnabled = true,
            telegramApiBaseUrl = "http://127.0.0.1:$port",
            telegramBotToken = "test-token",
            telegramChatId = "@gyro_channel",
        )
        val client = tracked(OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build())
        val adapter = TelegramNotificationAdapter(properties, TelegramBotClient(properties, ObjectMapper(), client))

        val result = adapter.deliver(notification())

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `built client carries shared proxy configuration and no token-capturing interceptors`() {
        val client = TelegramBotClient.buildClient(
            NotificationProperties(
                telegramEnabled = true,
                telegramBotToken = "test-token",
                telegramChatId = "@gyro_channel",
                egressProxyType = NotificationEgressProxyType.HTTP,
                egressProxyHost = "proxy.local",
                egressProxyPort = 1080,
            ),
        )

        assertEquals(emptyList(), client.interceptors)
        assertEquals(emptyList(), client.networkInterceptors)
        assertEquals(java.net.Proxy.Type.HTTP, client.proxy?.type())
    }

    @Test
    fun `resumed send is uncertain and never resubmitted`() {
        server.start()
        val result = createAdapter(tracked(OkHttpClient())).reconcile(notification())

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
    }

    @Test
    fun `personal adapter marks endpoint blocked after Telegram 403`() {
        val notification = notification().copy(type = NotificationType.TELEGRAM_TEST)
        val linking = Mockito.mock(TelegramAccountLinkingService::class.java)
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        val userId = UUID.randomUUID()
        server.enqueue(MockResponse(code = 403, body = """{"ok":false,"description":"Forbidden"}"""))
        server.start()
        val properties = telegramProperties()
        val bot = TelegramBotClient(properties, ObjectMapper(), tracked(OkHttpClient()))
        val adapter = TelegramUserNotificationAdapter(linking, intents, bot)
        Mockito.`when`(intents.findById(notification.intentId)).thenReturn(Optional.of(intent(notification.intentId, userId)))
        Mockito.`when`(linking.active(userId)).thenReturn(ActiveTelegramEndpoint("123456"))

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        Mockito.verify(linking).markBlocked(userId)
    }

    @Test
    fun `personal adapter revokes endpoint after definite invalid chat`() {
        val notification = notification().copy(type = NotificationType.TELEGRAM_TEST)
        val linking = Mockito.mock(TelegramAccountLinkingService::class.java)
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        val userId = UUID.randomUUID()
        server.enqueue(
            MockResponse(code = 400, body = """{"ok":false,"description":"Bad Request: chat not found"}"""),
        )
        server.start()
        val properties = telegramProperties()
        val bot = TelegramBotClient(properties, ObjectMapper(), tracked(OkHttpClient()))
        val adapter = TelegramUserNotificationAdapter(linking, intents, bot)
        Mockito.`when`(intents.findById(notification.intentId)).thenReturn(Optional.of(intent(notification.intentId, userId)))
        Mockito.`when`(linking.active(userId)).thenReturn(ActiveTelegramEndpoint("123456"))

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.INVALID_ENDPOINT, result.outcome)
        Mockito.verify(linking).markRevoked(userId)
    }

    @Test
    fun `large successful response remains successful for personal adapter`() {
        val notification = notification().copy(type = NotificationType.TELEGRAM_TEST)
        val linking = Mockito.mock(TelegramAccountLinkingService::class.java)
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        val userId = UUID.randomUUID()
        server.enqueue(MockResponse(code = 200, body = largeSuccessBody()))
        server.start()
        val properties = telegramProperties()
        val adapter = TelegramUserNotificationAdapter(
            linking,
            intents,
            TelegramBotClient(properties, ObjectMapper(), tracked(OkHttpClient())),
        )
        Mockito.`when`(intents.findById(notification.intentId)).thenReturn(Optional.of(intent(notification.intentId, userId)))
        Mockito.`when`(linking.active(userId)).thenReturn(ActiveTelegramEndpoint("123456"))

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.SUCCESS, result.outcome)
        assertEquals(AdapterClassification.LOG_ONLY_SUCCESS, result.classification)
        assertEquals(1, server.requestCount)
    }

    private fun startAndCreateAdapter(response: MockResponse): TelegramNotificationAdapter {
        server.enqueue(response)
        server.start()
        return createAdapter(tracked(OkHttpClient()))
    }

    private fun createAdapter(client: OkHttpClient): TelegramNotificationAdapter {
        val properties = telegramProperties()
        return TelegramNotificationAdapter(properties, TelegramBotClient(properties, ObjectMapper(), client))
    }

    private fun tracked(client: OkHttpClient) = NotificationHttpClientFactory.withTransmissionTracking(client)

    private fun telegramProperties() = NotificationProperties(
        telegramEnabled = true,
        telegramApiBaseUrl = server.url("/").toString().removeSuffix("/"),
        telegramBotToken = "test-token",
        telegramChatId = "@gyro_channel",
    )

    private fun intent(intentId: UUID, userId: UUID): NotificationIntentEntity {
        val now = Instant.now()
        return NotificationIntentEntity(
            id = intentId,
            userId = userId,
            type = NotificationType.TELEGRAM_TEST,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = NotificationRisk.LOW,
            routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plusSeconds(600),
            idempotencyKey = "test",
            sourceType = "TEST",
            sourceReference = "user:$userId",
            requestId = "test",
            templateData = "{}",
            status = NotificationIntentStatus.ROUTED,
        )
    }

    private fun notification() = RenderedNotification(
        intentId = UUID.randomUUID(),
        deliveryId = UUID.randomUUID(),
        type = NotificationType.TELEGRAM_CHANNEL_POST,
        channel = NotificationChannel.TELEGRAM,
        endpointReference = "internal:telegram",
        providerRequestId = "provider-request",
        subject = "عنوان",
        plainBody = "متن اعلان",
        htmlBody = null,
    )

    private fun largeSuccessBody() =
        """{"ok":true,"result":{"message_id":42,"text":"${"x".repeat(10_000)}"}}"""
}
