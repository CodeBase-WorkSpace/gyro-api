package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.persistence.NotificationEndpointHealthCleanupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationEndpointHealthCleanupService(
    private val endpoints: NotificationEndpointHealthCleanupRepository,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
) {
    @Transactional
    fun clearExpiredDiagnostics(): Int {
        return endpoints.clearExpiredDiagnostics(time.now(), properties.endpointHealthCleanupBatchSize)
    }
}
