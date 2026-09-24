package com.gyro.api.goal.infrastructure

import com.gyro.api.jooq.Tables.NUTRITION_PLANS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RecalibrationCandidateRepository(
    private val dsl: DSLContext,
) {
    /**
     * Returns one deterministic keyset page. The caller keeps advancing the
     * cursor until this method returns an empty page, so candidates that do
     * not produce a suggestion cannot starve users with larger IDs.
     */
    fun findEligiblePage(afterUserId: UUID?, batchSize: Int): List<UUID> {
        require(batchSize > 0) { "Recalibration batch size must be positive" }

        return dsl.selectDistinct(NUTRITION_PLANS.USER_ID)
            .from(NUTRITION_PLANS)
            .where(
                NUTRITION_PLANS.DAILY_ENERGY_DELTA.isNotNull
                    .and(afterUserId?.let(NUTRITION_PLANS.USER_ID::gt) ?: DSL.noCondition())
                    .and(hasCurrentEntitlement(NUTRITION_PLANS.USER_ID)),
            )
            .orderBy(NUTRITION_PLANS.USER_ID)
            .limit(batchSize)
            .fetch(NUTRITION_PLANS.USER_ID)
    }

    private fun hasCurrentEntitlement(userId: org.jooq.Field<UUID>): Condition {
        val now = DSL.currentOffsetDateTime()
        return DSL.exists(
            DSL.selectOne()
                .from(USER_SUBSCRIPTIONS)
                .where(
                    SUBSCRIPTION_USER_ID.eq(userId)
                        .and(
                            SUBSCRIPTION_STATUS.eq("ACTIVE")
                                .and(SUBSCRIPTION_PERIOD_END.isNull.or(SUBSCRIPTION_PERIOD_END.gt(now)))
                                .or(
                                    SUBSCRIPTION_STATUS.eq("GRACE_PERIOD")
                                        .and(GRACE_PERIOD_END.isNull.or(GRACE_PERIOD_END.gt(now))),
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
                            .and(GRANT_EXPIRES_AT.isNull.or(GRANT_EXPIRES_AT.gt(now))),
                    ),
            ),
        )
    }

    private companion object {
        val USER_SUBSCRIPTIONS = DSL.table(DSL.name("user_subscriptions")).`as`("us")
        val SUBSCRIPTION_USER_ID = DSL.field(DSL.name("us", "user_id"), UUID::class.java)
        val SUBSCRIPTION_STATUS = DSL.field(DSL.name("us", "status"), String::class.java)
        val SUBSCRIPTION_PERIOD_END = DSL.field(DSL.name("us", "period_end"), OffsetDateTime::class.java)
        val GRACE_PERIOD_END = DSL.field(DSL.name("us", "grace_period_end"), OffsetDateTime::class.java)

        val MANUAL_GRANTS = DSL.table(DSL.name("manual_grants")).`as`("mg")
        val GRANT_USER_ID = DSL.field(DSL.name("mg", "user_id"), UUID::class.java)
        val GRANT_REVOKED_AT = DSL.field(DSL.name("mg", "revoked_at"), OffsetDateTime::class.java)
        val GRANT_EXPIRES_AT = DSL.field(DSL.name("mg", "expires_at"), OffsetDateTime::class.java)
    }
}
