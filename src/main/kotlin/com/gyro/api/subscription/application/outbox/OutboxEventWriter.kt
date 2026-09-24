package com.gyro.api.subscription.application.outbox

import com.gyro.api.common.outbox.OutboxWriteRequest
import com.gyro.api.common.outbox.OutboxWriter
import org.springframework.stereotype.Component

@Component
class OutboxEventWriter(
    private val outboxWriter: OutboxWriter,
) {
    fun writeSubscriptionEvent(
        aggregateId: String,
        payload: SubscriptionEventPayload,
    ) {
        outboxWriter.write(
            OutboxWriteRequest(
                eventType = "subscription.${payload.transitionType}",
                aggregateType = "UserSubscription",
                aggregateId = aggregateId,
                payload = payload,
            ),
        )
    }
}
