package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.persistence.NotificationPushSubscriptionEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationPushSubscriptionRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PushSubscriptionServiceTest {
    @Test
    fun `status is a read-only active subscription projection`() {
        val repository = Mockito.mock(NotificationPushSubscriptionRepository::class.java)
        val userId = UUID.randomUUID()
        Mockito.`when`(repository.countByUserIdAndRevokedAtIsNullAndKeyVersion(userId, "v1")).thenReturn(2)
        val service = PushSubscriptionService(
            repository,
            Mockito.mock(NotificationScheduleRepository::class.java),
            testProperties(),
            Mockito.mock(TimeProvider::class.java),
            Mockito.mock(NotificationMetrics::class.java),
        )

        val status = service.status(userId)

        assertTrue(status.hasActiveSubscription)
        assertEquals(2, status.activeSubscriptionCount)
        Mockito.verify(repository, Mockito.never()).findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(NotificationPushSubscriptionEntity::class.java))
    }

    @Test
    fun `encrypts subscription with random nonce and decrypts active subscription`() {
        val repository = Mockito.mock(NotificationPushSubscriptionRepository::class.java)
        val schedules = Mockito.mock(NotificationScheduleRepository::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val userId = UUID.randomUUID()
        Mockito.`when`(time.now()).thenReturn(Instant.parse("2026-07-15T00:00:00Z"))
        Mockito.`when`(repository.save(Mockito.any(NotificationPushSubscriptionEntity::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId)).thenReturn(emptyList())
        val service = PushSubscriptionService(repository, schedules, testProperties(), time, Mockito.mock(NotificationMetrics::class.java))
        val command = PushSubscriptionCommand("https://push.example.test/endpoint", "p256dh", "auth")

        service.subscribe(userId, command)
        val first = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        service.subscribe(userId, command)
        val second = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        Mockito.`when`(repository.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId)).thenReturn(listOf(second))

        assertNotEquals(first.endpointCiphertext, second.endpointCiphertext)
        assertEquals(command.endpoint, service.active(userId)?.endpoint)
        second.endpointCiphertext = "corrupt"
        assertEquals(null, service.active(userId))
        assertEquals(true, second.revokedAt != null)
    }

    @Test
    fun `corrupt newest subscription falls back to older usable subscription`() {
        val repository = Mockito.mock(NotificationPushSubscriptionRepository::class.java)
        val schedules = Mockito.mock(NotificationScheduleRepository::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val userId = UUID.randomUUID()
        Mockito.`when`(time.now()).thenReturn(Instant.parse("2026-07-15T00:00:00Z"))
        Mockito.`when`(repository.save(Mockito.any(NotificationPushSubscriptionEntity::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId)).thenReturn(emptyList())
        val service = PushSubscriptionService(repository, schedules, testProperties(), time, Mockito.mock(NotificationMetrics::class.java))
        service.subscribe(userId, PushSubscriptionCommand("https://push.example.test/older", "old-key", "old-auth"))
        val older = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        service.subscribe(userId, PushSubscriptionCommand("https://push.example.test/newer", "new-key", "new-auth"))
        val newer = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        newer.endpointCiphertext = "corrupt"
        Mockito.`when`(repository.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId)).thenReturn(listOf(newer, older))

        assertEquals("https://push.example.test/older", service.active(userId)?.endpoint)
        assertEquals(true, newer.revokedAt != null)
    }

    @Test
    fun `delivery keeps only the configured number of newest subscriptions active`() {
        val repository = Mockito.mock(NotificationPushSubscriptionRepository::class.java)
        val schedules = Mockito.mock(NotificationScheduleRepository::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val userId = UUID.randomUUID()
        Mockito.`when`(time.now()).thenReturn(Instant.parse("2026-07-15T00:00:00Z"))
        Mockito.`when`(repository.save(Mockito.any(NotificationPushSubscriptionEntity::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId)).thenReturn(emptyList())
        val service = PushSubscriptionService(
            repository,
            schedules,
            testProperties(maxSubscriptions = 1),
            time,
            Mockito.mock(NotificationMetrics::class.java),
        )
        service.subscribe(userId, PushSubscriptionCommand("https://push.example.test/older", "old-key", "old-auth"))
        val older = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        service.subscribe(userId, PushSubscriptionCommand("https://push.example.test/newer", "new-key", "new-auth"))
        val newer = Mockito.mockingDetails(repository).invocations.last { it.method.name == "save" }.arguments[0] as NotificationPushSubscriptionEntity
        Mockito.`when`(repository.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId)).thenReturn(listOf(newer, older))

        assertEquals(listOf("https://push.example.test/newer"), service.activeAll(userId).map { it.endpoint })
        assertTrue(older.revokedAt != null)
    }

    @Test
    fun `Push endpoint policy accepts configured providers only`() {
        val providers = setOf("fcm.googleapis.com", ".notify.windows.com")

        assertTrue(isAllowedWebPushEndpoint("https://fcm.googleapis.com/wp/example", providers))
        assertTrue(isAllowedWebPushEndpoint("https://wns2-bl2p.notify.windows.com/w/example", providers))
        assertFalse(isAllowedWebPushEndpoint("https://attacker.example/push", providers))
        assertFalse(isAllowedWebPushEndpoint("https://fcm.googleapis.com:8443/wp/example", providers))
        assertFalse(isAllowedWebPushEndpoint("https://user@fcm.googleapis.com/wp/example", providers))
    }

    private fun testProperties(maxSubscriptions: Int = 10) = NotificationProperties(
        webPushAllowedEndpointHosts = setOf("push.example.test"),
        webPushMaxSubscriptionsPerUser = maxSubscriptions,
    )
}
