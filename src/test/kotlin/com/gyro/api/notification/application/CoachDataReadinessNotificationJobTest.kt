package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.CoachDataNudgeRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CoachDataReadinessNotificationJobTest {
    private val repository = Mockito.mock(CoachDataNudgeRepository::class.java)
    private val service = Mockito.mock(CoachDataReadinessNotificationService::class.java)
    private val metrics = Mockito.mock(NotificationMetrics::class.java)
    private val time = Mockito.mock(TimeProvider::class.java)

    @Test
    fun `job processes every local hour candidate page`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val startedAt = Instant.parse("2026-07-31T14:30:00Z")
        val finishedAt = startedAt.plusSeconds(2)
        val properties = NotificationProperties(
            coachDataNudgeEnabled = true,
            coachDataNudgeBatchSize = 2,
            coachDataNudgeLocalHour = 18,
        )
        val job = CoachDataReadinessNotificationJob(repository, service, properties, metrics, time)
        Mockito.`when`(time.now()).thenReturn(startedAt, finishedAt)
        Mockito.`when`(repository.findEligiblePageAtLocalHour(null, 2, 18, startedAt))
            .thenReturn(listOf(first, second))
        Mockito.`when`(repository.findEligiblePageAtLocalHour(second, 2, 18, startedAt)).thenReturn(emptyList())
        Mockito.`when`(service.evaluate(first)).thenReturn(CoachDataNudgeOutcome.CREATED)
        Mockito.`when`(service.evaluate(second)).thenReturn(CoachDataNudgeOutcome.NO_ACTION_TODAY)

        job.run()

        Mockito.verify(service).evaluate(first)
        Mockito.verify(service).evaluate(second)
        Mockito.verify(metrics).coachDataNudgeJobCompleted(2, 1, Duration.ofSeconds(2))
    }

    @Test
    fun `disabled nudge job does not query candidates`() {
        val properties = NotificationProperties(coachDataNudgeEnabled = false)
        val job = CoachDataReadinessNotificationJob(repository, service, properties, metrics, time)

        job.run()

        Mockito.verifyNoInteractions(repository, service, metrics, time)
    }
}
