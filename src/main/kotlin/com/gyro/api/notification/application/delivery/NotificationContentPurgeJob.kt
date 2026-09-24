package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.NotificationContentPurgeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationContentPurgeService(
    private val repository: NotificationContentPurgeRepository,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    @Transactional
    fun purgeBatch(): Int = repository.purgeDue(time.now(), properties.batchSize)
}

@Component
class NotificationContentPurgeJob(
    private val service: NotificationContentPurgeService,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
) {
    @Scheduled(fixedDelayString = "\${app.notification.content-purge-delay:1h}")
    fun run() {
        if (!properties.jobsEnabled) return
        runCatching { service.purgeBatch() }
            .onSuccess(metrics::contentPurged)
            .onFailure {
                metrics.contentPurgeFailed()
                log.warn("event=notification_content_purge outcome=failure reason={}", it::class.simpleName)
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationContentPurgeJob::class.java)
    }
}
