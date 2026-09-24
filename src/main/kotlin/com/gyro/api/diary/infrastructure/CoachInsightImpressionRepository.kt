package com.gyro.api.diary.infrastructure

import com.gyro.api.jooq.Tables.COACH_INSIGHT_IMPRESSIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

data class CoachInsightImpression(
    val key: String,
    val shownOn: LocalDate,
)

@Repository
class CoachInsightImpressionRepository(
    private val dsl: DSLContext,
) {
    fun shownSince(
        userId: UUID,
        sinceInclusive: LocalDate,
    ): List<CoachInsightImpression> =
        dsl.select(
            COACH_INSIGHT_IMPRESSIONS.KIND,
            COACH_INSIGHT_IMPRESSIONS.SHOWN_ON,
        )
            .from(COACH_INSIGHT_IMPRESSIONS)
            .where(
                COACH_INSIGHT_IMPRESSIONS.USER_ID.eq(userId)
                    .and(COACH_INSIGHT_IMPRESSIONS.SHOWN_ON.ge(sinceInclusive))
            )
            .orderBy(COACH_INSIGHT_IMPRESSIONS.SHOWN_ON.desc(), COACH_INSIGHT_IMPRESSIONS.KIND.asc())
            .fetch { record ->
                CoachInsightImpression(
                    key = record.get(COACH_INSIGHT_IMPRESSIONS.KIND)!!,
                    shownOn = record.get(COACH_INSIGHT_IMPRESSIONS.SHOWN_ON)!!,
                )
            }

    fun record(
        userId: UUID,
        keys: Collection<String>,
        shownOn: LocalDate,
    ): Int =
        keys.sumOf { key ->
            dsl.insertInto(COACH_INSIGHT_IMPRESSIONS)
                .set(COACH_INSIGHT_IMPRESSIONS.USER_ID, userId)
                .set(COACH_INSIGHT_IMPRESSIONS.KIND, key)
                .set(COACH_INSIGHT_IMPRESSIONS.SHOWN_ON, shownOn)
                .onConflict(
                    COACH_INSIGHT_IMPRESSIONS.USER_ID,
                    COACH_INSIGHT_IMPRESSIONS.KIND,
                    COACH_INSIGHT_IMPRESSIONS.SHOWN_ON,
                )
                .doNothing()
                .execute()
        }
}
