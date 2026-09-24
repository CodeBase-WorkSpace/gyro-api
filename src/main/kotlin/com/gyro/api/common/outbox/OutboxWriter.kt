package com.gyro.api.common.outbox

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class OutboxWriteRequest(
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val payload: Any,
)

interface OutboxWriter {
    fun write(request: OutboxWriteRequest): OutboxEvent
}

@Component
class JpaOutboxWriter(
    private val events: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) : OutboxWriter {
    override fun write(request: OutboxWriteRequest): OutboxEvent = events.save(
        OutboxEvent(
            eventType = request.eventType,
            aggregateType = request.aggregateType,
            aggregateId = request.aggregateId,
            payload = objectMapper.writeValueAsString(request.payload),
        ),
    )
}
