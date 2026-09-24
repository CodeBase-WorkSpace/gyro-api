package com.gyro.api.subscription.application.job

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.common.outbox.InvalidOutboxPayloadException
import com.gyro.api.common.outbox.OutboxConsumer
import com.gyro.api.common.outbox.OutboxDeliveryService
import com.gyro.api.common.outbox.OutboxStatus
import com.gyro.api.common.outbox.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/** The sole owner of outbox event status. Consumers only execute their callback. */
@Component
class OutboxPublisherJob(
    private val events: OutboxEventRepository,
    private val deliveryService: OutboxDeliveryService,
    private val consumers: List<OutboxConsumer>,
    private val time: TimeProvider,
    private val metrics: LifecycleObservability,
    @Value("\${app.billing.lifecycle.jobs-enabled:true}") private val enabled: Boolean,
    @Value("\${app.billing.lifecycle.batch-size:100}") private val batchSize: Int,
    @Value("\${app.billing.lifecycle.outbox-max-retries:5}") private val maxRetries: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(consumers.map { it.consumerName }.distinct().size == consumers.size) {
            "Outbox consumer names must be unique"
        }
    }
    @Scheduled(fixedDelayString = "\${app.billing.lifecycle.outbox-publish-delay:5000}") fun run() {
        if (!enabled) return
        try {
            val batch = events.findReadyToPublish(time.now(), PageRequest.of(0, batchSize));
            val outcomes = batch.map(::publish); outcomes.forEach {
                metrics.jobItem(
                    "outbox_publish",
                    it.metricOutcome
                )
            }; metrics.batchSize("outbox_publish", batch.size); metrics.jobRun(
                "outbox_publish",
                if (outcomes.all { it == PublishOutcome.PUBLISHED }) "success" else "partial_failure"
            )
        }
        catch (e: RuntimeException) { metrics.jobRun("outbox_publish", "failure"); log.warn("event=outbox_publish outcome=failure reason={}", e::class.simpleName) }
    }

    private fun publish(event: com.gyro.api.common.outbox.OutboxEvent): PublishOutcome {
        try {
            val supportedConsumers = consumers.filter { it.supports(event.eventType) }
            if (supportedConsumers.isEmpty()) {
                event.status = OutboxStatus.FAILED
                event.retryCount = maxRetries
                event.nextRetryAt = null
                event.lastError = "No outbox consumer supports ${event.eventType}"
                events.save(event)
                log.error("event=outbox_publish outcome=unsupported eventId={} eventType={}", event.id, event.eventType)
                return PublishOutcome.FAILED
            }
            supportedConsumers.forEach { consumer ->
                deliveryService.deliver(event, consumer)
            }
            event.status = OutboxStatus.PUBLISHED
            event.publishedAt = time.now()
            event.nextRetryAt = null
            event.lastError = null
            events.save(event)
            log.info("event=outbox_publish outcome=published eventId={}", event.id)
            return PublishOutcome.PUBLISHED
        } catch (e: InvalidOutboxPayloadException) {
            event.status = OutboxStatus.FAILED
            event.retryCount = maxRetries
            event.nextRetryAt = null
            event.lastError = "${e::class.simpleName}: ${e.message}".take(500)
            events.save(event)
            log.error("event=outbox_publish outcome=invalid_payload eventId={} eventType={}", event.id, event.eventType)
            return PublishOutcome.FAILED
        } catch (e: RuntimeException) {
            val retries = event.retryCount + 1
            val failed = retries >= maxRetries
            event.status = if (failed) OutboxStatus.FAILED else OutboxStatus.PENDING
            event.retryCount = retries
            event.nextRetryAt = if (failed) null else time.now().plus(backoff(retries))
            event.lastError = "${e::class.simpleName}: ${e.message}".take(500)
            events.save(event)
            log.warn("event=outbox_publish outcome={} eventId={} retryCount={}", if (failed) "failed" else "retry", event.id, retries)
            return if (failed) PublishOutcome.FAILED else PublishOutcome.RETRY
        }
    }
    private fun backoff(retry: Int) = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30))[retry.coerceIn(1, 5) - 1]

    private enum class PublishOutcome(val metricOutcome: String) {
        PUBLISHED("success"),
        RETRY("retry"),
        FAILED("failure"),
    }
}
