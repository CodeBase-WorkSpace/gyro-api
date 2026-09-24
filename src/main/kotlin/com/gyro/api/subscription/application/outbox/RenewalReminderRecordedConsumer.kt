package com.gyro.api.subscription.application.outbox

import com.gyro.api.common.outbox.OutboxConsumer
import com.gyro.api.common.outbox.OutboxEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Explicit terminal consumer for v1 reminders: they are recorded for later delivery, not sent. */
@Component
class RenewalReminderRecordedConsumer : OutboxConsumer {
    private val log = LoggerFactory.getLogger(javaClass)
    override val consumerName = "renewal-reminder-recorded"
    override fun supports(eventType: String) = eventType == "subscription.RENEWAL_REMINDER"
    override fun consume(event: OutboxEvent) {
        log.info("event=renewal_reminder outcome=recorded eventId={}", event.id)
    }
}
