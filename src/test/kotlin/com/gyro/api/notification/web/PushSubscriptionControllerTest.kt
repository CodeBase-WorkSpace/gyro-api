package com.gyro.api.notification.web

import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.application.PushSubscriptionRateLimitService
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.NotificationCreateOutcome
import com.gyro.api.notification.application.PushSubscriptionStatus
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.Base64
import kotlin.test.assertEquals

class PushSubscriptionControllerTest {
    @Test
    fun `disabled Push rejects public key and subscription`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val controller = PushSubscriptionController(service, NotificationProperties(webPushPublicKey = "key"), Mockito.mock(NotificationService::class.java), Mockito.mock(TimeProvider::class.java), Mockito.mock(NotificationIntentRepository::class.java), rateLimits())

        assertThrows<ResponseStatusException> { controller.publicKey() }
        assertThrows<ResponseStatusException> { controller.subscribe("00000000-0000-0000-0000-000000000001", PushSubscriptionRequest("https://push.example.test/a", "p256dh", "auth")) }
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun `reports subscription status and scopes disable operations explicitly`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val notifications = Mockito.mock(NotificationService::class.java)
        val controller = PushSubscriptionController(service, enabledProperties(), notifications, Mockito.mock(TimeProvider::class.java), Mockito.mock(NotificationIntentRepository::class.java), rateLimits())
        val userId = UUID.randomUUID()
        Mockito.`when`(service.status(userId)).thenReturn(PushSubscriptionStatus(true, 1))
        Mockito.`when`(service.hasActiveSubscription(userId, "https://push.example.test/current")).thenReturn(true)

        assertEquals(true, controller.status(userId.toString()).hasActiveSubscription)
        assertEquals(
            true,
            controller.currentStatus(userId.toString(), CurrentPushSubscriptionStatusRequest("https://push.example.test/current")).hasActiveSubscription,
        )
        controller.unsubscribe(userId.toString(), RevokePushSubscriptionRequest("https://push.example.test/current"))
        controller.unsubscribeAll(userId.toString())
        Mockito.verify(service).revokeEndpoint(userId, "https://push.example.test/current")
        Mockito.verify(service).revokeAll(userId)
    }

    @Test
    fun `queues a test only for a subscribed user`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val notifications = Mockito.mock(NotificationService::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        val controller = PushSubscriptionController(service, enabledProperties(), notifications, time, intents, rateLimits())
        val userId = UUID.randomUUID()
        val now = Instant.parse("2026-07-16T00:00:00Z")
        Mockito.`when`(service.hasActiveSubscription(userId)).thenReturn(true)
        Mockito.`when`(time.now()).thenReturn(now)
        val expected = NotificationRequest(
            recipientUserId = userId,
            type = com.gyro.api.notification.domain.NotificationType.PUSH_TEST,
            templateData = emptyMap(),
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plus(Duration.ofMinutes(10)),
            idempotencyKey = "push-test:$userId:${now.epochSecond / 60}",
            requestId = "push-test:$userId:${now.epochSecond / 60}",
            sourceType = "PUSH_TEST",
            sourceReference = "user:$userId",
        )
        Mockito.`when`(notifications.createWithOutcome(expected))
            .thenReturn(NotificationCreateOutcome(UUID.randomUUID(), true))

        val response = controller.test(userId.toString())

        assertEquals(true, response.created)
        Mockito.verify(notifications).createWithOutcome(expected)
    }

    @Test
    fun `terminal test failure uses one deterministic retry intent per minute`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val notifications = Mockito.mock(NotificationService::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val intents = Mockito.mock(NotificationIntentRepository::class.java)
        val controller = PushSubscriptionController(service, enabledProperties(), notifications, time, intents, rateLimits())
        val userId = UUID.randomUUID()
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val bucketKey = "push-test:$userId:${now.epochSecond / 60}"
        val retryKey = "$bucketKey:retry:1"
        val terminal = Mockito.mock(com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity::class.java)
        Mockito.`when`(terminal.status).thenReturn(com.gyro.api.notification.domain.NotificationIntentStatus.FAILED)
        Mockito.`when`(service.hasActiveSubscription(userId)).thenReturn(true)
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(intents.findBySourceTypeAndIdempotencyKey("PUSH_TEST", bucketKey)).thenReturn(terminal)
        val expected = NotificationRequest(
            recipientUserId = userId,
            type = com.gyro.api.notification.domain.NotificationType.PUSH_TEST,
            templateData = emptyMap(),
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plus(Duration.ofMinutes(10)),
            idempotencyKey = retryKey,
            requestId = retryKey,
            sourceType = "PUSH_TEST",
            sourceReference = "user:$userId",
        )
        val outcome = NotificationCreateOutcome(UUID.randomUUID(), true)
        Mockito.`when`(notifications.createWithOutcome(expected)).thenReturn(outcome)

        controller.test(userId.toString())
        controller.test(userId.toString())

        Mockito.verify(notifications, Mockito.times(2)).createWithOutcome(expected)
    }

    @Test
    fun `rejects a subscription endpoint outside configured Push providers`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val controller = PushSubscriptionController(
            service,
            enabledProperties(),
            Mockito.mock(NotificationService::class.java),
            Mockito.mock(TimeProvider::class.java),
            Mockito.mock(NotificationIntentRepository::class.java),
            rateLimits(),
        )

        val error = assertThrows<ResponseStatusException> {
            controller.subscribe(
                UUID.randomUUID().toString(),
                PushSubscriptionRequest("https://attacker.example/push", "p256dh", "auth"),
            )
        }

        assertEquals(400, error.statusCode.value())
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun `rejects browser keys with invalid decoded lengths`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val controller = PushSubscriptionController(
            service,
            enabledProperties(),
            Mockito.mock(NotificationService::class.java),
            Mockito.mock(TimeProvider::class.java),
            Mockito.mock(NotificationIntentRepository::class.java),
            rateLimits(),
        )

        val error = assertThrows<ResponseStatusException> {
            controller.subscribe(
                UUID.randomUUID().toString(),
                PushSubscriptionRequest("https://push.example.test/current", "cDI1NmRo", "YXV0aA"),
            )
        }

        assertEquals(400, error.statusCode.value())
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun `accepts browser keys with Web Push decoded sizes`() {
        val service = Mockito.mock(PushSubscriptionService::class.java)
        val controller = PushSubscriptionController(
            service,
            enabledProperties(),
            Mockito.mock(NotificationService::class.java),
            Mockito.mock(TimeProvider::class.java),
            Mockito.mock(NotificationIntentRepository::class.java),
            rateLimits(),
        )
        val userId = UUID.randomUUID()

        controller.subscribe(
            userId.toString(),
            PushSubscriptionRequest("https://push.example.test/current", validP256dh(), validAuth()),
        )

        val invocation = Mockito.mockingDetails(service).invocations.single { it.method.name == "subscribe" }
        assertEquals(userId, invocation.arguments[0])
        val command = invocation.arguments[1] as com.gyro.api.notification.application.PushSubscriptionCommand
        assertEquals(validP256dh(), command.p256dh)
        assertEquals(validAuth(), command.auth)
    }

    private fun enabledProperties() = NotificationProperties(
        webPushEnabled = true,
        webPushPublicKey = "key",
        webPushAllowedEndpointHosts = setOf("push.example.test"),
    )

    private fun rateLimits() = Mockito.mock(PushSubscriptionRateLimitService::class.java)

    private fun validP256dh(): String = ByteArray(65).also { it[0] = 4 }
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun validAuth(): String = ByteArray(16)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
}
