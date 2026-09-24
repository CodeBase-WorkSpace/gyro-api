package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.RenderedNotification
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Utils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.apache.http.client.methods.HttpPost
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.BigIntegers
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import tools.jackson.databind.ObjectMapper

private class FakeWebPushClient : WebPushClient {
    var lastPayload: String? = null
    override fun send(subscription: com.gyro.api.notification.application.ActivePushSubscription, payload: String): WebPushProviderResponse {
        lastPayload = payload
        return WebPushProviderResponse(201)
    }
}

class WebPushNotificationAdapterTest {
    @Test
    fun `provider client prepares VAPID request without exposing provider response body`() {
        val server = MockWebServer()
        server.enqueue(MockResponse(code = 201, body = "  accepted\nby provider  "))
        server.start()
        val (serverPublicKey, serverPrivateKey) = keyPair()
        val (browserPublicKey, _) = keyPair()
        val auth = ByteArray(16).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val client = VapidWebPushClient(
            NotificationProperties(
                webPushPublicKey = serverPublicKey,
                webPushPrivateKey = serverPrivateKey,
                webPushSubject = "mailto:info@gyrohealth.ir",
                webPushTimeout = Duration.ofSeconds(2),
            ),
        )

        try {
            val response = client.send(
                com.gyro.api.notification.application.ActivePushSubscription(
                    server.url("/push").toString(),
                    browserPublicKey,
                    auth,
                    "fingerprint",
                ),
                "{\"title\":\"جیرو\"}",
            )

            assertEquals(201, response.statusCode)
            assertEquals(null, response.classification)
            val request = server.takeRequest()
            assertEquals("aes128gcm", request.headers["Content-Encoding"])
            assertEquals(true, request.headers["Authorization"]?.startsWith("vapid t=") == true)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `provider client uses modern aes128gcm encoding`() {
        val service = Mockito.mock(PushService::class.java)
        val notification = Mockito.mock(Notification::class.java)
        val request = Mockito.mock(HttpPost::class.java)
        Mockito.`when`(service.preparePost(notification, Encoding.AES128GCM)).thenReturn(request)

        assertEquals(request, prepareWebPush(service, notification))
        Mockito.verify(service).preparePost(notification, Encoding.AES128GCM)
    }

    @Test
    fun `resumed Web Push is uncertain and never resubmitted`() {
        val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
        val adapter = WebPushNotificationAdapter(
            subscriptions,
            NotificationProperties(),
            Mockito.mock(NotificationIntentRepository::class.java),
            FakeWebPushClient(),
            ObjectMapper(),
            Mockito.mock(NotificationMetrics::class.java),
        )

        val result = adapter.reconcile(
            RenderedNotification(UUID.randomUUID(), UUID.randomUUID(), NotificationType.FOOD_MEAL_REMINDER, NotificationChannel.PUSH, "push:subscription", UUID.randomUUID().toString(), null, "body", null),
        )

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
    }

    @Test
    fun `provider connection failure before dispatch is transient`() {
        val (adapter, notification) = adapterWithThrowingClient(
            WebPushTransportException(false, ConnectException("proxy unavailable")),
        )

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `provider timeout after dispatch remains uncertain`() {
        val (adapter, notification) = adapterWithThrowingClient(
            WebPushTransportException(true, SocketTimeoutException("response timeout")),
        )

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, result.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, result.classification)
    }

    @Test
    fun `push payload title uses the rendered subject and falls back to the brand name`() {
        val userId = UUID.randomUUID()
        val intentId = UUID.randomUUID()
        val now = java.time.Instant.now()
        val intent = com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity(
            id = intentId,
            userId = userId,
            type = NotificationType.ADMIN_ANNOUNCEMENT,
            category = com.gyro.api.notification.domain.NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            risk = com.gyro.api.notification.domain.NotificationRisk.LOW,
            routeStrategy = com.gyro.api.notification.domain.NotificationRouteStrategy.EXPLICIT_CHANNELS,
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plusSeconds(3_600),
            idempotencyKey = "test:$intentId",
            sourceType = "TEST",
            sourceReference = "test",
            requestId = "test",
            templateData = null,
        )
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        Mockito.`when`(intents.findById(intentId)).thenReturn(java.util.Optional.of(intent))
        val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
        Mockito.`when`(subscriptions.activeAll(userId)).thenReturn(
            listOf(com.gyro.api.notification.application.ActivePushSubscription("https://push.example/e", "p256dh", "auth", "fingerprint")),
        )
        val client = FakeWebPushClient()
        val adapter = WebPushNotificationAdapter(
            subscriptions,
            NotificationProperties(webPushEnabled = true, webPushPublicKey = "public", webPushPrivateKey = "private"),
            intents,
            client,
            ObjectMapper(),
            Mockito.mock(NotificationMetrics::class.java),
        )

        fun rendered(subject: String?) = RenderedNotification(
            intentId, UUID.randomUUID(), NotificationType.ADMIN_ANNOUNCEMENT, NotificationChannel.PUSH,
            "internal:push", "provider-request", subject, "متن\nاعلان", null,
        )

        adapter.deliver(rendered("عنوان اعلان"))
        assertEquals(true, client.lastPayload?.contains("\"title\":\"عنوان اعلان\""))
        assertEquals(true, client.lastPayload?.contains("متن\\nاعلان"))

        adapter.deliver(rendered(null))
        assertEquals(true, client.lastPayload?.contains("\"title\":\"جیرو\""))
    }

    @Test
    fun `maps provider status and revokes invalid endpoint`() {
        val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
        val adapter = WebPushNotificationAdapter(subscriptions, NotificationProperties(), Mockito.mock(NotificationIntentRepository::class.java), FakeWebPushClient(), ObjectMapper(), Mockito.mock(NotificationMetrics::class.java))
        val userId = UUID.randomUUID()

        assertEquals(AdapterOutcome.SUCCESS, adapter.resultForStatus(201, userId, "fingerprint").outcome)
        assertEquals(AdapterOutcome.THROTTLED, adapter.resultForStatus(429, userId, "fingerprint").outcome)
        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, adapter.resultForStatus(500, userId, "fingerprint").outcome)
        assertEquals(AdapterOutcome.PERMANENT_FAILURE, adapter.resultForStatus(400, userId, "fingerprint").outcome)
        assertEquals(AdapterOutcome.INVALID_ENDPOINT, adapter.resultForStatus(410, userId, "fingerprint").outcome)
        Mockito.verify(subscriptions).revoke(userId, "fingerprint")
    }

    @Test
    fun `multi-device delivery succeeds when any active endpoint accepts the Push`() {
        val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
        val adapter = WebPushNotificationAdapter(subscriptions, NotificationProperties(), Mockito.mock(NotificationIntentRepository::class.java), FakeWebPushClient(), ObjectMapper(), Mockito.mock(NotificationMetrics::class.java))

        val result = adapter.aggregate(
            listOf(
                com.gyro.api.notification.domain.AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT),
                com.gyro.api.notification.domain.AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS),
            ),
        )

        assertEquals(AdapterOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `classifies provider responses without retaining raw details`() {
        assertEquals(WebPushProviderClassification.BAD_JWT, classifyProviderResponse(403, "BadJwtToken request-123"))
        assertEquals(WebPushProviderClassification.INVALID_AUDIENCE, classifyProviderResponse(403, "invalid audience"))
        assertEquals(WebPushProviderClassification.EXPIRED_SUBSCRIPTION, classifyProviderResponse(410, "arbitrary provider text"))
        assertEquals(WebPushProviderClassification.RATE_LIMITED, classifyProviderResponse(429, "slow down"))
        assertEquals(WebPushProviderClassification.PROVIDER_ERROR, classifyProviderResponse(500, "upstream request abc"))
    }

    private fun adapterWithThrowingClient(exception: WebPushTransportException): Pair<WebPushNotificationAdapter, RenderedNotification> {
        val userId = UUID.randomUUID()
        val intentId = UUID.randomUUID()
        val now = java.time.Instant.now()
        val intent = com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity(
            id = intentId,
            userId = userId,
            type = NotificationType.PUSH_TEST,
            category = com.gyro.api.notification.domain.NotificationCategory.MANDATORY_TRANSACTIONAL,
            risk = com.gyro.api.notification.domain.NotificationRisk.LOW,
            routeStrategy = com.gyro.api.notification.domain.NotificationRouteStrategy.EXPLICIT_CHANNELS,
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plusSeconds(3_600),
            idempotencyKey = "test:$intentId",
            sourceType = "TEST",
            sourceReference = "test",
            requestId = "test",
            templateData = null,
        )
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        Mockito.`when`(intents.findById(intentId)).thenReturn(java.util.Optional.of(intent))
        val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
        Mockito.`when`(subscriptions.activeAll(userId)).thenReturn(
            listOf(com.gyro.api.notification.application.ActivePushSubscription("https://push.example/e", "p256dh", "auth", "fingerprint")),
        )
        val client = object : WebPushClient {
            override fun send(
                subscription: com.gyro.api.notification.application.ActivePushSubscription,
                payload: String,
            ): WebPushProviderResponse = throw exception
        }
        val adapter = WebPushNotificationAdapter(
            subscriptions,
            NotificationProperties(webPushEnabled = true, webPushPublicKey = "public", webPushPrivateKey = "private"),
            intents,
            client,
            ObjectMapper(),
            Mockito.mock(NotificationMetrics::class.java),
        )
        val notification = RenderedNotification(
            intentId,
            UUID.randomUUID(),
            NotificationType.PUSH_TEST,
            NotificationChannel.PUSH,
            "internal:push",
            "provider-request",
            "Test",
            "Body",
            null,
        )
        return adapter to notification
    }

    private fun keyPair(): Pair<String, String> {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
        val pair = generator.generateKeyPair()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(Utils.encode(pair.public as ECPublicKey)) to
            encoder.encodeToString(BigIntegers.asUnsignedByteArray(32, (pair.private as ECPrivateKey).d))
    }
}
