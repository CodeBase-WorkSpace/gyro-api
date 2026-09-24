package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.policy.NotificationPolicyRegistry
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.NotificationAttemptContext
import com.gyro.api.notification.infrastructure.persistence.NotificationAttemptStore
import com.gyro.api.notification.infrastructure.persistence.NotificationFinalization
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

class StaleNotificationClaimException(deliveryId: UUID) :
    IllegalStateException("Notification delivery $deliveryId is no longer owned by this claim")

class NotificationPostSendProcessingException(cause: Exception) :
    RuntimeException("Notification post-send persistence failed", cause)

@Service
class NotificationAttemptService(
    adapters: List<NotificationChannelAdapter>,
    private val store: NotificationAttemptStore,
    private val policies: NotificationPolicyRegistry,
    private val outcomes: NotificationOutcomeService,
    private val time: TimeProvider,
    private val properties: NotificationProperties,
    private val transactions: TransactionTemplate,
    private val metrics: NotificationMetrics,
) {
    private val adaptersByKey = adapters.associateBy(NotificationChannelAdapter::adapterKey).also {
        require(it.size == adapters.size) { "Notification adapter keys must be unique" }
    }

    fun attempt(claim: NotificationDeliveryClaim) {
        val context = transactions.execute { store.begin(claim, time.now()) }
            ?: throw StaleNotificationClaimException(claim.deliveryId)
        val adapter = requireNotNull(adaptersByKey[context.adapterKey]) {
            "No notification adapter is registered for ${context.adapterKey}"
        }
        val notification = context.renderedNotification()
        val result = invokeAdapter(context, adapter, notification)
        val completedAt = time.now()
        val finalization = try {
            val decision = decideFinalization(context, result, completedAt)
            transactions.executeWithoutResult {
                val finalized = store.finalize(
                    claim = claim,
                    context = context,
                    result = result,
                    finalization = decision,
                    completedAt = completedAt,
                    contentPurgeAt = if (decision.status in TERMINAL_STATUSES) {
                        completedAt.plus(properties.contentRetention)
                    } else {
                        null
                    },
                )
                if (!finalized) throw StaleNotificationClaimException(claim.deliveryId)
                outcomes.recompute(context.intentId)
            }
            decision
        } catch (exception: StaleNotificationClaimException) {
            throw exception
        } catch (exception: Exception) {
            throw NotificationPostSendProcessingException(exception)
        }
        metrics.attempt(context.adapterKey, result.outcome, result.classification)
        metrics.deliveryOutcome(context.type, context.channel, context.adapterKey, finalization.status, finalization.reason)
    }

    private fun invokeAdapter(
        context: NotificationAttemptContext,
        adapter: NotificationChannelAdapter,
        notification: RenderedNotification,
    ): AdapterResult {
        if (context.resumed) {
            val reconciled = try {
                adapter.reconcile(notification)
            } catch (exception: Exception) {
                return adapterFailure(context, AdapterOperation.RECONCILE, exception)
            }
            if (reconciled != null) return reconciled
        }
        return try {
            adapter.deliver(notification)
        } catch (exception: Exception) {
            adapterFailure(context, AdapterOperation.DELIVER, exception)
        }
    }

    private fun adapterFailure(
        context: NotificationAttemptContext,
        operation: AdapterOperation,
        exception: Exception,
    ): AdapterResult {
        log.error(
            "event=notification_adapter_exception deliveryId={} intentId={} providerRequestId={} adapter={} channel={} attempt={} operation={} exception={}",
            context.deliveryId,
            context.intentId,
            context.providerRequestId,
            context.adapterKey,
            context.channel,
            context.attemptNumber,
            operation,
            exception::class.simpleName,
            exception.sanitizedForLogging(),
        )
        metrics.adapterException(context.adapterKey, operation, exception)
        return AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)
    }

    private fun decideFinalization(
        context: NotificationAttemptContext,
        result: AdapterResult,
        completedAt: java.time.Instant,
    ): NotificationFinalization {
        val policy = policies.policyFor(context.type)
        val retryable = result.outcome in setOf(
            AdapterOutcome.TRANSIENT_FAILURE,
            AdapterOutcome.THROTTLED,
            AdapterOutcome.UNKNOWN_FAILURE,
            AdapterOutcome.UNKNOWN_AFTER_SEND,
        )
        val retryAt = if (retryable && context.attemptNumber < policy.retryPolicy.maxAttempts) {
            completedAt.plus(
                result.retryAfter ?: policy.retryPolicy.backoff.getOrElse(context.attemptNumber - 1) { Duration.ZERO },
            )
        } else {
            null
        }
        val canRetry = retryAt != null && retryAt.isBefore(context.expiresAt)
        val status = when {
            result.outcome == AdapterOutcome.SUCCESS -> NotificationDeliveryStatus.DELIVERED
            canRetry -> NotificationDeliveryStatus.RETRY_SCHEDULED
            retryable -> NotificationDeliveryStatus.DEAD_LETTER
            else -> NotificationDeliveryStatus.PERMANENT_FAILURE
        }
        val reason = when {
            result.outcome == AdapterOutcome.SUCCESS -> null
            result.outcome == AdapterOutcome.INVALID_ENDPOINT -> NotificationReason.ENDPOINT_INVALID
            result.outcome == AdapterOutcome.THROTTLED -> if (canRetry) NotificationReason.RATE_LIMIT else NotificationReason.RETRY_EXHAUSTED
            retryable -> if (canRetry) NotificationReason.PROVIDER_TRANSIENT else NotificationReason.RETRY_EXHAUSTED
            else -> NotificationReason.PROVIDER_PERMANENT
        }
        return NotificationFinalization(status, reason, if (canRetry) retryAt else null)
    }

    private fun NotificationAttemptContext.renderedNotification() = RenderedNotification(
        intentId = intentId,
        deliveryId = deliveryId,
        type = type,
        channel = channel,
        endpointReference = endpointReference,
        providerRequestId = providerRequestId,
        subject = subject,
        plainBody = plainBody,
        htmlBody = htmlBody,
    )

    companion object {
        private val log = LoggerFactory.getLogger(NotificationAttemptService::class.java)
        private val TERMINAL_STATUSES = setOf(
            NotificationDeliveryStatus.DELIVERED,
            NotificationDeliveryStatus.SUPPRESSED,
            NotificationDeliveryStatus.EXPIRED,
            NotificationDeliveryStatus.PERMANENT_FAILURE,
            NotificationDeliveryStatus.DEAD_LETTER,
        )
    }
}
