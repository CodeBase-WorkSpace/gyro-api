package com.gyro.api.notification.infrastructure.observability

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryQueueRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationContentPurgeRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.TransactionTimedOutException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

@Component
class NotificationMetrics(
    private val registries: ObjectProvider<MeterRegistry>,
    private val queueRepository: NotificationDeliveryQueueRepository,
    private val purgeRepository: NotificationContentPurgeRepository,
    private val intents: NotificationIntentRepository,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
) {
    private val dueDeliveries = AtomicLong()
    private val oldestDueAgeSeconds = AtomicLong()
    private val expiredActiveDeliveries = AtomicLong()
    private val overdueContentPurges = AtomicLong()
    private val oldestOverduePurgeAgeSeconds = AtomicLong()
    private val failedMandatoryReceipts = AtomicLong()
    private val oldestFailedMandatoryReceiptAgeSeconds = AtomicLong()
    private val lastBatchDurationNanos = AtomicLong()
    private val lastBatchSize = AtomicLong()

    @PostConstruct
    fun registerGauges() {
        registries.ifAvailable { registry ->
            Gauge.builder("gyro.notifications.queue.due", dueDeliveries) { it.get().toDouble() }.register(registry)
            Gauge.builder("gyro.notifications.queue.oldest_due_age_seconds", oldestDueAgeSeconds) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.queue.expired_active", expiredActiveDeliveries) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.purge.overdue", overdueContentPurges) { it.get().toDouble() }.register(registry)
            Gauge.builder("gyro.notifications.purge.oldest_overdue_age_seconds", oldestOverduePurgeAgeSeconds) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.mandatory_receipts.failed", failedMandatoryReceipts) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.mandatory_receipts.oldest_failure_age_seconds", oldestFailedMandatoryReceiptAgeSeconds) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.worker.batch.last_duration_seconds", lastBatchDurationNanos) {
                it.get().toDouble() / 1_000_000_000.0
            }.register(registry)
            Gauge.builder("gyro.notifications.worker.batch.last_size", lastBatchSize) { it.get().toDouble() }
                .register(registry)
            Gauge.builder("gyro.notifications.worker.poll_delay_seconds", properties) {
                it.pollDelay.toMillis().toDouble() / 1_000.0
            }.register(registry)
            Gauge.builder("gyro.notifications.worker.claim_duration_seconds", properties) {
                it.claimDuration.toMillis().toDouble() / 1_000.0
            }.register(registry)
        }
        refreshQueue()
    }

    @Scheduled(fixedDelayString = "\${app.notification.metrics-refresh-delay:60s}")
    fun refreshQueue() = runCatching {
        val now = time.now()
        val snapshot = queueRepository.snapshot(now)
        val purgeSnapshot = purgeRepository.snapshot(now)
        dueDeliveries.set(snapshot.dueCount)
        oldestDueAgeSeconds.set(
            snapshot.oldestDueAt
                ?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                ?: 0,
        )
        expiredActiveDeliveries.set(snapshot.expiredActiveCount)
        overdueContentPurges.set(purgeSnapshot.overdueCount)
        oldestOverduePurgeAgeSeconds.set(
            purgeSnapshot.oldestOverdueAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0,
        )
        failedMandatoryReceipts.set(
            intents.countByTypeAndStatus(NotificationType.PAYMENT_VERIFIED, NotificationIntentStatus.FAILED),
        )
        oldestFailedMandatoryReceiptAgeSeconds.set(
            intents.findFirstByTypeAndStatusOrderByTerminalAtAsc(
                NotificationType.PAYMENT_VERIFIED,
                NotificationIntentStatus.FAILED,
            )?.terminalAt?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0,
        )
    }.onFailure { notificationMetricsRefreshFailed() }

    fun intentCreated(type: NotificationType, category: NotificationCategory) = increment(
        "gyro.notifications.intents",
        "type", type.name,
        "category", category.name,
        "outcome", "created",
    )

    fun intentOutcome(type: NotificationType, status: NotificationIntentStatus, reason: NotificationReason?) = increment(
        "gyro.notifications.intent.outcomes",
        "type", type.name,
        "status", status.name,
        "reason", reason?.name ?: "NONE",
    )

    fun deliveryCreated(type: NotificationType, channel: NotificationChannel, adapter: String) = increment(
        "gyro.notifications.deliveries",
        "type", type.name,
        "channel", channel.name,
        "adapter", adapter,
        "outcome", "created",
    )

    fun deliveryOutcome(
        type: NotificationType,
        channel: NotificationChannel,
        adapter: String,
        status: NotificationDeliveryStatus,
        reason: NotificationReason?,
    ) = increment(
        "gyro.notifications.delivery.outcomes",
        "type", type.name,
        "channel", channel.name,
        "adapter", adapter,
        "status", status.name,
        "reason", reason?.name ?: "NONE",
    )

    fun coachDataNudgeEvaluation(outcome: String, reason: String) = increment(
        "gyro.notifications.coach_data_nudge.evaluations",
        "outcome", outcome,
        "reason", reason,
    )

    fun coachDataNudgeJobCompleted(candidates: Int, created: Int, duration: Duration) {
        increment(
            "gyro.notifications.coach_data_nudge.job",
            "outcome", "completed",
            "result", "candidates",
            amount = candidates.toDouble(),
        )
        increment(
            "gyro.notifications.coach_data_nudge.job",
            "outcome", "completed",
            "result", "created",
            amount = created.toDouble(),
        )
        increment(
            "gyro.notifications.coach_data_nudge.job.duration_seconds",
            amount = duration.toNanos().coerceAtLeast(0).toDouble() / 1_000_000_000.0,
        )
    }

    fun attempt(adapter: String, outcome: AdapterOutcome, classification: AdapterClassification) = increment(
        "gyro.notifications.attempts",
        "adapter", adapter,
        "outcome", outcome.name,
        "classification", classification.name,
    )

    fun adapterException(adapter: String, operation: AdapterOperation, exception: Throwable) = increment(
        "gyro.notifications.adapter.exceptions",
        "adapter", adapter,
        "operation", operation.name,
        "exception", exception.metricKind(),
    )

    fun processingFailure(adapter: String, outcome: String, exception: Throwable) = increment(
        "gyro.notifications.processing.failures",
        "adapter", adapter,
        "outcome", outcome,
        "exception", exception.metricKind(),
    )

    fun claimed(count: Int) = increment("gyro.notifications.worker.claims", "outcome", "claimed", amount = count.toDouble())

    fun claimRecovered(count: Int) = increment("gyro.notifications.worker.claims", "outcome", "recovered", amount = count.toDouble())

    fun deliveryBatchCompleted(processed: Int, duration: Duration) {
        lastBatchSize.set(processed.toLong())
        lastBatchDurationNanos.set(duration.toNanos().coerceAtLeast(0))
    }

    fun contentPurged(count: Int) = increment("gyro.notifications.purge", "outcome", "purged", amount = count.toDouble())

    fun contentPurgeFailed() = increment("gyro.notifications.purge", "outcome", "failed")

    fun telegramLinkDataCleaned(
        legacyTokens: Int,
        unclaimedCodes: Int,
        consumedCodes: Int,
        webhookUpdates: Int,
    ) {
        increment(
            "gyro.notifications.telegram.link.cleanup.records",
            "kind", "legacy_tokens",
            amount = legacyTokens.toDouble(),
        )
        increment(
            "gyro.notifications.telegram.link.cleanup.records",
            "kind", "unclaimed_codes",
            amount = unclaimedCodes.toDouble(),
        )
        increment(
            "gyro.notifications.telegram.link.cleanup.records",
            "kind", "consumed_codes",
            amount = consumedCodes.toDouble(),
        )
        increment(
            "gyro.notifications.telegram.link.cleanup.records",
            "kind", "webhook_updates",
            amount = webhookUpdates.toDouble(),
        )
    }

    fun telegramLinkDataCleanupFailed() = increment("gyro.notifications.telegram.link.cleanup.failures")

    fun telegramLinkConfirmation(outcome: String) = increment(
        "gyro.notifications.telegram.link.confirmations",
        "outcome", outcome.lowercase(),
    )

    private fun notificationMetricsRefreshFailed() = increment("gyro.notifications.metrics.refresh.failures")

    fun pushSubscriptionUnusable(reason: String, keyVersion: String) = increment(
        "gyro.notifications.push_subscriptions.unusable",
        "reason", reason,
        "key_version", keyVersion.take(32),
    )

    fun pushDevicesTargeted(count: Int) = increment(
        "gyro.notifications.push.devices.targeted",
        amount = count.toDouble(),
    )

    fun pushDeviceOutcome(outcome: AdapterOutcome) = increment(
        "gyro.notifications.push.devices.${outcome.deviceMetricName()}",
    )

    private fun increment(name: String, vararg tags: String, amount: Double = 1.0) {
        registries.ifAvailable { registry -> Counter.builder(name).tags(*tags).register(registry).increment(amount) }
    }

    private fun Throwable.metricKind(): String {
        val causes = generateSequence(this) { it.cause }.toList()
        return when {
            causes.any { it is TransactionTimedOutException || it is TimeoutException } -> "TIMEOUT"
            causes.any { it is TransientDataAccessException || it is DataAccessResourceFailureException } -> "TRANSIENT_DATA_ACCESS"
            causes.any { it is IOException } -> "IO"
            causes.any { it is IllegalArgumentException } -> "ILLEGAL_ARGUMENT"
            causes.any { it is IllegalStateException } -> "ILLEGAL_STATE"
            else -> "OTHER"
        }
    }

    private fun AdapterOutcome.deviceMetricName(): String = when (this) {
        AdapterOutcome.SUCCESS -> "succeeded"
        AdapterOutcome.INVALID_ENDPOINT -> "invalid"
        AdapterOutcome.UNKNOWN_AFTER_SEND -> "unknown"
        else -> "failed"
    }
}
