package com.gyro.api.common.outbox

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.jooq.Tables.OUTBOX_EVENT_CONSUMPTIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

/** Delivers one event to one consumer and records the publisher-owned idempotency ledger atomically. */
@Service
class OutboxDeliveryService(
    private val dsl: DSLContext,
    private val time: TimeProvider,
) {
    @Transactional
    fun deliver(event: OutboxEvent, consumer: OutboxConsumer) {
        val eventId = requireNotNull(event.id)
        // A conflicting insert waits for the winning transaction. It can reserve the pair only if that
        // transaction rolls back; after a commit it returns no row and cannot invoke the consumer again.
        val reservationId = dsl.insertInto(OUTBOX_EVENT_CONSUMPTIONS)
            .set(OUTBOX_EVENT_CONSUMPTIONS.EVENT_ID, eventId)
            .set(OUTBOX_EVENT_CONSUMPTIONS.CONSUMER_NAME, consumer.consumerName)
            .onConflict(
                OUTBOX_EVENT_CONSUMPTIONS.EVENT_ID,
                OUTBOX_EVENT_CONSUMPTIONS.CONSUMER_NAME,
            )
            .doNothing()
            .returning(OUTBOX_EVENT_CONSUMPTIONS.ID)
            .fetchOne()
            ?.get(OUTBOX_EVENT_CONSUMPTIONS.ID)
            ?: return

        consumer.consume(event)
        dsl.update(OUTBOX_EVENT_CONSUMPTIONS)
            .set(OUTBOX_EVENT_CONSUMPTIONS.PROCESSED_AT, time.now().atOffset(ZoneOffset.UTC))
            .where(OUTBOX_EVENT_CONSUMPTIONS.ID.eq(reservationId))
            .execute()
    }
}
