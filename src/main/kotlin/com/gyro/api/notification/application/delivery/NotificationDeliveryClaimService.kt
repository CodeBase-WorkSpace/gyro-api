package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationDeliveryClaim
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryQueueRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class NotificationDeliveryClaimService(
    private val queueRepository: NotificationDeliveryQueueRepository,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
) {
    @Transactional
    fun recoverAndExpireDue(): Set<UUID> {
        val now = time.now()
        val recovered = queueRepository.recoverExpiredClaims(now)
        if (recovered > 0) metrics.claimRecovered(recovered)

        val expiredIntentIds = queueRepository.findExpiredIntentIds(now)
        if (expiredIntentIds.isNotEmpty()) {
            queueRepository.expireDueDeliveries(now, now.plus(properties.contentRetention))
        }

        return expiredIntentIds
    }

    @Transactional
    fun claimNext(): NotificationDeliveryClaim? {
        val now = time.now()
        val claimed = queueRepository.claimDueDeliveries(
            now = now,
            batchSize = 1,
            owner = properties.workerIdentity,
            claimExpiresAt = now.plus(properties.claimDuration),
            channels = properties.deliveryChannels.map { it.name }.toSet(),
        )
        if (claimed.isNotEmpty()) metrics.claimed(claimed.size)
        return claimed.singleOrNull()
    }
}
