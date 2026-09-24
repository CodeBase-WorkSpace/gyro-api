package com.gyro.api.common.outbox

/** A callback invoked by the single outbox publisher after durable event creation. */
interface OutboxConsumer {
    val consumerName: String
    fun supports(eventType: String): Boolean
    fun consume(event: OutboxEvent)
}

class InvalidOutboxPayloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
