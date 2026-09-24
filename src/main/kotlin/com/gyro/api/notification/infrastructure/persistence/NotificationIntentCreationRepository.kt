package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_INTENTS
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationReason
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import java.time.ZoneOffset
import java.util.UUID

@Repository
class NotificationIntentCreationRepository(
    private val dsl: DSLContext,
) {
    fun insertIfAbsent(intent: NotificationIntentEntity): UUID? = dsl.insertInto(NOTIFICATION_INTENTS)
        .set(NOTIFICATION_INTENTS.ID, intent.id)
        .set(NOTIFICATION_INTENTS.USER_ID, intent.userId)
        .set(NOTIFICATION_INTENTS.NOTIFICATION_TYPE, intent.type.name)
        .set(NOTIFICATION_INTENTS.CATEGORY, intent.category.name)
        .set(NOTIFICATION_INTENTS.RISK, intent.risk.name)
        .set(NOTIFICATION_INTENTS.ROUTE_STRATEGY, intent.routeStrategy.name)
        .set(NOTIFICATION_INTENTS.OCCURRED_AT, intent.occurredAt.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_INTENTS.SCHEDULED_AT, intent.scheduledAt.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_INTENTS.EXPIRES_AT, intent.expiresAt.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_INTENTS.IDEMPOTENCY_KEY, intent.idempotencyKey)
        .set(NOTIFICATION_INTENTS.SOURCE_TYPE, intent.sourceType)
        .set(NOTIFICATION_INTENTS.SOURCE_REFERENCE, intent.sourceReference)
        .set(NOTIFICATION_INTENTS.REQUEST_ID, intent.requestId)
        .set(NOTIFICATION_INTENTS.TEMPLATE_DATA, intent.templateData?.let(JSON::valueOf))
        .set(NOTIFICATION_INTENTS.STATUS, intent.status.name)
        .set(NOTIFICATION_INTENTS.REASON, intent.reason?.name)
        .set(NOTIFICATION_INTENTS.TERMINAL_AT, intent.terminalAt?.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_INTENTS.CREATED_AT, intent.createdAt.atOffset(ZoneOffset.UTC))
        .set(NOTIFICATION_INTENTS.UPDATED_AT, intent.updatedAt.atOffset(ZoneOffset.UTC))
        .onConflict(NOTIFICATION_INTENTS.SOURCE_TYPE, NOTIFICATION_INTENTS.IDEMPOTENCY_KEY)
        .doNothing()
        .returning(NOTIFICATION_INTENTS.ID)
        .fetchOne()
        ?.get(NOTIFICATION_INTENTS.ID)

    fun findOutcome(sourceType: String, idempotencyKey: String): PersistedNotificationIntentOutcome = requireNotNull(
        dsl.select(NOTIFICATION_INTENTS.ID, NOTIFICATION_INTENTS.STATUS, NOTIFICATION_INTENTS.REASON)
            .from(NOTIFICATION_INTENTS)
            .where(
                NOTIFICATION_INTENTS.SOURCE_TYPE.eq(sourceType)
                    .and(NOTIFICATION_INTENTS.IDEMPOTENCY_KEY.eq(idempotencyKey)),
            )
            .fetchOne { record ->
                PersistedNotificationIntentOutcome(
                    intentId = record.get(NOTIFICATION_INTENTS.ID),
                    status = NotificationIntentStatus.valueOf(record.get(NOTIFICATION_INTENTS.STATUS)),
                    reason = record.get(NOTIFICATION_INTENTS.REASON)?.let(NotificationReason::valueOf),
                )
            },
    ) { "Conflicting notification intent was not visible after insert" }
}

data class PersistedNotificationIntentOutcome(
    val intentId: UUID,
    val status: NotificationIntentStatus,
    val reason: NotificationReason?,
)
