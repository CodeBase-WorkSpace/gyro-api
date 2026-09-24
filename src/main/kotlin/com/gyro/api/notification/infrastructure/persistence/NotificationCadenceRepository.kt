package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_ATTEMPTS
import com.gyro.api.jooq.Tables.NOTIFICATION_DELIVERIES
import com.gyro.api.jooq.Tables.NOTIFICATION_INTENTS
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationDeliveryStatus
import com.gyro.api.notification.domain.NotificationIntentStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class NotificationCadenceRepository(
    private val dsl: DSLContext,
) {
    fun lockUser(userId: UUID) {
        dsl.selectOne()
            .from(DSL.table(DSL.name("users")))
            .where(DSL.field(DSL.name("id"), UUID::class.java).eq(userId))
            .forUpdate()
            .fetchOne()
    }

    fun countAcceptedAnnouncements(
        userId: UUID,
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Long =
        dsl.selectCount()
            .from(NOTIFICATION_INTENTS)
            .where(
                NOTIFICATION_INTENTS.USER_ID.eq(userId)
                    .and(NOTIFICATION_INTENTS.CATEGORY.eq(NotificationCategory.OPTIONAL_ANNOUNCEMENTS.name))
                    .and(NOTIFICATION_INTENTS.SCHEDULED_AT.ge(fromInclusive.atOffset(ZoneOffset.UTC)))
                    .and(NOTIFICATION_INTENTS.SCHEDULED_AT.lt(toExclusive.atOffset(ZoneOffset.UTC)))
                    .and(
                        NOTIFICATION_INTENTS.STATUS.`in`(
                            NotificationIntentStatus.PENDING.name,
                            NotificationIntentStatus.ROUTED.name,
                            NotificationIntentStatus.COMPLETED.name,
                            NotificationIntentStatus.PARTIALLY_COMPLETED.name,
                        )
                    )
            )
            .fetchOne(0, Long::class.java) ?: 0L

    fun latestDeliveredPushAt(userId: UUID): Instant? =
        dsl.select(DSL.max(NOTIFICATION_ATTEMPTS.COMPLETED_AT))
            .from(NOTIFICATION_DELIVERIES)
            .join(NOTIFICATION_INTENTS)
            .on(NOTIFICATION_INTENTS.ID.eq(NOTIFICATION_DELIVERIES.INTENT_ID))
            .join(NOTIFICATION_ATTEMPTS)
            .on(NOTIFICATION_ATTEMPTS.DELIVERY_ID.eq(NOTIFICATION_DELIVERIES.ID))
            .where(
                NOTIFICATION_INTENTS.USER_ID.eq(userId)
                    .and(NOTIFICATION_DELIVERIES.CHANNEL.eq(NotificationChannel.PUSH.name))
                    .and(NOTIFICATION_DELIVERIES.STATUS.eq(NotificationDeliveryStatus.DELIVERED.name))
                    .and(NOTIFICATION_ATTEMPTS.OUTCOME.eq(AdapterOutcome.SUCCESS.name))
            )
            .fetchOne(0, OffsetDateTime::class.java)
            ?.toInstant()
}
