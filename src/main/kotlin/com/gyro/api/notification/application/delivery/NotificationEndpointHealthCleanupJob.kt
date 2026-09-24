package com.gyro.api.notification.application.delivery

import com.gyro.api.notification.application.NotificationEndpointHealthCleanupService
import com.gyro.api.notification.config.NotificationProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NotificationEndpointHealthCleanupJob(
    private val cleanup: NotificationEndpointHealthCleanupService,
    private val properties: NotificationProperties,
) {
    @Scheduled(fixedDelayString = "\${app.notification.endpoint-health-cleanup-delay:1h}")
    fun run() {
        if (!properties.jobsEnabled) return
        runCatching { cleanup.clearExpiredDiagnostics() }
            .onSuccess { cleared -> if (cleared > 0) log.info("event=notification_endpoint_diagnostics_cleared count={}", cleared) }
            .onFailure { exception -> log.warn("event=notification_endpoint_diagnostics_cleanup_failed exception={}", exception::class.simpleName) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationEndpointHealthCleanupJob::class.java)
    }
}
