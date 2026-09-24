package com.gyro.api.notification.application

import com.gyro.api.common.error.AdminAnnouncementAudienceTooLargeException
import com.gyro.api.common.error.AdminAnnouncementContentConflictException
import com.gyro.api.common.error.InvalidAdminAnnouncementException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.domain.UserStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.notification.infrastructure.persistence.AdminAnnouncementEntity
import com.gyro.api.notification.infrastructure.persistence.AdminAnnouncementRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

// Mockito's any() returns null, which Kotlin's null checks reject for non-null parameters;
// routing it through a generic helper keeps the compiler from inserting the check.
private fun <T> anyRequest(): T = Mockito.any()

class AdminAnnouncementServiceTest {
    private val announcements = Mockito.mock(AdminAnnouncementRepository::class.java)
    private val subscriptions = Mockito.mock(PushSubscriptionService::class.java)
    private val notifications = Mockito.mock(NotificationService::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val time = Mockito.mock(TimeProvider::class.java)
    private val service = AdminAnnouncementService(
        announcements,
        subscriptions,
        notifications,
        users,
        NotificationProperties(telegramEnabled = true),
        time,
    )
    private val now = Instant.parse("2026-07-16T00:00:00Z")
    private val operatorId = UUID.randomUUID()
    private val announcementId = UUID.randomUUID()

    private fun stubNewAnnouncement() {
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(Optional.empty())
        Mockito.`when`(announcements.saveAndFlush(anyRequest<AdminAnnouncementEntity>()))
            .thenAnswer { invocation -> invocation.getArgument<AdminAnnouncementEntity>(0) }
    }

    private fun stubActiveUser(userId: UUID) {
        Mockito.`when`(users.findById(userId)).thenReturn(
            Optional.of(GyroUser(id = userId, status = UserStatus.ACTIVE)),
        )
    }

    private fun persistedAnnouncement(
        title: String = "عنوان",
        body: String = "متن اعلان",
        webPush: Boolean = true,
        telegramChannel: Boolean = false,
        recipientUserId: UUID? = null,
    ) = AdminAnnouncementEntity(
        id = announcementId,
        title = title,
        body = body,
        contentHash = AdminAnnouncementService.contentHash(title, body),
        targetWebPush = webPush,
        targetTelegramChannel = telegramChannel,
        recipientUserId = recipientUserId,
        createdBy = operatorId,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `fans out one idempotent intent per subscribed user from persisted content`() {
        val users = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val requests = mutableListOf<NotificationRequest>()
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(subscriptions.userIdsWithActiveSubscription()).thenReturn(users)
        Mockito.`when`(notifications.createWithOutcome(anyRequest())).thenAnswer { invocation ->
            requests += invocation.getArgument<NotificationRequest>(0)
            NotificationCreateOutcome(UUID.randomUUID(), created = true)
        }

        val result = service.send(operatorId, announcementId, "عنوان", "متن اعلان")

        assertEquals(3, result.recipientsTargeted)
        assertEquals(3, result.intentsCreated)
        assertEquals(0, result.intentsExisting)
        assertEquals(0, result.failures)
        assertEquals(3, requests.size)
        assertEquals(users.toSet(), requests.map { it.recipientUserId }.toSet())
        requests.forEach { request ->
            assertEquals(NotificationType.ADMIN_ANNOUNCEMENT, request.type)
            assertEquals("admin-announcement:$announcementId:${request.recipientUserId}", request.idempotencyKey)
            assertEquals("ADMIN_ANNOUNCEMENT", request.sourceType)
            assertEquals("operator:$operatorId", request.sourceReference)
            assertEquals(TemplateVariableValue.Text("عنوان"), request.templateData["title"])
            assertEquals(TemplateVariableValue.Text("متن اعلان"), request.templateData["body"])
            assertEquals(now.plusSeconds(24 * 3_600), request.expiresAt)
        }
    }

    @Test
    fun `targets one selected user only when that user has active push`() {
        val recipient = UUID.randomUUID()
        stubActiveUser(recipient)
        val requests = mutableListOf<NotificationRequest>()
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(subscriptions.hasActiveSubscription(recipient)).thenReturn(true)
        Mockito.`when`(notifications.createWithOutcome(anyRequest())).thenAnswer { invocation ->
            requests += invocation.getArgument<NotificationRequest>(0)
            NotificationCreateOutcome(UUID.randomUUID(), created = true)
        }

        val result = service.send(
            operatorId,
            announcementId,
            "عنوان",
            "متن اعلان",
            recipientUserId = recipient,
        )

        assertEquals(1, result.recipientsTargeted)
        assertEquals(listOf(recipient), requests.map(NotificationRequest::recipientUserId))
        Mockito.verify(subscriptions, Mockito.never()).userIdsWithActiveSubscription()
    }

    @Test
    fun `reports no recipient when selected user has no active push endpoint`() {
        val recipient = UUID.randomUUID()
        stubActiveUser(recipient)
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(subscriptions.hasActiveSubscription(recipient)).thenReturn(false)

        val result = service.send(
            operatorId,
            announcementId,
            "عنوان",
            "متن اعلان",
            recipientUserId = recipient,
        )

        assertEquals(0, result.recipientsTargeted)
        assertEquals(0, result.intentsCreated)
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `resubmitting the same id and content resumes and reports existing intents separately`() {
        val users = listOf(UUID.randomUUID(), UUID.randomUUID())
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(Optional.of(persistedAnnouncement()))
        Mockito.`when`(subscriptions.userIdsWithActiveSubscription()).thenReturn(users)
        Mockito.`when`(notifications.createWithOutcome(anyRequest()))
            .thenReturn(NotificationCreateOutcome(UUID.randomUUID(), created = false))
            .thenReturn(NotificationCreateOutcome(UUID.randomUUID(), created = true))

        val result = service.send(operatorId, announcementId, "عنوان", "متن اعلان")

        assertEquals(1, result.intentsCreated)
        assertEquals(1, result.intentsExisting)
        assertEquals(0, result.failures)
        Mockito.verify(announcements, Mockito.never()).saveAndFlush(anyRequest<AdminAnnouncementEntity>())
    }

    @Test
    fun `resubmitting the same id with a changed title is rejected before any fan-out`() {
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(Optional.of(persistedAnnouncement()))

        assertThrows<AdminAnnouncementContentConflictException> {
            service.send(operatorId, announcementId, "عنوان دیگر", "متن اعلان")
        }
        Mockito.verifyNoInteractions(subscriptions, notifications)
    }

    @Test
    fun `resubmitting the same id with a changed body is rejected before any fan-out`() {
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(Optional.of(persistedAnnouncement()))

        assertThrows<AdminAnnouncementContentConflictException> {
            service.send(operatorId, announcementId, "عنوان", "متن دیگر")
        }
        Mockito.verifyNoInteractions(subscriptions, notifications)
    }

    @Test
    fun `resubmitting the same id with changed targets is rejected before any fan-out`() {
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(Optional.of(persistedAnnouncement()))

        assertThrows<AdminAnnouncementContentConflictException> {
            service.send(
                operatorId,
                announcementId,
                "عنوان",
                "متن اعلان",
                AdminAnnouncementTargets(webPush = true, telegramChannel = true),
            )
        }
        Mockito.verifyNoInteractions(subscriptions, notifications)
    }

    @Test
    fun `resubmitting the same id for another recipient is rejected before any fan-out`() {
        val originalRecipient = UUID.randomUUID()
        val changedRecipient = UUID.randomUUID()
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(
            Optional.of(persistedAnnouncement(recipientUserId = originalRecipient)),
        )

        assertThrows<AdminAnnouncementContentConflictException> {
            service.send(
                operatorId,
                announcementId,
                "عنوان",
                "متن اعلان",
                recipientUserId = changedRecipient,
            )
        }
        Mockito.verifyNoInteractions(subscriptions, notifications, users)
    }

    @Test
    fun `accepted targeted announcement remains retryable after recipient is deactivated`() {
        val recipient = UUID.randomUUID()
        Mockito.`when`(announcements.findById(announcementId)).thenReturn(
            Optional.of(persistedAnnouncement(recipientUserId = recipient)),
        )
        Mockito.`when`(users.findById(recipient)).thenReturn(
            Optional.of(GyroUser(id = recipient, status = UserStatus.DEACTIVATED)),
        )
        Mockito.`when`(subscriptions.hasActiveSubscription(recipient)).thenReturn(false)

        val result = service.send(
            operatorId,
            announcementId,
            "عنوان",
            "متن اعلان",
            recipientUserId = recipient,
        )

        assertEquals(0, result.recipientsTargeted)
        Mockito.verifyNoInteractions(users, notifications)
    }

    @Test
    fun `unknown selected user is rejected before announcement persistence`() {
        val recipient = UUID.randomUUID()
        Mockito.`when`(users.findById(recipient)).thenReturn(Optional.empty())

        val error = assertThrows<ResourceNotFoundException> {
            service.send(operatorId, announcementId, "عنوان", "متن", recipientUserId = recipient)
        }
        assertEquals(HttpStatus.NOT_FOUND, error.status)
        Mockito.verify(announcements, Mockito.never()).saveAndFlush(anyRequest<AdminAnnouncementEntity>())
        Mockito.verifyNoInteractions(subscriptions, notifications)
    }

    @Test
    fun `deactivated selected user is rejected before announcement persistence`() {
        val recipient = UUID.randomUUID()
        Mockito.`when`(users.findById(recipient)).thenReturn(
            Optional.of(GyroUser(id = recipient, status = UserStatus.DEACTIVATED)),
        )

        val error = assertThrows<InvalidAdminAnnouncementException> {
            service.send(operatorId, announcementId, "عنوان", "متن", recipientUserId = recipient)
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.status)
        Mockito.verify(announcements, Mockito.never()).saveAndFlush(anyRequest<AdminAnnouncementEntity>())
        Mockito.verifyNoInteractions(subscriptions, notifications)
    }

    @Test
    fun `invalid target combinations return validation errors before persistence`() {
        assertThrows<InvalidAdminAnnouncementException> {
            service.send(
                operatorId,
                announcementId,
                "عنوان",
                "متن",
                AdminAnnouncementTargets(webPush = false, telegramChannel = false),
            )
        }
        assertThrows<InvalidAdminAnnouncementException> {
            service.send(
                operatorId,
                announcementId,
                "عنوان",
                "متن",
                AdminAnnouncementTargets(webPush = false, telegramChannel = true),
                recipientUserId = UUID.randomUUID(),
            )
        }
        Mockito.verifyNoInteractions(announcements, subscriptions, notifications, users)
    }

    @Test
    fun `a per-user failure is counted without aborting the fan-out`() {
        val users = listOf(UUID.randomUUID(), UUID.randomUUID())
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(subscriptions.userIdsWithActiveSubscription()).thenReturn(users)
        Mockito.`when`(notifications.createWithOutcome(anyRequest()))
            .thenThrow(IllegalStateException("poison"))
            .thenReturn(NotificationCreateOutcome(UUID.randomUUID(), created = true))

        val result = service.send(operatorId, announcementId, "عنوان", "متن")

        assertEquals(2, result.recipientsTargeted)
        assertEquals(1, result.intentsCreated)
        assertEquals(1, result.failures)
    }

    @Test
    fun `telegram channel target queues one mandatory channel post`() {
        val requests = mutableListOf<NotificationRequest>()
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(notifications.createWithOutcome(anyRequest())).thenAnswer { invocation ->
            requests += invocation.getArgument<NotificationRequest>(0)
            NotificationCreateOutcome(UUID.randomUUID(), created = true)
        }

        val result = service.send(
            operatorId,
            announcementId,
            "عنوان",
            "متن",
            AdminAnnouncementTargets(webPush = false, telegramChannel = true),
        )

        assertEquals(0, result.recipientsTargeted)
        assertEquals(true, result.telegramQueued)
        assertEquals(false, result.telegramAlreadyQueued)
        Mockito.verifyNoInteractions(subscriptions)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(NotificationType.TELEGRAM_CHANNEL_POST, request.type)
        assertEquals(operatorId, request.recipientUserId)
        assertEquals("telegram-channel-post:$announcementId", request.idempotencyKey)
    }

    @Test
    fun `Telegram master switch rejects channel announcements before persistence`() {
        val disabledService = AdminAnnouncementService(
            announcements,
            subscriptions,
            notifications,
            users,
            NotificationProperties(telegramEnabled = false),
            time,
        )

        assertThrows<InvalidAdminAnnouncementException> {
            disabledService.send(
                operatorId,
                announcementId,
                "عنوان",
                "متن",
                AdminAnnouncementTargets(webPush = false, telegramChannel = true),
            )
        }
        Mockito.verifyNoInteractions(announcements, subscriptions, notifications)
    }

    @Test
    fun `an existing telegram post is reported as already queued`() {
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(announcements.findById(announcementId))
            .thenReturn(Optional.of(persistedAnnouncement(webPush = false, telegramChannel = true)))
        Mockito.`when`(notifications.createWithOutcome(anyRequest()))
            .thenReturn(NotificationCreateOutcome(UUID.randomUUID(), created = false))

        val result = service.send(
            operatorId,
            announcementId,
            "عنوان",
            "متن اعلان",
            AdminAnnouncementTargets(webPush = false, telegramChannel = true),
        )

        assertEquals(false, result.telegramQueued)
        assertEquals(true, result.telegramAlreadyQueued)
        assertEquals(0, result.failures)
    }

    @Test
    fun `an audience above the synchronous fan-out bound is rejected`() {
        Mockito.`when`(time.now()).thenReturn(now)
        stubNewAnnouncement()
        Mockito.`when`(subscriptions.userIdsWithActiveSubscription())
            .thenReturn(List(AdminAnnouncementService.MAX_RECIPIENTS + 1) { UUID.randomUUID() })

        val error = assertThrows<AdminAnnouncementAudienceTooLargeException> {
            service.send(operatorId, announcementId, "عنوان", "متن")
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        assertEquals((AdminAnnouncementService.MAX_RECIPIENTS + 1).toString(), error.metadata["audienceSize"])
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `rejects blank or oversized content before targeting users`() {
        assertThrows<InvalidAdminAnnouncementException> { service.send(operatorId, announcementId, "", "متن") }
        assertThrows<InvalidAdminAnnouncementException> { service.send(operatorId, announcementId, "ع".repeat(81), "متن") }
        assertThrows<InvalidAdminAnnouncementException> { service.send(operatorId, announcementId, "عنوان", "") }
        assertThrows<InvalidAdminAnnouncementException> { service.send(operatorId, announcementId, "عنوان", "م".repeat(501)) }
        Mockito.verifyNoInteractions(announcements, subscriptions, notifications, users)
    }
}
