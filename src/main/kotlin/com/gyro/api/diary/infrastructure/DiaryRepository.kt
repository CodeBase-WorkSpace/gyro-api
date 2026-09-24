package com.gyro.api.diary.infrastructure

import com.gyro.api.jooq.Tables.DIARY_DAYS
import com.gyro.api.jooq.Tables.DIARY_ENTRIES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * jOOQ-backed repository responsible for persistence of diary days and diary entries.
 *
 * This repository intentionally contains only persistence logic. Business rules such as
 * authorization, validation, ownership checks, and deciding *what* should be copied belong
 * in the application service.
 *
 * Diary entries persist immutable nutrition snapshots so historical diary data remains stable
 * even if the underlying food or meal templates are edited later.
 */
@Repository
class DiaryRepository(
    private val dsl: DSLContext,
) {
    fun nutritionTotalsByDate(
        userId: UUID,
        from: LocalDate,
        toInclusive: LocalDate,
    ): Map<LocalDate, DiaryNutritionTotals> {
        return dsl.select(
            DIARY_ENTRIES.DIARY_DATE,
            DSL.count(DIARY_ENTRIES.ID),
            DSL.coalesce(DSL.sum(DIARY_ENTRIES.PROTEIN_SNAPSHOT), BigDecimal.ZERO),
            DSL.coalesce(DSL.sum(DIARY_ENTRIES.CALORIES_SNAPSHOT), BigDecimal.ZERO),
        )
            .from(DIARY_ENTRIES)
            .where(DIARY_ENTRIES.USER_ID.eq(userId).and(DIARY_ENTRIES.DIARY_DATE.between(from, toInclusive)))
            .groupBy(DIARY_ENTRIES.DIARY_DATE)
            .fetch { record ->
                record.get(DIARY_ENTRIES.DIARY_DATE) to DiaryNutritionTotals(
                    logged = record.value2() > 0,
                    protein = record.value3(),
                    calories = record.value4(),
                )
            }.toMap()
    }

    /**
     * Loads only the bounded date window needed for the dashboard streak.
     * The existing `(user_id, diary_date)` access path keeps this independent
     * of the owner's total diary history.
     */
    fun loggedDatesBetween(
        userId: UUID,
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
    ): Set<LocalDate> =
        dsl.selectDistinct(DIARY_ENTRIES.DIARY_DATE)
            .from(DIARY_ENTRIES)
            .where(
                DIARY_ENTRIES.USER_ID.eq(userId)
                    .and(DIARY_ENTRIES.DIARY_DATE.between(fromInclusive, toInclusive))
            )
            .fetchSet(DIARY_ENTRIES.DIARY_DATE)
    /**
     * Returns the diary day for the supplied user and date, creating it when it does not already
     * exist.
     */
    fun getOrCreateDay(
        userId: UUID,
        date: LocalDate,
        timezone: String,
    ): DiaryDayRecord {
        dsl.insertInto(DIARY_DAYS)
            .set(DIARY_DAYS.USER_ID, userId)
            .set(DIARY_DAYS.DIARY_DATE, date)
            .set(DIARY_DAYS.TIMEZONE, timezone)
            .set(DIARY_DAYS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(DIARY_DAYS.UPDATED_AT, DSL.currentOffsetDateTime())
            .onConflict(DIARY_DAYS.USER_ID, DIARY_DAYS.DIARY_DATE)
            .doNothing()
            .execute()

        return dsl.select(DIARY_DAYS.ID, DIARY_DAYS.DIARY_DATE, DIARY_DAYS.TIMEZONE)
            .from(DIARY_DAYS)
            .where(DIARY_DAYS.USER_ID.eq(userId).and(DIARY_DAYS.DIARY_DATE.eq(date)))
            .fetchOne { record ->
                DiaryDayRecord(
                    id = record.get(DIARY_DAYS.ID),
                    date = record.get(DIARY_DAYS.DIARY_DATE),
                    timezone = record.get(DIARY_DAYS.TIMEZONE),
                )
            } ?: error("Diary day insert did not return a row.")
    }

    /**
     * Finds an existing diary day for the supplied user and date.
     *
     * Returns `null` when the day has not been created.
     */
    fun findDay(userId: UUID, date: LocalDate): DiaryDayRecord? {
        return dsl.select(DIARY_DAYS.ID, DIARY_DAYS.DIARY_DATE, DIARY_DAYS.TIMEZONE)
            .from(DIARY_DAYS)
            .where(DIARY_DAYS.USER_ID.eq(userId).and(DIARY_DAYS.DIARY_DATE.eq(date)))
            .fetchOne { record ->
                DiaryDayRecord(
                    id = record.get(DIARY_DAYS.ID),
                    date = record.get(DIARY_DAYS.DIARY_DATE),
                    timezone = record.get(DIARY_DAYS.TIMEZONE),
                )
            }
    }

    /**
     * Loads all diary entries for the supplied diary day ordered by meal type and sort order.
     *
     * The returned records contain immutable snapshot values rather than recalculated nutrition.
     */
    fun findEntries(
        userId: UUID,
        day: DiaryDayRecord,
    ): List<DiaryEntryRecord> {
        return dsl.select(
            DIARY_ENTRIES.ID,
            DIARY_ENTRIES.MEAL_TYPE,
            DIARY_ENTRIES.SOURCE_TYPE,
            DIARY_ENTRIES.SOURCE_FOOD_ID,
            DIARY_ENTRIES.SOURCE_MEAL_ID,
            DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_ID,
            DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT,
            DIARY_ENTRIES.SORT_ORDER,
            DIARY_ENTRIES.CALORIES_SNAPSHOT,
            DIARY_ENTRIES.PROTEIN_SNAPSHOT,
            DIARY_ENTRIES.CARBS_SNAPSHOT,
            DIARY_ENTRIES.FAT_SNAPSHOT,
            DIARY_ENTRIES.FIBER_SNAPSHOT,
            DIARY_ENTRIES.SUGAR_SNAPSHOT,
            DIARY_ENTRIES.SODIUM_SNAPSHOT,
        )
            .from(DIARY_ENTRIES)
            .where(
                DIARY_ENTRIES.DIARY_DAY_ID.eq(day.id)
                    .and(DIARY_ENTRIES.USER_ID.eq(userId))
                    .and(DIARY_ENTRIES.DIARY_DATE.eq(day.date))
            )
            .orderBy(DIARY_ENTRIES.MEAL_TYPE.asc(), DIARY_ENTRIES.SORT_ORDER.asc(), DIARY_ENTRIES.ID.asc())
            .fetch { record ->
                DiaryEntryRecord(
                    id = record.get(DIARY_ENTRIES.ID),
                    mealType = record.get(DIARY_ENTRIES.MEAL_TYPE),
                    sourceType = record.get(DIARY_ENTRIES.SOURCE_TYPE),
                    sourceFoodId = record.get(DIARY_ENTRIES.SOURCE_FOOD_ID),
                    sourceMealId = record.get(DIARY_ENTRIES.SOURCE_MEAL_ID),
                    displayName = record.get(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT),
                    servingUnitId = record.get(DIARY_ENTRIES.SERVING_UNIT_ID),
                    servingQuantity = record.get(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT),
                    servingUnitCode = record.get(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT),
                    servingUnitName = record.get(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT),
                    sortOrder = record.get(DIARY_ENTRIES.SORT_ORDER),
                    calories = record.get(DIARY_ENTRIES.CALORIES_SNAPSHOT),
                    protein = record.get(DIARY_ENTRIES.PROTEIN_SNAPSHOT),
                    carbs = record.get(DIARY_ENTRIES.CARBS_SNAPSHOT),
                    fat = record.get(DIARY_ENTRIES.FAT_SNAPSHOT),
                    fiber = record.get(DIARY_ENTRIES.FIBER_SNAPSHOT),
                    sugar = record.get(DIARY_ENTRIES.SUGAR_SNAPSHOT),
                    sodium = record.get(DIARY_ENTRIES.SODIUM_SNAPSHOT),
                )
            }
    }

    /**
     * Computes the next sort order within a meal group for a diary day.
     */
    fun nextSortOrder(dayId: UUID, mealType: String): Int {
        return (dsl.select(DSL.max(DIARY_ENTRIES.SORT_ORDER))
            .from(DIARY_ENTRIES)
            .where(DIARY_ENTRIES.DIARY_DAY_ID.eq(dayId).and(DIARY_ENTRIES.MEAL_TYPE.eq(mealType)))
            .fetchOne(0, Int::class.java) ?: -1) + 1
    }

    /**
     * Persists a new diary entry using the provided immutable nutrition snapshot.
     */
    fun createEntry(
        userId: UUID,
        day: DiaryDayRecord,
        snapshot: DiaryEntrySnapshot,
    ) {
        createEntries(userId, day, listOf(snapshot))
    }

    fun createEntries(
        userId: UUID,
        day: DiaryDayRecord,
        snapshots: List<DiaryEntrySnapshot>,
    ) {
        if (snapshots.isEmpty()) return

        val nextOrderByMeal = snapshots.map { it.mealType }.distinct()
            .associateWith { nextSortOrder(day.id, it) }
            .toMutableMap()

        snapshots.forEach { snapshot ->
            val sortOrder = nextOrderByMeal.getValue(snapshot.mealType)
            nextOrderByMeal[snapshot.mealType] = sortOrder + 1

            dsl.insertInto(DIARY_ENTRIES)
            .set(DIARY_ENTRIES.DIARY_DAY_ID, day.id)
            .set(DIARY_ENTRIES.USER_ID, userId)
            .set(DIARY_ENTRIES.DIARY_DATE, day.date)
            .set(DIARY_ENTRIES.MEAL_TYPE, snapshot.mealType)
            .set(DIARY_ENTRIES.SOURCE_TYPE, snapshot.sourceType)
            .set(DIARY_ENTRIES.SOURCE_FOOD_ID, snapshot.sourceFoodId)
            .set(DIARY_ENTRIES.SOURCE_MEAL_ID, snapshot.sourceMealId)
            .set(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT, snapshot.displayName)
            .set(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT, snapshot.servingQuantity)
            .set(DIARY_ENTRIES.SERVING_UNIT_ID, snapshot.servingUnitId)
            .set(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT, snapshot.servingUnitCode)
            .set(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT, snapshot.servingUnitName)
            .set(DIARY_ENTRIES.CALORIES_SNAPSHOT, snapshot.calories)
            .set(DIARY_ENTRIES.PROTEIN_SNAPSHOT, snapshot.protein)
            .set(DIARY_ENTRIES.CARBS_SNAPSHOT, snapshot.carbs)
            .set(DIARY_ENTRIES.FAT_SNAPSHOT, snapshot.fat)
            .set(DIARY_ENTRIES.FIBER_SNAPSHOT, snapshot.fiber)
            .set(DIARY_ENTRIES.SUGAR_SNAPSHOT, snapshot.sugar)
            .set(DIARY_ENTRIES.SODIUM_SNAPSHOT, snapshot.sodium)
            .set(DIARY_ENTRIES.SORT_ORDER, sortOrder)
            .set(DIARY_ENTRIES.CREATED_AT, DSL.currentOffsetDateTime())
            .set(DIARY_ENTRIES.UPDATED_AT, DSL.currentOffsetDateTime())
            .execute()
        }
        touchDay(day.id)
    }

    /**
     * Replaces the snapshot values of an existing diary entry.
     *
     * Returns `true` when an entry was updated; otherwise `false`.
     */
    fun updateEntry(
        userId: UUID,
        day: DiaryDayRecord,
        entryId: UUID,
        snapshot: DiaryEntrySnapshot,
    ): Boolean {
        val updated = dsl.update(DIARY_ENTRIES)
            .set(DIARY_ENTRIES.MEAL_TYPE, snapshot.mealType)
            .set(DIARY_ENTRIES.SOURCE_TYPE, snapshot.sourceType)
            .set(DIARY_ENTRIES.SOURCE_FOOD_ID, snapshot.sourceFoodId)
            .set(DIARY_ENTRIES.SOURCE_MEAL_ID, snapshot.sourceMealId)
            .set(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT, snapshot.displayName)
            .set(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT, snapshot.servingQuantity)
            .set(DIARY_ENTRIES.SERVING_UNIT_ID, snapshot.servingUnitId)
            .set(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT, snapshot.servingUnitCode)
            .set(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT, snapshot.servingUnitName)
            .set(DIARY_ENTRIES.CALORIES_SNAPSHOT, snapshot.calories)
            .set(DIARY_ENTRIES.PROTEIN_SNAPSHOT, snapshot.protein)
            .set(DIARY_ENTRIES.CARBS_SNAPSHOT, snapshot.carbs)
            .set(DIARY_ENTRIES.FAT_SNAPSHOT, snapshot.fat)
            .set(DIARY_ENTRIES.FIBER_SNAPSHOT, snapshot.fiber)
            .set(DIARY_ENTRIES.SUGAR_SNAPSHOT, snapshot.sugar)
            .set(DIARY_ENTRIES.SODIUM_SNAPSHOT, snapshot.sodium)
            .set(DIARY_ENTRIES.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                DIARY_ENTRIES.ID.eq(entryId)
                    .and(DIARY_ENTRIES.DIARY_DAY_ID.eq(day.id))
                    .and(DIARY_ENTRIES.USER_ID.eq(userId))
                    .and(DIARY_ENTRIES.DIARY_DATE.eq(day.date))
            )
            .execute() == 1
        if (updated) {
            touchDay(day.id)
        }
        return updated
    }

    /**
     * Deletes a diary entry owned by the specified user.
     *
     * Returns `true` when an entry was removed; otherwise `false`.
     */
    fun deleteEntry(
        userId: UUID,
        day: DiaryDayRecord,
        entryId: UUID,
    ): Boolean {
        val deleted = dsl.deleteFrom(DIARY_ENTRIES)
            .where(
                DIARY_ENTRIES.ID.eq(entryId)
                    .and(DIARY_ENTRIES.DIARY_DAY_ID.eq(day.id))
                    .and(DIARY_ENTRIES.USER_ID.eq(userId))
                    .and(DIARY_ENTRIES.DIARY_DATE.eq(day.date))
            )
            .execute() == 1
        if (deleted) {
            touchDay(day.id)
        }
        return deleted
    }

    /**
     * Copies existing diary entry snapshots into another diary day.
     *
     * This method must copy the persisted snapshot fields exactly as stored. It must never
     * recalculate nutrition from the current food or meal definitions, ensuring historical data
     * remains stable.
     */
    fun copyEntries(
        userId: UUID,
        sourceEntries: List<DiaryEntryRecord>,
        targetDay: DiaryDayRecord,
    ) {
        if (sourceEntries.isEmpty()) return

        val currentMaxSortOrderByMealType = sourceEntries
            .map { it.mealType }
            .distinct()
            .associateWith { mealType ->
                nextSortOrder(targetDay.id, mealType) - 1
            }
            .toMutableMap()

        val inserts = sourceEntries.map { entry ->
            val nextSortOrder = currentMaxSortOrderByMealType.getValue(entry.mealType) + 1
            currentMaxSortOrderByMealType[entry.mealType] = nextSortOrder

            dsl.insertInto(DIARY_ENTRIES)
                .set(DIARY_ENTRIES.DIARY_DAY_ID, targetDay.id)
                .set(DIARY_ENTRIES.USER_ID, userId)
                .set(DIARY_ENTRIES.DIARY_DATE, targetDay.date)
                .set(DIARY_ENTRIES.MEAL_TYPE, entry.mealType)
                .set(DIARY_ENTRIES.SOURCE_TYPE, entry.sourceType)
                .set(DIARY_ENTRIES.SOURCE_FOOD_ID, entry.sourceFoodId)
                .set(DIARY_ENTRIES.SOURCE_MEAL_ID, entry.sourceMealId)
                .set(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT, entry.displayName)
                .set(DIARY_ENTRIES.SERVING_UNIT_ID, entry.servingUnitId)
                .set(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT, entry.servingQuantity)
                .set(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT, entry.servingUnitCode)
                .set(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT, entry.servingUnitName)
                .set(DIARY_ENTRIES.CALORIES_SNAPSHOT, entry.calories)
                .set(DIARY_ENTRIES.PROTEIN_SNAPSHOT, entry.protein)
                .set(DIARY_ENTRIES.CARBS_SNAPSHOT, entry.carbs)
                .set(DIARY_ENTRIES.FAT_SNAPSHOT, entry.fat)
                .set(DIARY_ENTRIES.FIBER_SNAPSHOT, entry.fiber)
                .set(DIARY_ENTRIES.SUGAR_SNAPSHOT, entry.sugar)
                .set(DIARY_ENTRIES.SODIUM_SNAPSHOT, entry.sodium)
                .set(DIARY_ENTRIES.SORT_ORDER, nextSortOrder)
                .set(DIARY_ENTRIES.CREATED_AT, DSL.currentOffsetDateTime())
                .set(DIARY_ENTRIES.UPDATED_AT, DSL.currentOffsetDateTime())
        }

        dsl.batch(inserts).execute()
        touchDay(targetDay.id)
    }

    fun findEntryById(
        userId: UUID,
        entryId: UUID,
    ): DiaryEntryRecord? {
        return dsl.select(
            DIARY_ENTRIES.ID,
            DIARY_ENTRIES.MEAL_TYPE,
            DIARY_ENTRIES.SOURCE_TYPE,
            DIARY_ENTRIES.SOURCE_FOOD_ID,
            DIARY_ENTRIES.SOURCE_MEAL_ID,
            DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_ID,
            DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT,
            DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT,
            DIARY_ENTRIES.SORT_ORDER,
            DIARY_ENTRIES.CALORIES_SNAPSHOT,
            DIARY_ENTRIES.PROTEIN_SNAPSHOT,
            DIARY_ENTRIES.CARBS_SNAPSHOT,
            DIARY_ENTRIES.FAT_SNAPSHOT,
            DIARY_ENTRIES.FIBER_SNAPSHOT,
            DIARY_ENTRIES.SUGAR_SNAPSHOT,
            DIARY_ENTRIES.SODIUM_SNAPSHOT,
        )
            .from(DIARY_ENTRIES)
            .where(
                DIARY_ENTRIES.ID.eq(entryId)
                    .and(DIARY_ENTRIES.USER_ID.eq(userId))
            )
            .fetchOne { record ->
                DiaryEntryRecord(
                    id = record.get(DIARY_ENTRIES.ID),
                    mealType = record.get(DIARY_ENTRIES.MEAL_TYPE),
                    sourceType = record.get(DIARY_ENTRIES.SOURCE_TYPE),
                    sourceFoodId = record.get(DIARY_ENTRIES.SOURCE_FOOD_ID),
                    sourceMealId = record.get(DIARY_ENTRIES.SOURCE_MEAL_ID),
                    displayName = record.get(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT),
                    servingUnitId = record.get(DIARY_ENTRIES.SERVING_UNIT_ID),
                    servingQuantity = record.get(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT),
                    servingUnitCode = record.get(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT),
                    servingUnitName = record.get(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT),
                    sortOrder = record.get(DIARY_ENTRIES.SORT_ORDER),
                    calories = record.get(DIARY_ENTRIES.CALORIES_SNAPSHOT),
                    protein = record.get(DIARY_ENTRIES.PROTEIN_SNAPSHOT),
                    carbs = record.get(DIARY_ENTRIES.CARBS_SNAPSHOT),
                    fat = record.get(DIARY_ENTRIES.FAT_SNAPSHOT),
                    fiber = record.get(DIARY_ENTRIES.FIBER_SNAPSHOT),
                    sugar = record.get(DIARY_ENTRIES.SUGAR_SNAPSHOT),
                    sodium = record.get(DIARY_ENTRIES.SODIUM_SNAPSHOT),
                )
            }
    }

    fun copyEntry(
        userId: UUID,
        sourceEntry: DiaryEntryRecord,
        targetDay: DiaryDayRecord,
    ) {
        dsl.insertInto(DIARY_ENTRIES)
            .set(DIARY_ENTRIES.DIARY_DAY_ID, targetDay.id)
            .set(DIARY_ENTRIES.USER_ID, userId)
            .set(DIARY_ENTRIES.DIARY_DATE, targetDay.date)
            .set(DIARY_ENTRIES.MEAL_TYPE, sourceEntry.mealType)
            .set(DIARY_ENTRIES.SOURCE_TYPE, sourceEntry.sourceType)
            .set(DIARY_ENTRIES.SOURCE_FOOD_ID, sourceEntry.sourceFoodId)
            .set(DIARY_ENTRIES.SOURCE_MEAL_ID, sourceEntry.sourceMealId)
            .set(DIARY_ENTRIES.DISPLAY_NAME_SNAPSHOT, sourceEntry.displayName)
            .set(DIARY_ENTRIES.SERVING_UNIT_ID, sourceEntry.servingUnitId)
            .set(DIARY_ENTRIES.SERVING_QUANTITY_SNAPSHOT, sourceEntry.servingQuantity)
            .set(DIARY_ENTRIES.SERVING_UNIT_CODE_SNAPSHOT, sourceEntry.servingUnitCode)
            .set(DIARY_ENTRIES.SERVING_UNIT_NAME_SNAPSHOT, sourceEntry.servingUnitName)
            .set(DIARY_ENTRIES.CALORIES_SNAPSHOT, sourceEntry.calories)
            .set(DIARY_ENTRIES.PROTEIN_SNAPSHOT, sourceEntry.protein)
            .set(DIARY_ENTRIES.CARBS_SNAPSHOT, sourceEntry.carbs)
            .set(DIARY_ENTRIES.FAT_SNAPSHOT, sourceEntry.fat)
            .set(DIARY_ENTRIES.FIBER_SNAPSHOT, sourceEntry.fiber)
            .set(DIARY_ENTRIES.SUGAR_SNAPSHOT, sourceEntry.sugar)
            .set(DIARY_ENTRIES.SODIUM_SNAPSHOT, sourceEntry.sodium)
            .set(DIARY_ENTRIES.SORT_ORDER, nextSortOrder(targetDay.id, sourceEntry.mealType))
            .set(DIARY_ENTRIES.CREATED_AT, DSL.currentOffsetDateTime())
            .set(DIARY_ENTRIES.UPDATED_AT, DSL.currentOffsetDateTime())
            .execute()
        touchDay(targetDay.id)
    }

    private fun touchDay(dayId: UUID) {
        dsl.update(DIARY_DAYS)
            .set(DIARY_DAYS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(DIARY_DAYS.ID.eq(dayId))
            .execute()
    }
}

data class DiaryNutritionTotals(
    val logged: Boolean,
    val protein: BigDecimal,
    val calories: BigDecimal = BigDecimal.ZERO,
)

data class DiaryDayRecord(
    val id: UUID,
    val date: LocalDate,
    val timezone: String,
)

data class DiaryEntryRecord(
    val id: UUID,
    val mealType: String,
    val sourceType: String,
    val sourceFoodId: UUID?,
    val sourceMealId: UUID?,
    val displayName: String,
    val servingUnitId: UUID?,
    val servingQuantity: BigDecimal,
    val servingUnitCode: String,
    val servingUnitName: String,
    val sortOrder: Int,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class DiaryEntrySnapshot(
    val mealType: String,
    val sourceType: String,
    val sourceFoodId: UUID?,
    val sourceMealId: UUID?,
    val displayName: String,
    val servingQuantity: BigDecimal,
    val servingUnitId: UUID?,
    val servingUnitCode: String,
    val servingUnitName: String,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)
