package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationDeliveryClaim
import com.gyro.api.notification.domain.NotificationDeliveryStatus
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.NotificationProcessingFailureRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.util.concurrent.TimeoutException

@Service
class NotificationProcessingFailureHandler(
    private val repository: NotificationProcessingFailureRepository,
    private val outcomes: NotificationOutcomeService,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
    private val time: TimeProvider,
    private val transactions: TransactionTemplate,
) {
    fun handle(claim: NotificationDeliveryClaim, exception: Exception) {
        val transient = exception.isTransientInfrastructureFailure()
        val preserveIncompleteAttempt = transient && exception.isPostSendFailure()
        val result = transactions.execute {
            repository.record(
                claim = claim,
                now = time.now(),
                transient = transient,
                backoff = properties.processingFailureBackoff,
                maxAttempts = properties.processingFailureMaxAttempts,
                contentRetention = properties.contentRetention,
                preserveIncompleteAttempt = preserveIncompleteAttempt,
            )?.also { recorded ->
                if (recorded.status == NotificationDeliveryStatus.DEAD_LETTER) outcomes.recompute(recorded.intentId)
            }
        } ?: return

        log.error(
            "event=notification_processing_failure deliveryId={} intentId={} providerRequestId={} adapter={} channel={} attempt={} disposition={} exception={}",
            claim.deliveryId,
            result.intentId,
            result.providerRequestId,
            result.adapterKey,
            result.channel,
            result.attemptNumber,
            result.status,
            exception::class.simpleName,
            exception.sanitizedForLogging(),
        )
        metrics.processingFailure(result.adapterKey, result.status.name, exception)
    }

    private fun Throwable.isTransientInfrastructureFailure(): Boolean = generateSequence(this) { it.cause }.any {
        it is TransientDataAccessException ||
            it is DataAccessResourceFailureException ||
            it is TransactionTimedOutException ||
            it is TimeoutException ||
            it is IOException
    }

    private fun Throwable.isPostSendFailure(): Boolean = generateSequence(this) { it.cause }.any {
        it is NotificationPostSendProcessingException
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationProcessingFailureHandler::class.java)
    }
}

/** Preserves actionable frames without leaking exception messages or nested provider response bodies. */
internal fun Throwable.sanitizedForLogging(): Throwable = RuntimeException(this::class.qualifiedName ?: "unknown").also {
    it.stackTrace = stackTrace
}
