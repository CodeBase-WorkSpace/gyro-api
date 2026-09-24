package com.gyro.api.goal.infrastructure

import com.gyro.api.jooq.Tables.PLAN_TARGET_REGIME_BOUNDARIES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class PlanTargetRegimeBoundaryRepository(
    private val dsl: DSLContext,
) {
    fun insertScheduleBoundaryIfAbsent(
        userId: UUID,
        nutritionPlanId: UUID,
        effectiveFrom: LocalDate,
    ) {
        // Recalibration evidence is date-granular, so multiple target changes on one
        // user-local date intentionally form a single regime boundary.
        dsl.insertInto(PLAN_TARGET_REGIME_BOUNDARIES)
            .set(PLAN_TARGET_REGIME_BOUNDARIES.ID, UUID.randomUUID())
            .set(PLAN_TARGET_REGIME_BOUNDARIES.USER_ID, userId)
            .set(PLAN_TARGET_REGIME_BOUNDARIES.NUTRITION_PLAN_ID, nutritionPlanId)
            .set(PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM, effectiveFrom)
            .set(PLAN_TARGET_REGIME_BOUNDARIES.REASON, "SCHEDULE_CHANGE")
            .onConflict(
                PLAN_TARGET_REGIME_BOUNDARIES.NUTRITION_PLAN_ID,
                PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM,
                PLAN_TARGET_REGIME_BOUNDARIES.REASON,
            )
            .doNothing()
            .execute()
    }

    fun latestScheduleBoundaryOnOrBefore(
        nutritionPlanId: UUID,
        date: LocalDate,
    ): LocalDate? = dsl.select(PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM)
        .from(PLAN_TARGET_REGIME_BOUNDARIES)
        .where(
            PLAN_TARGET_REGIME_BOUNDARIES.NUTRITION_PLAN_ID.eq(nutritionPlanId)
                .and(PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM.le(date)),
        )
        .orderBy(PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM.desc())
        .limit(1)
        .fetchOne(PLAN_TARGET_REGIME_BOUNDARIES.EFFECTIVE_FROM)
}
