package com.gyro.api.subscription.application.outbox

import com.gyro.api.common.outbox.OutboxWriteRequest
import com.gyro.api.common.outbox.OutboxWriter
import com.gyro.api.subscription.domain.Money
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class PaymentVerifiedNotificationPayload(
    val userId: UUID,
    val paymentAttemptId: UUID,
    val amount: Money,
    val occurredAt: Instant,
    val requestId: String,
)

@Component
class PaymentVerifiedNotificationOutboxWriter(
    private val outbox: OutboxWriter,
) {
    fun write(payload: PaymentVerifiedNotificationPayload) {
        outbox.write(
            OutboxWriteRequest(
                eventType = PAYMENT_VERIFIED_EVENT,
                aggregateType = "PaymentAttempt",
                aggregateId = payload.paymentAttemptId.toString(),
                payload = payload,
            ),
        )
    }

    companion object {
        const val PAYMENT_VERIFIED_EVENT = "billing.payment-verified"
    }
}
