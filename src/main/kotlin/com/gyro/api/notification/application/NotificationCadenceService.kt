package com.gyro.api.notification.application

import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.persistence.NotificationCadenceRepository
import com.gyro.api.user.application.UserTimezoneResolver
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class NotificationAnnouncementPriority {
    INSIGHT,
    MILESTONE,
    WEEKLY_REVIEW,
    RECOVERY,
}

data class AnnouncementBudgetDecision(
    val allowed: Boolean,
    val priority: NotificationAnnouncementPriority?,
)

/**
 * Applies the interruption budget only to unsolicited announcements.
 *
 * Reminder categories are subscriptions scheduled by the user and transactional
 * messages report consequences of the user's own actions, so neither consumes this
 * budget. The type mapping is deliberately exhaustive for today's announcement
 * types; adding a coaching notification requires choosing its priority here.
 */
@Service
class NotificationCadenceService(
    private val repository: NotificationCadenceRepository,
    private val timezoneResolver: UserTimezoneResolver,
) {
    fun announcementBudget(
        userId: UUID,
        type: NotificationType,
        category: NotificationCategory,
        scheduledAt: Instant,
    ): AnnouncementBudgetDecision {
        if (category != NotificationCategory.OPTIONAL_ANNOUNCEMENTS) {
            return AnnouncementBudgetDecision(allowed = true, priority = null)
        }
        val priority = announcementPriority(type)
        val zone = timezoneResolver.resolve(userId)
        val localDate = scheduledAt.atZone(zone).toLocalDate()
        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()
        val weekStartDate = localDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        val weekStart = weekStartDate.atStartOfDay(zone).toInstant()
        val weekEnd = weekStartDate.plusWeeks(1).atStartOfDay(zone).toInstant()

        repository.lockUser(userId)
        val allowed = repository.countAcceptedAnnouncements(userId, dayStart, dayEnd) < DAILY_LIMIT &&
            repository.countAcceptedAnnouncements(userId, weekStart, weekEnd) < WEEKLY_LIMIT
        return AnnouncementBudgetDecision(allowed = allowed, priority = priority)
    }

    private fun announcementPriority(type: NotificationType): NotificationAnnouncementPriority =
        when (type) {
            NotificationType.RECALIBRATION_SUGGESTION,
            NotificationType.ADMIN_ANNOUNCEMENT,
            -> NotificationAnnouncementPriority.INSIGHT

            NotificationType.COACH_DATA_NUDGE -> NotificationAnnouncementPriority.RECOVERY

            else -> error("Notification type $type is not registered as an optional announcement.")
        }

    private companion object {
        const val DAILY_LIMIT = 1L
        const val WEEKLY_LIMIT = 3L
    }
}
