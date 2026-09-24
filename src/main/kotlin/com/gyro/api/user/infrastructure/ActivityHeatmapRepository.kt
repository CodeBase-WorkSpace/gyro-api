package com.gyro.api.user.infrastructure

import com.gyro.api.jooq.Tables.DIARY_DAYS
import com.gyro.api.jooq.Tables.DIARY_ENTRIES
import com.gyro.api.user.application.ActivityHeatmapDailyCount
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class ActivityHeatmapRepository(
    private val dsl: DSLContext,
) {
    fun loadDailyEntryCounts(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<ActivityHeatmapDailyCount> {
        val entryCount = DSL.count(DIARY_ENTRIES.ID).`as`("entry_count")

        return dsl.select(DIARY_DAYS.DIARY_DATE, entryCount)
            .from(DIARY_DAYS)
            .leftJoin(DIARY_ENTRIES)
            .on(
                DIARY_ENTRIES.DIARY_DAY_ID.eq(DIARY_DAYS.ID)
                    .and(DIARY_ENTRIES.USER_ID.eq(DIARY_DAYS.USER_ID))
                    .and(DIARY_ENTRIES.DIARY_DATE.eq(DIARY_DAYS.DIARY_DATE))
            )
            .where(
                DIARY_DAYS.USER_ID.eq(userId)
                    .and(DIARY_DAYS.DIARY_DATE.between(from, to))
            )
            .groupBy(DIARY_DAYS.DIARY_DATE)
            .orderBy(DIARY_DAYS.DIARY_DATE.asc())
            .fetch { record ->
                ActivityHeatmapDailyCount(
                    date = record.get(DIARY_DAYS.DIARY_DATE),
                    entryCount = record.get(entryCount),
                )
            }
    }
}
