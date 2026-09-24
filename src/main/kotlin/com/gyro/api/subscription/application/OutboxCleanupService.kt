package com.gyro.api.subscription.application

import com.gyro.api.common.outbox.OutboxEventConsumptionRepository
import com.gyro.api.common.outbox.OutboxEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class OutboxCleanupService(private val events: OutboxEventRepository, private val consumptions: OutboxEventConsumptionRepository) {
    @Transactional
    fun cleanup(threshold: Instant): Int {
        consumptions.deleteForPublishedBefore(threshold)
        return events.deletePublishedBefore(threshold)
    }
}
