package com.gyro.api.notification.application.delivery

import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class TelegramLinkDataCleanupJobTest {
    private val cleanup = Mockito.mock(TelegramLinkDataCleanupService::class.java)
    private val metrics = Mockito.mock(NotificationMetrics::class.java)

    @Test
    fun `records deleted token and webhook update counts`() {
        Mockito.`when`(cleanup.purgeBatch()).thenReturn(TelegramLinkDataCleanupResult(1, 2, 3, 5))
        val job = TelegramLinkDataCleanupJob(cleanup, NotificationProperties(), metrics)

        job.run()

        Mockito.verify(metrics).telegramLinkDataCleaned(1, 2, 3, 5)
        Mockito.verify(metrics, Mockito.never()).telegramLinkDataCleanupFailed()
    }

    @Test
    fun `records cleanup failure`() {
        Mockito.`when`(cleanup.purgeBatch()).thenThrow(IllegalStateException("database unavailable"))
        val job = TelegramLinkDataCleanupJob(cleanup, NotificationProperties(), metrics)

        job.run()

        Mockito.verify(metrics).telegramLinkDataCleanupFailed()
        Mockito.verify(metrics, Mockito.never()).telegramLinkDataCleaned(
            Mockito.anyInt(),
            Mockito.anyInt(),
            Mockito.anyInt(),
            Mockito.anyInt(),
        )
    }
}
