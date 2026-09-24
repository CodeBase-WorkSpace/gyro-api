package com.gyro.api.daily_score.infrastructure

import com.gyro.api.daily_score.application.DailyScoreBreakdown
import com.gyro.api.daily_score.application.DailyScoreDraft
import com.gyro.api.daily_score.application.DailyScoreInput
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.daily_score.application.DailyScoreNutritionTotals
import com.gyro.api.daily_score.application.DailyScoreReadModel
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.jooq.Tables.DAILY_SCORES
import com.gyro.api.jooq.Tables.DIARY_DAYS
import com.gyro.api.jooq.Tables.DIARY_ENTRIES
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class DailyScoreRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    fun loadDiaryDayUpdates(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, Instant> =
        dsl.select(DIARY_DAYS.DIARY_DATE, DIARY_DAYS.UPDATED_AT)
            .from(DIARY_DAYS)
            .where(
                DIARY_DAYS.USER_ID.eq(userId)
                    .and(DIARY_DAYS.DIARY_DATE.between(from, to))
            )
            .fetchMap(DIARY_DAYS.DIARY_DATE, DIARY_DAYS.UPDATED_AT)
            .mapValues { (_, updatedAt) -> updatedAt.toInstant() }

    fun loadScores(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyScoreReadModel> {
        return dsl.selectFrom(DAILY_SCORES)
            .where(
                DAILY_SCORES.USER_ID.eq(userId)
                    .and(DAILY_SCORES.LOCAL_DATE.between(from, to))
            )
            .orderBy(DAILY_SCORES.LOCAL_DATE.asc())
            .fetch { record ->
                DailyScoreReadModel(
                    id = record.get(DAILY_SCORES.ID),
                    userId = record.get(DAILY_SCORES.USER_ID),
                    localDate = record.get(DAILY_SCORES.LOCAL_DATE),
                    score = record.get(DAILY_SCORES.SCORE),
                    mode = DailyScoreMode.valueOf(record.get(DAILY_SCORES.MODE)),
                    goalId = record.get(DAILY_SCORES.GOAL_ID),
                    goalType = record.get(DAILY_SCORES.GOAL_TYPE)?.let { GoalType.valueOf(it) },
                    formulaName = record.get(DAILY_SCORES.FORMULA_NAME),
                    formulaVersion = record.get(DAILY_SCORES.FORMULA_VERSION),
                    breakdown = objectMapper.readValue(
                        record.get(DAILY_SCORES.BREAKDOWN).data(),
                        DailyScoreBreakdown::class.java,
                    ),
                    finalizedAt = record.get(DAILY_SCORES.FINALIZED_AT).toInstant(),
                    createdAt = record.get(DAILY_SCORES.CREATED_AT).toInstant(),
                )
            }
    }

    fun upsert(drafts: List<DailyScoreDraft>) {
        drafts.forEach { draft ->
            dsl.insertInto(DAILY_SCORES)
                .set(DAILY_SCORES.ID, draft.id)
                .set(DAILY_SCORES.USER_ID, draft.userId)
                .set(DAILY_SCORES.LOCAL_DATE, draft.localDate)
                .set(DAILY_SCORES.SCORE, draft.score)
                .set(DAILY_SCORES.MODE, draft.mode.name)
                .set(DAILY_SCORES.GOAL_ID, draft.goalId)
                .set(DAILY_SCORES.GOAL_TYPE, draft.goalType?.name)
                .set(DAILY_SCORES.FORMULA_NAME, draft.formulaName)
                .set(DAILY_SCORES.FORMULA_VERSION, draft.formulaVersion)
                .set(DAILY_SCORES.BREAKDOWN, JSON.valueOf(objectMapper.writeValueAsString(draft.breakdown)))
                .set(DAILY_SCORES.FINALIZED_AT, draft.finalizedAt.toOffsetDateTime())
                .set(DAILY_SCORES.CREATED_AT, draft.createdAt.toOffsetDateTime())
                .onConflict(DAILY_SCORES.USER_ID, DAILY_SCORES.LOCAL_DATE)
                .doUpdate()
                .set(DAILY_SCORES.SCORE, draft.score)
                .set(DAILY_SCORES.MODE, draft.mode.name)
                .set(DAILY_SCORES.GOAL_ID, draft.goalId)
                .set(DAILY_SCORES.GOAL_TYPE, draft.goalType?.name)
                .set(DAILY_SCORES.FORMULA_NAME, draft.formulaName)
                .set(DAILY_SCORES.FORMULA_VERSION, draft.formulaVersion)
                .set(DAILY_SCORES.BREAKDOWN, JSON.valueOf(objectMapper.writeValueAsString(draft.breakdown)))
                .set(DAILY_SCORES.FINALIZED_AT, draft.finalizedAt.toOffsetDateTime())
                .execute()
        }
    }

    fun loadScoreInputs(
        userId: UUID,
        dates: List<LocalDate>,
    ): Map<LocalDate, DailyScoreInput> {
        if (dates.isEmpty()) {
            return emptyMap()
        }
        val from = dates.min()
        val to = dates.max()
        val grouped = mutableMapOf<LocalDate, MutableList<DiaryEntryScoreRow>>()

        dsl.select(
            DIARY_DAYS.DIARY_DATE,
            DIARY_ENTRIES.ID,
            DIARY_ENTRIES.MEAL_TYPE,
            DIARY_ENTRIES.CALORIES_SNAPSHOT,
            DIARY_ENTRIES.PROTEIN_SNAPSHOT,
            DIARY_ENTRIES.CARBS_SNAPSHOT,
            DIARY_ENTRIES.FAT_SNAPSHOT,
        )
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
            .fetch { record ->
                val date = record.get(DIARY_DAYS.DIARY_DATE)
                grouped.getOrPut(date) { mutableListOf() }.add(
                    DiaryEntryScoreRow(
                        entryId = record.get(DIARY_ENTRIES.ID),
                        mealType = record.get(DIARY_ENTRIES.MEAL_TYPE),
                        calories = record.get(DIARY_ENTRIES.CALORIES_SNAPSHOT) ?: BigDecimal.ZERO,
                        protein = record.get(DIARY_ENTRIES.PROTEIN_SNAPSHOT) ?: BigDecimal.ZERO,
                        carbs = record.get(DIARY_ENTRIES.CARBS_SNAPSHOT) ?: BigDecimal.ZERO,
                        fat = record.get(DIARY_ENTRIES.FAT_SNAPSHOT) ?: BigDecimal.ZERO,
                    )
                )
            }

        return dates.associateWith { date ->
            val rows = grouped[date].orEmpty().filter { it.entryId != null }
            DailyScoreInput(
                date = date,
                logged = rows.isNotEmpty(),
                loggedMealCount = rows.size,
                loggedMealTypes = rows.mapNotNull { it.mealType }.toSet(),
                totals = DailyScoreNutritionTotals(
                    calories = rows.fold(BigDecimal.ZERO) { total, row -> total + row.calories },
                    protein = rows.fold(BigDecimal.ZERO) { total, row -> total + row.protein },
                    carbs = rows.fold(BigDecimal.ZERO) { total, row -> total + row.carbs },
                    fat = rows.fold(BigDecimal.ZERO) { total, row -> total + row.fat },
                ),
                target = null,
            )
        }
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime {
        return atOffset(ZoneOffset.UTC)
    }

    private data class DiaryEntryScoreRow(
        val entryId: UUID?,
        val mealType: String?,
        val calories: BigDecimal,
        val protein: BigDecimal,
        val carbs: BigDecimal,
        val fat: BigDecimal,
    )
}
