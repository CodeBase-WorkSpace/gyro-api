package com.gyro.api.notification.application

import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.persistence.NotificationCadenceRepository
import com.gyro.api.user.application.UserTimezoneResolver
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class NotificationCadenceServiceTest {
    private val repository = Mockito.mock(NotificationCadenceRepository::class.java)
    private val timezones = Mockito.mock(UserTimezoneResolver::class.java)
    private val service = NotificationCadenceService(repository, timezones)
    private val userId = UUID.randomUUID()

    @Test
    fun `a daily weight reminder does not consume the announcement budget`() {
        val decision = service.announcementBudget(
            userId = userId,
            type = NotificationType.WEIGHT_REMINDER,
            category = NotificationCategory.OPTIONAL_WEIGHT_LOGGING,
            scheduledAt = Instant.parse("2026-07-23T10:00:00Z"),
        )

        assertEquals(true, decision.allowed)
        assertEquals(null, decision.priority)
        Mockito.verifyNoInteractions(repository, timezones)
    }

    @Test
    fun `a second announcement on the same local day is dropped`() {
        val scheduledAt = Instant.parse("2026-07-23T21:00:00Z")
        Mockito.`when`(timezones.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(
            repository.countAcceptedAnnouncements(
                userId,
                Instant.parse("2026-07-23T20:30:00Z"),
                Instant.parse("2026-07-24T20:30:00Z"),
            )
        ).thenReturn(1)

        val decision = service.announcementBudget(
            userId = userId,
            type = NotificationType.RECALIBRATION_SUGGESTION,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            scheduledAt = scheduledAt,
        )

        assertEquals(false, decision.allowed)
        assertEquals(NotificationAnnouncementPriority.INSIGHT, decision.priority)
        Mockito.verify(repository).lockUser(userId)
    }

    @Test
    fun `announcement week starts saturday in the user timezone`() {
        val scheduledAt = Instant.parse("2026-07-29T10:00:00Z")
        Mockito.`when`(timezones.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(
            repository.countAcceptedAnnouncements(
                userId,
                Instant.parse("2026-07-28T20:30:00Z"),
                Instant.parse("2026-07-29T20:30:00Z"),
            )
        ).thenReturn(0)
        Mockito.`when`(
            repository.countAcceptedAnnouncements(
                userId,
                Instant.parse("2026-07-24T20:30:00Z"),
                Instant.parse("2026-07-31T20:30:00Z"),
            )
        ).thenReturn(2)

        val decision = service.announcementBudget(
            userId = userId,
            type = NotificationType.ADMIN_ANNOUNCEMENT,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            scheduledAt = scheduledAt,
        )

        assertEquals(true, decision.allowed)
    }

    @Test
    fun `coach data nudge uses recovery priority and the announcement budget`() {
        val scheduledAt = Instant.parse("2026-07-29T10:00:00Z")
        Mockito.`when`(timezones.resolve(userId)).thenReturn(ZoneId.of("Asia/Tehran"))
        Mockito.`when`(
            repository.countAcceptedAnnouncements(
                userId,
                Instant.parse("2026-07-28T20:30:00Z"),
                Instant.parse("2026-07-29T20:30:00Z"),
            ),
        ).thenReturn(0)
        Mockito.`when`(
            repository.countAcceptedAnnouncements(
                userId,
                Instant.parse("2026-07-24T20:30:00Z"),
                Instant.parse("2026-07-31T20:30:00Z"),
            ),
        ).thenReturn(0)

        val decision = service.announcementBudget(
            userId = userId,
            type = NotificationType.COACH_DATA_NUDGE,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
            scheduledAt = scheduledAt,
        )

        assertEquals(true, decision.allowed)
        assertEquals(NotificationAnnouncementPriority.RECOVERY, decision.priority)
    }
}
