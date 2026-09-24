package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.jooq.Tables.NOTIFICATION_INTENTS
import com.gyro.api.jooq.Tables.NUTRITION_PLANS
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationType
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class CoachDataNudgeRepository(
    private val dsl: DSLContext,
) {
    fun findEligiblePageAtLocalHour(
        afterUserId: UUID?,
        batchSize: Int,
        localHour: Int,
        evaluatedAt: Instant,
    ): List<UUID> {
        require(batchSize > 0) { "Coach data nudge batch size must be positive" }
        require(localHour in 0..23) { "Coach data nudge local hour must be between 0 and 23" }

        val plan = NUTRITION_PLANS.`as`("np")
        val newerPlan = NUTRITION_PLANS.`as`("newer_plan")
        val evaluatedAtUtc = DSL.`val`(evaluatedAt.atOffset(ZoneOffset.UTC))
        val localTimestamp = DSL.field(
            "{0} at time zone coalesce({1}, {2})",
            LocalDateTime::class.java,
            evaluatedAtUtc,
            TIMEZONE_NAME,
            DSL.inline(DEFAULT_TIMEZONE),
        )
        val localDate = localTimestamp.cast(LocalDate::class.java)
        val completedPlanDays = DSL.field("{0} - {1}", Int::class.java, localDate, plan.START_DATE)

        return dsl.select(plan.USER_ID)
            .from(plan)
            .join(USER_PROFILES).on(PROFILE_USER_ID.eq(plan.USER_ID))
            .leftJoin(TIMEZONES).on(TIMEZONE_NAME.eq(PROFILE_TIMEZONE))
            .where(
                plan.DAILY_ENERGY_DELTA.isNotNull
                    .and(plan.START_DATE.le(localDate))
                    .and(completedPlanDays.between(READINESS_FIRST_DAY, READINESS_LAST_DAY))
                    .and(DSL.extract(localTimestamp, org.jooq.DatePart.HOUR).eq(localHour))
                    .and(afterUserId?.let(plan.USER_ID::gt) ?: DSL.noCondition())
                    .and(
                        DSL.notExists(
                            DSL.selectOne()
                                .from(newerPlan)
                                .where(
                                    newerPlan.USER_ID.eq(plan.USER_ID)
                                        .and(newerPlan.START_DATE.le(localDate))
                                        .and(newerPlan.START_DATE.gt(plan.START_DATE)),
                                ),
                        ),
                    )
                    .and(hasEntitlementAt(plan.USER_ID, evaluatedAtUtc)),
            )
            .orderBy(plan.USER_ID)
            .limit(batchSize)
            .fetch(plan.USER_ID)
    }

    fun lockUser(userId: UUID) {
        requireNotNull(
            dsl.select(USER_ID)
                .from(USERS)
                .where(USER_ID.eq(userId))
                .forUpdate()
                .fetchOne(USER_ID),
        ) { "Coach data nudge user $userId does not exist" }
    }

    /** Only accepted intents consume the one-nudge evidence period. */
    fun hasNudgeForEvidencePeriod(userId: UUID, evidencePeriodReference: String): Boolean =
        dsl.fetchExists(
            DSL.selectOne()
                .from(NOTIFICATION_INTENTS)
                .where(
                    NOTIFICATION_INTENTS.USER_ID.eq(userId)
                        .and(NOTIFICATION_INTENTS.NOTIFICATION_TYPE.eq(NotificationType.COACH_DATA_NUDGE.name))
                        .and(NOTIFICATION_INTENTS.SOURCE_REFERENCE.eq(evidencePeriodReference))
                        .and(NOTIFICATION_INTENTS.STATUS.`in`(ACCEPTED_STATUS_NAMES)),
                ),
        )

    fun hasRecentAcceptedReminder(
        userId: UUID,
        types: Set<NotificationType>,
        fromInclusive: Instant,
        toInclusive: Instant,
    ): Boolean =
        dsl.fetchExists(
            DSL.selectOne()
                .from(NOTIFICATION_INTENTS)
                .where(
                    NOTIFICATION_INTENTS.USER_ID.eq(userId)
                        .and(NOTIFICATION_INTENTS.NOTIFICATION_TYPE.`in`(types.map(NotificationType::name)))
                        .and(NOTIFICATION_INTENTS.SCHEDULED_AT.ge(fromInclusive.atOffset(ZoneOffset.UTC)))
                        .and(NOTIFICATION_INTENTS.SCHEDULED_AT.le(toInclusive.atOffset(ZoneOffset.UTC)))
                        .and(NOTIFICATION_INTENTS.STATUS.`in`(ACCEPTED_STATUS_NAMES)),
                ),
        )

    private fun hasEntitlementAt(userId: Field<UUID>, evaluatedAt: Field<OffsetDateTime>): Condition =
        DSL.exists(
            DSL.selectOne()
                .from(USER_SUBSCRIPTIONS)
                .where(
                    SUBSCRIPTION_USER_ID.eq(userId)
                        .and(
                            SUBSCRIPTION_STATUS.eq("ACTIVE")
                                .and(SUBSCRIPTION_PERIOD_END.isNull.or(SUBSCRIPTION_PERIOD_END.gt(evaluatedAt)))
                                .or(
                                    SUBSCRIPTION_STATUS.eq("GRACE_PERIOD")
                                        .and(GRACE_PERIOD_END.isNull.or(GRACE_PERIOD_END.gt(evaluatedAt))),
                                ),
                        ),
                ),
        ).or(
            DSL.exists(
                DSL.selectOne()
                    .from(MANUAL_GRANTS)
                    .where(
                        GRANT_USER_ID.eq(userId)
                            .and(GRANT_REVOKED_AT.isNull)
                            .and(GRANT_EXPIRES_AT.isNull.or(GRANT_EXPIRES_AT.gt(evaluatedAt))),
                    ),
            ),
        )

    private companion object {
        const val DEFAULT_TIMEZONE = "Asia/Tehran"
        const val READINESS_FIRST_DAY = 7
        const val READINESS_LAST_DAY = 13

        val USERS = DSL.table(DSL.name("users")).`as`("u")
        val USER_ID = DSL.field(DSL.name("u", "id"), UUID::class.java)

        val USER_PROFILES = DSL.table(DSL.name("user_profiles")).`as`("up")
        val PROFILE_USER_ID = DSL.field(DSL.name("up", "user_id"), UUID::class.java)
        val PROFILE_TIMEZONE = DSL.field(DSL.name("up", "timezone"), String::class.java)

        val TIMEZONES = DSL.table(DSL.name("pg_timezone_names")).`as`("tz")
        val TIMEZONE_NAME = DSL.field(DSL.name("tz", "name"), String::class.java)

        val USER_SUBSCRIPTIONS = DSL.table(DSL.name("user_subscriptions")).`as`("us")
        val SUBSCRIPTION_USER_ID = DSL.field(DSL.name("us", "user_id"), UUID::class.java)
        val SUBSCRIPTION_STATUS = DSL.field(DSL.name("us", "status"), String::class.java)
        val SUBSCRIPTION_PERIOD_END = DSL.field(DSL.name("us", "period_end"), OffsetDateTime::class.java)
        val GRACE_PERIOD_END = DSL.field(DSL.name("us", "grace_period_end"), OffsetDateTime::class.java)

        val MANUAL_GRANTS = DSL.table(DSL.name("manual_grants")).`as`("mg")
        val GRANT_USER_ID = DSL.field(DSL.name("mg", "user_id"), UUID::class.java)
        val GRANT_REVOKED_AT = DSL.field(DSL.name("mg", "revoked_at"), OffsetDateTime::class.java)
        val GRANT_EXPIRES_AT = DSL.field(DSL.name("mg", "expires_at"), OffsetDateTime::class.java)

        val ACCEPTED_STATUSES = setOf(
            NotificationIntentStatus.PENDING,
            NotificationIntentStatus.ROUTED,
            NotificationIntentStatus.COMPLETED,
            NotificationIntentStatus.PARTIALLY_COMPLETED,
        )
        val ACCEPTED_STATUS_NAMES = ACCEPTED_STATUSES.map(NotificationIntentStatus::name)
    }
}
