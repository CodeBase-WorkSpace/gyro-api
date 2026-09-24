package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleClaim
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleQueueRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class FoodReminderEvaluatorTest {
    @Test
    fun `failure recording failure does not abort remaining claimed schedules`() {
        val queue = Mockito.mock(NotificationScheduleQueueRepository::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val first = NotificationScheduleClaim(UUID.randomUUID(), "test:food-schedules", UUID.randomUUID())
        val second = NotificationScheduleClaim(UUID.randomUUID(), "test:food-schedules", UUID.randomUUID())
        var secondProcessed = false
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(queue.claimDue(now, "test:food-schedules", 50, now.plusSeconds(60))).thenReturn(listOf(first, second))
        val processor = object : FoodReminderScheduleProcessor(
            Mockito.mock(NotificationScheduleRepository::class.java),
            Mockito.mock(UserProfileRepository::class.java),
            Mockito.mock(PushSubscriptionService::class.java),
            Mockito.mock(NotificationPreferenceService::class.java),
            Mockito.mock(NotificationService::class.java),
            Mockito.mock(JdbcTemplate::class.java),
            NotificationProperties(),
        ) {
            override fun process(claim: NotificationScheduleClaim, now: Instant) {
                if (claim == first) throw IllegalStateException("poison")
                if (claim == second) secondProcessed = true
            }

            override fun recordFailure(claim: NotificationScheduleClaim, now: Instant, failure: Throwable) {
                throw IllegalStateException("recording unavailable")
            }
        }
        val evaluator = FoodReminderEvaluator(queue, processor, NotificationProperties(workerIdentity = "test"), time)

        assertEquals(1, evaluator.evaluateDueSchedules())
        assertEquals(true, secondProcessed)
    }
}
