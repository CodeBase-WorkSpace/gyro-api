package com.gyro.api.progress.infrastructure

import com.gyro.api.jooq.Tables.*
import com.gyro.api.progress.application.NutritionProgressDailyPoint
import com.gyro.api.progress.application.NutritionProgressTotals
import com.gyro.api.progress.application.ProgressDateRange
import com.gyro.api.progress.application.WeightProgressPoint
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@Repository
class ProgressReadRepository(
    private val dsl: DSLContext,
) {
    fun loadNutritionDailyTotals(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<NutritionProgressDailyPoint> {
        return loadNutritionDailyTotals(
            userId = userId,
            ranges = listOf(ProgressDateRange(from = from, to = to)),
        )
    }

    fun loadNutritionDailyTotals(
        userId: UUID,
        ranges: List<ProgressDateRange>,
    ): List<NutritionProgressDailyPoint> {
        val calories = sumOrZero(DIARY_ENTRIES.CALORIES_SNAPSHOT, "calories")
        val protein = sumOrZero(DIARY_ENTRIES.PROTEIN_SNAPSHOT, "protein")
        val carbs = sumOrZero(DIARY_ENTRIES.CARBS_SNAPSHOT, "carbs")
        val fat = sumOrZero(DIARY_ENTRIES.FAT_SNAPSHOT, "fat")
        val fiber = sumOrZero(DIARY_ENTRIES.FIBER_SNAPSHOT, "fiber")
        val sugar = sumOrZero(DIARY_ENTRIES.SUGAR_SNAPSHOT, "sugar")
        val sodium = sumOrZero(DIARY_ENTRIES.SODIUM_SNAPSHOT, "sodium")
        val entryCount = DSL.count(DIARY_ENTRIES.ID).`as`("entry_count")

        return dsl.select(
            DIARY_DAYS.DIARY_DATE,
            entryCount,
            calories,
            protein,
            carbs,
            fat,
            fiber,
            sugar,
            sodium,
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
                    .and(ranges.toDiaryDateCondition())
            )
            .groupBy(DIARY_DAYS.DIARY_DATE)
            .orderBy(DIARY_DAYS.DIARY_DATE.asc())
            .fetch { record ->
                NutritionProgressDailyPoint(
                    date = record.get(DIARY_DAYS.DIARY_DATE),
                    logged = record.get(entryCount) > 0,
                    totals = NutritionProgressTotals(
                        calories = record.get(calories),
                        protein = record.get(protein),
                        carbs = record.get(carbs),
                        fat = record.get(fat),
                        fiber = record.get(fiber),
                        sugar = record.get(sugar),
                        sodium = record.get(sodium),
                    ),
                    goal = null,
                )
            }
    }

    fun loadWeightPoints(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<WeightProgressPoint> {
        return dsl.select(WEIGHT_ENTRIES.RECORDED_DATE, WEIGHT_ENTRIES.WEIGHT_KG)
            .from(WEIGHT_ENTRIES)
            .where(
                WEIGHT_ENTRIES.USER_ID.eq(userId)
                    .and(WEIGHT_ENTRIES.RECORDED_DATE.between(from, to))
            )
            .orderBy(WEIGHT_ENTRIES.RECORDED_DATE.asc())
            .fetch { record ->
                WeightProgressPoint(
                    date = record.get(WEIGHT_ENTRIES.RECORDED_DATE),
                    weightKg = record.get(WEIGHT_ENTRIES.WEIGHT_KG).setScale(3),
                )
            }
    }

    fun loadLatestWeightMeasurementDate(userId: UUID): LocalDate? {
        return dsl.select(DSL.max(WEIGHT_ENTRIES.RECORDED_DATE))
            .from(WEIGHT_ENTRIES)
            .where(WEIGHT_ENTRIES.USER_ID.eq(userId))
            .fetchOne(0, LocalDate::class.java)
    }

    private fun sumOrZero(
        field: Field<BigDecimal>,
        alias: String,
    ): Field<BigDecimal> {
        return DSL.coalesce(DSL.sum(field), BigDecimal.ZERO).`as`(alias)
    }

    private fun List<ProgressDateRange>.toDiaryDateCondition(): Condition {
        return fold(DSL.falseCondition() as Condition) { condition, range ->
            condition.or(DIARY_DAYS.DIARY_DATE.between(range.from, range.to))
        }
    }
}
