package com.gyro.api.subscription.application.outbox

import java.time.Instant
import java.util.UUID

data class SubscriptionEventPayload(
    val userId: UUID,
    val transitionType: String,
    val planId: Long,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val occurredAt: Instant,
)
