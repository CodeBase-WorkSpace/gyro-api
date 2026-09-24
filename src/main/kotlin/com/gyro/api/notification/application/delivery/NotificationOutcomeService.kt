package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class NotificationOutcomeService(
    private val intents: NotificationIntentRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val time: TimeProvider,
    private val metrics: NotificationMetrics,
) {
    @Transactional
    fun recompute(intentId: UUID) {
        val intent = intents.findById(intentId).orElseThrow()
        val currentDeliveries = deliveries.findAllByIntentId(intentId)
        if (currentDeliveries.isEmpty()) return

        val nonTerminal = setOf(
            NotificationDeliveryStatus.PENDING,
            NotificationDeliveryStatus.CLAIMED,
            NotificationDeliveryStatus.RETRY_SCHEDULED,
        )
        val allTerminal = currentDeliveries.none { it.status in nonTerminal }
        val delivered = currentDeliveries.count { it.status == NotificationDeliveryStatus.DELIVERED }
        val nextStatus = when {
            !allTerminal -> NotificationIntentStatus.ROUTED
            delivered == currentDeliveries.size -> NotificationIntentStatus.COMPLETED
            delivered > 0 -> NotificationIntentStatus.PARTIALLY_COMPLETED
            currentDeliveries.all { it.status == NotificationDeliveryStatus.EXPIRED } -> NotificationIntentStatus.EXPIRED
            currentDeliveries.all { it.status == NotificationDeliveryStatus.SUPPRESSED } -> NotificationIntentStatus.SUPPRESSED
            else -> NotificationIntentStatus.FAILED
        }
        val reason = if (nextStatus == NotificationIntentStatus.COMPLETED) null else currentDeliveries.firstNotNullOfOrNull { it.reason }
        if (intent.status == nextStatus && intent.reason == reason) return

        val terminal = nextStatus !in setOf(NotificationIntentStatus.PENDING, NotificationIntentStatus.ROUTED)
        val now = time.now()
        intent.status = nextStatus
        intent.reason = reason
        intent.terminalAt = if (terminal) now else null
        intent.updatedAt = now
        intents.save(intent)
        if (terminal) metrics.intentOutcome(intent.type, nextStatus, reason)
    }
}
