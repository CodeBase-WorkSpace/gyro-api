package com.gyro.api.subscription.application

import com.gyro.api.common.outbox.InvalidOutboxPayloadException
import com.gyro.api.common.outbox.OutboxConsumer
import com.gyro.api.common.outbox.OutboxEvent
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Consumes outbox lifecycle events and invalidates entitlement cache.
 * Delegates transactional work to [EntitlementCacheInvalidationProcessor]
 * to avoid self-invocation which bypasses Spring's transaction proxy.
 */
@Component
class EntitlementCacheInvalidator(
    private val processor: EntitlementCacheInvalidationProcessor,
    private val objectMapper: ObjectMapper,
) : OutboxConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CONSUMER_NAME = "entitlement-cache-invalidator"

        val INVALIDATION_EVENT_TYPES = setOf(
            "subscription.FIRST_PURCHASE",
            "subscription.RENEWAL",
            "subscription.GRACE_EXIT_SUCCESS",
            "subscription.PERIOD_EXPIRED",
            "subscription.GRACE_ENTRY",
            "subscription.GRACE_EXIT_FAILURE",
            "subscription.USER_CANCEL",
            "subscription.USER_RESTORE",
            "subscription.ADMIN_GRANT",
            "subscription.ADMIN_REVOKE",
            "subscription.ADMIN_BILLING_BLOCK",
            "subscription.ADMIN_BILLING_UNBLOCK",
            "subscription.ACCOUNT_DELETED",
        )
    }

    override val consumerName: String = CONSUMER_NAME

    override fun supports(eventType: String) = eventType in INVALIDATION_EVENT_TYPES

    override fun consume(event: OutboxEvent) {
        val payload = try {
            objectMapper.readValue(event.payload, SubscriptionEventPayload::class.java)
        } catch (exception: Exception) {
            throw InvalidOutboxPayloadException("Invalid subscription event payload for event ${event.id}", exception)
        }
        processor.invalidate(payload.userId, requireNotNull(event.id))
        log.info("event=entitlement_cache_invalidation outcome=success eventId={}", event.id)
    }
}
