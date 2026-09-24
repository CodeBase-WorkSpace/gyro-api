package com.gyro.api.food.infrastructure

import com.gyro.api.jooq.Tables.FOOD_NUTRITION_FACTS
import com.gyro.api.jooq.Tables.FOOD_LOCALIZATIONS
import com.gyro.api.jooq.Tables.RECENT_FOODS
import com.gyro.api.jooq.Tables.FOODS
import com.gyro.api.jooq.Tables.MEALS
import com.gyro.api.jooq.Tables.MEAL_ITEMS
import com.gyro.api.jooq.Tables.SERVING_UNITS
import com.gyro.api.food.domain.ServingDefinition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class MealRepository(
    private val dsl: DSLContext,
) {
    fun countOwnerMeals(
        ownerUserId: UUID,
        normalizedQuery: String?,
    ): Long {
        return dsl.selectCount()
            .from(MEALS)
            .where(ownerMealCondition(ownerUserId, normalizedQuery))
            .fetchOne(0, Long::class.java) ?: 0L
    }

    fun findOwnerMeals(
        ownerUserId: UUID,
        normalizedQuery: String?,
        limit: Int,
        offset: Int,
    ): List<MealRecord> {
        return dsl.select(
            MEALS.ID,
            MEALS.NAME,
            MEALS.CREATED_AT,
            MEALS.TOTAL_BATCH_WEIGHT,
            MEALS.SERVING_WEIGHT,
        )
            .from(MEALS)
            .where(ownerMealCondition(ownerUserId, normalizedQuery))
            .orderBy(MEALS.CREATED_AT.desc(), MEALS.ID.asc())
            .limit(limit)
            .offset(offset)
            .fetch { record -> record.toMealRecord() }
    }

    fun findOwnerMeal(
        ownerUserId: UUID,
        mealId: UUID,
    ): MealRecord? {
        return dsl.select(
            MEALS.ID,
            MEALS.NAME,
            MEALS.CREATED_AT,
            MEALS.TOTAL_BATCH_WEIGHT,
            MEALS.SERVING_WEIGHT,
        )
            .from(MEALS)
            .where(
                MEALS.ID.eq(mealId)
                    .and(MEALS.OWNER_USER_ID.eq(ownerUserId))
                    .and(MEALS.ARCHIVED_AT.isNull)
            )
            .fetchOne { record -> record.toMealRecord() }
    }

    fun findMealNutritionItems(
        ownerUserId: UUID,
        mealIds: Set<UUID>,
    ): Map<UUID, List<MealNutritionItemRecord>> {
        if (mealIds.isEmpty()) {
            return emptyMap()
        }

        val selectedUnit = SERVING_UNITS.`as`("selected_unit")
        val baseUnit = SERVING_UNITS.`as`("base_unit")

        return dsl.select(
            MEAL_ITEMS.ID,
            MEAL_ITEMS.MEAL_ID,
            MEAL_ITEMS.QUANTITY,
            selectedUnit.ID,
            selectedUnit.CODE,
            selectedUnit.GRAM_MULTIPLIER,
            selectedUnit.MILLILITER_MULTIPLIER,
            FOODS.ID,
            FOODS.NAME,
            FOOD_NUTRITION_FACTS.BASE_QUANTITY,
            baseUnit.ID,
            baseUnit.CODE,
            baseUnit.GRAM_MULTIPLIER,
            baseUnit.MILLILITER_MULTIPLIER,
            FOOD_NUTRITION_FACTS.CALORIES,
            FOOD_NUTRITION_FACTS.PROTEIN,
            FOOD_NUTRITION_FACTS.CARBS,
            FOOD_NUTRITION_FACTS.FAT,
            FOOD_NUTRITION_FACTS.FIBER,
            FOOD_NUTRITION_FACTS.SUGAR,
            FOOD_NUTRITION_FACTS.SODIUM,
        )
            .from(MEAL_ITEMS)
            .join(MEALS).on(MEALS.ID.eq(MEAL_ITEMS.MEAL_ID))
            .join(selectedUnit).on(selectedUnit.ID.eq(MEAL_ITEMS.SERVING_UNIT_ID))
            .join(FOODS).on(FOODS.ID.eq(MEAL_ITEMS.FOOD_ID))
            .join(FOOD_NUTRITION_FACTS).on(FOOD_NUTRITION_FACTS.FOOD_ID.eq(FOODS.ID))
            .join(baseUnit).on(baseUnit.ID.eq(FOOD_NUTRITION_FACTS.BASE_UNIT_ID))
            .where(
                MEALS.OWNER_USER_ID.eq(ownerUserId)
                    .and(MEALS.ARCHIVED_AT.isNull)
                    .and(MEAL_ITEMS.MEAL_ID.`in`(mealIds))
                    .and(FOODS.ARCHIVED_AT.isNull)
                    .and(FOODS.IS_SEARCHABLE.isTrue)
                    .and(FOODS.CURATION_STATUS.ne("HIDDEN"))
                    .and(FOODS.TYPE.eq("SYSTEM").or(FOODS.OWNER_USER_ID.eq(ownerUserId)))
            )
            .orderBy(MEAL_ITEMS.MEAL_ID.asc(), MEAL_ITEMS.SORT_ORDER.asc(), MEAL_ITEMS.ID.asc())
            .fetchGroups(
                { record -> record.get(MEAL_ITEMS.MEAL_ID) },
                { record ->
                    record.toMealNutritionItem(
                        selectedUnit = selectedUnit,
                        baseUnit = baseUnit,
                    )
                },
            )
    }

    fun findServingUnitsByCode(codes: Set<String>): Map<String, MealServingUnitRecord> {
        if (codes.isEmpty()) {
            return emptyMap()
        }

        return dsl.select(
            SERVING_UNITS.ID,
            SERVING_UNITS.CODE,
            SERVING_UNITS.GRAM_MULTIPLIER,
            SERVING_UNITS.MILLILITER_MULTIPLIER,
        )
            .from(SERVING_UNITS)
            .where(SERVING_UNITS.CODE.`in`(codes).and(SERVING_UNITS.IS_ACTIVE.isTrue))
            .fetch { record ->
                MealServingUnitRecord(
                    id = record.get(SERVING_UNITS.ID),
                    code = record.get(SERVING_UNITS.CODE),
                    gramMultiplier = record.get(SERVING_UNITS.GRAM_MULTIPLIER),
                    milliliterMultiplier = record.get(SERVING_UNITS.MILLILITER_MULTIPLIER),
                )
            }
            .associateBy { it.code }
    }

    fun findServingUnitsById(ids: Set<UUID>): Map<UUID, MealServingUnitRecord> {
        if (ids.isEmpty()) return emptyMap()

        return dsl.select(
            SERVING_UNITS.ID,
            SERVING_UNITS.CODE,
            SERVING_UNITS.GRAM_MULTIPLIER,
            SERVING_UNITS.MILLILITER_MULTIPLIER,
        )
            .from(SERVING_UNITS)
            .where(SERVING_UNITS.ID.`in`(ids).and(SERVING_UNITS.IS_ACTIVE.isTrue))
            .fetch { record ->
                MealServingUnitRecord(
                    id = record.get(SERVING_UNITS.ID),
                    code = record.get(SERVING_UNITS.CODE),
                    gramMultiplier = record.get(SERVING_UNITS.GRAM_MULTIPLIER),
                    milliliterMultiplier = record.get(SERVING_UNITS.MILLILITER_MULTIPLIER),
                )
            }
            .associateBy { it.id }
    }

    fun findVisibleFoodNutrition(
        ownerUserId: UUID,
        foodIds: Set<UUID>,
        locale: String? = null,
    ): Map<UUID, MealFoodNutritionRecord> {
        if (foodIds.isEmpty()) {
            return emptyMap()
        }

        val baseUnit = SERVING_UNITS.`as`("base_unit")
        val fl = FOOD_LOCALIZATIONS.`as`("food_locale")
        val displayName = DSL.coalesce(fl.DISPLAY_NAME, FOODS.NAME)

        return dsl.select(
            FOODS.ID,
            displayName,
            FOOD_NUTRITION_FACTS.BASE_QUANTITY,
            baseUnit.ID,
            baseUnit.CODE,
            baseUnit.GRAM_MULTIPLIER,
            baseUnit.MILLILITER_MULTIPLIER,
            FOOD_NUTRITION_FACTS.CALORIES,
            FOOD_NUTRITION_FACTS.PROTEIN,
            FOOD_NUTRITION_FACTS.CARBS,
            FOOD_NUTRITION_FACTS.FAT,
            FOOD_NUTRITION_FACTS.FIBER,
            FOOD_NUTRITION_FACTS.SUGAR,
            FOOD_NUTRITION_FACTS.SODIUM,
        )
            .from(FOODS)
            .join(FOOD_NUTRITION_FACTS).on(FOOD_NUTRITION_FACTS.FOOD_ID.eq(FOODS.ID))
            .join(baseUnit).on(baseUnit.ID.eq(FOOD_NUTRITION_FACTS.BASE_UNIT_ID))
            .leftJoin(fl).on(
                fl.FOOD_ID.eq(FOODS.ID)
                    .and(fl.LOCALE.eq(locale ?: "en"))
                    .and(fl.REVIEW_STATUS.eq("REVIEWED"))
            )
            .where(
                FOODS.ID.`in`(foodIds)
                    .and(FOODS.ARCHIVED_AT.isNull)
                    .and(FOODS.IS_SEARCHABLE.isTrue)
                    .and(FOODS.CURATION_STATUS.ne("HIDDEN"))
                    .and(FOODS.TYPE.eq("SYSTEM").or(FOODS.OWNER_USER_ID.eq(ownerUserId)))
            )
            .fetch { record ->
                MealFoodNutritionRecord(
                    foodId = record.get(FOODS.ID),
                    foodName = record.get(displayName),
                    baseQuantity = record.get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
                    baseUnit = MealServingUnitRecord(
                        id = record.get(baseUnit.ID),
                        code = record.get(baseUnit.CODE),
                        gramMultiplier = record.get(baseUnit.GRAM_MULTIPLIER),
                        milliliterMultiplier = record.get(baseUnit.MILLILITER_MULTIPLIER),
                    ),
                    calories = record.get(FOOD_NUTRITION_FACTS.CALORIES),
                    protein = record.get(FOOD_NUTRITION_FACTS.PROTEIN),
                    carbs = record.get(FOOD_NUTRITION_FACTS.CARBS),
                    fat = record.get(FOOD_NUTRITION_FACTS.FAT),
                    fiber = record.get(FOOD_NUTRITION_FACTS.FIBER),
                    sugar = record.get(FOOD_NUTRITION_FACTS.SUGAR),
                    sodium = record.get(FOOD_NUTRITION_FACTS.SODIUM),
                )
            }
            .associateBy { it.foodId }
    }

    fun findVisibleFoodNutritionByPublicId(
        ownerUserId: UUID,
        publicId: String,
        locale: String? = null,
    ): MealFoodNutritionRecord? {
        val foodId = dsl.select(FOODS.ID)
            .from(FOODS)
            .where(
                FOODS.PUBLIC_ID.eq(publicId)
                    .and(FOODS.ARCHIVED_AT.isNull)
                    .and(FOODS.IS_SEARCHABLE.isTrue)
                    .and(FOODS.CURATION_STATUS.ne("HIDDEN"))
                    .and(FOODS.TYPE.eq("SYSTEM").or(FOODS.OWNER_USER_ID.eq(ownerUserId)))
            )
            .fetchOne(FOODS.ID)
            ?: return null

        return findVisibleFoodNutrition(ownerUserId, setOf(foodId), locale)[foodId]
    }

    fun findVisibleFoodNutritionByPublicIds(
        ownerUserId: UUID,
        publicIds: Set<String>,
        locale: String? = null,
    ): Map<String, MealFoodNutritionRecord> {
        if (publicIds.isEmpty()) return emptyMap()

        val baseUnit = SERVING_UNITS.`as`("base_unit")
        val fl = FOOD_LOCALIZATIONS.`as`("food_locale")
        val displayName = DSL.coalesce(fl.DISPLAY_NAME, FOODS.NAME)

        return dsl.select(
            FOODS.PUBLIC_ID,
            FOODS.ID,
            displayName,
            FOOD_NUTRITION_FACTS.BASE_QUANTITY,
            baseUnit.ID,
            baseUnit.CODE,
            baseUnit.GRAM_MULTIPLIER,
            baseUnit.MILLILITER_MULTIPLIER,
            FOOD_NUTRITION_FACTS.CALORIES,
            FOOD_NUTRITION_FACTS.PROTEIN,
            FOOD_NUTRITION_FACTS.CARBS,
            FOOD_NUTRITION_FACTS.FAT,
            FOOD_NUTRITION_FACTS.FIBER,
            FOOD_NUTRITION_FACTS.SUGAR,
            FOOD_NUTRITION_FACTS.SODIUM,
        )
            .from(FOODS)
            .join(FOOD_NUTRITION_FACTS).on(FOOD_NUTRITION_FACTS.FOOD_ID.eq(FOODS.ID))
            .join(baseUnit).on(baseUnit.ID.eq(FOOD_NUTRITION_FACTS.BASE_UNIT_ID))
            .leftJoin(fl).on(
                fl.FOOD_ID.eq(FOODS.ID)
                    .and(fl.LOCALE.eq(locale ?: "en"))
                    .and(fl.REVIEW_STATUS.eq("REVIEWED"))
            )
            .where(
                FOODS.PUBLIC_ID.`in`(publicIds)
                    .and(FOODS.ARCHIVED_AT.isNull)
                    .and(FOODS.IS_SEARCHABLE.isTrue)
                    .and(FOODS.CURATION_STATUS.ne("HIDDEN"))
                    .and(FOODS.TYPE.eq("SYSTEM").or(FOODS.OWNER_USER_ID.eq(ownerUserId)))
            )
            .fetch { record ->
                record.get(FOODS.PUBLIC_ID) to MealFoodNutritionRecord(
                    foodId = record.get(FOODS.ID),
                    foodName = record.get(displayName),
                    baseQuantity = record.get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
                    baseUnit = MealServingUnitRecord(
                        id = record.get(baseUnit.ID),
                        code = record.get(baseUnit.CODE),
                        gramMultiplier = record.get(baseUnit.GRAM_MULTIPLIER),
                        milliliterMultiplier = record.get(baseUnit.MILLILITER_MULTIPLIER),
                    ),
                    calories = record.get(FOOD_NUTRITION_FACTS.CALORIES),
                    protein = record.get(FOOD_NUTRITION_FACTS.PROTEIN),
                    carbs = record.get(FOOD_NUTRITION_FACTS.CARBS),
                    fat = record.get(FOOD_NUTRITION_FACTS.FAT),
                    fiber = record.get(FOOD_NUTRITION_FACTS.FIBER),
                    sugar = record.get(FOOD_NUTRITION_FACTS.SUGAR),
                    sodium = record.get(FOOD_NUTRITION_FACTS.SODIUM),
                )
            }
            .toMap()
    }

    fun markFoodsRecent(ownerUserId: UUID, foodIds: Set<UUID>) {
        if (foodIds.isEmpty()) return

        foodIds.forEach { foodId ->
            dsl.insertInto(RECENT_FOODS)
                .set(RECENT_FOODS.USER_ID, ownerUserId)
                .set(RECENT_FOODS.FOOD_ID, foodId)
                .set(RECENT_FOODS.LAST_USED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .set(RECENT_FOODS.USE_COUNT, 1)
                .set(RECENT_FOODS.CREATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .set(RECENT_FOODS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .onConflict(RECENT_FOODS.USER_ID, RECENT_FOODS.FOOD_ID)
                .doUpdate()
                .set(RECENT_FOODS.LAST_USED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .set(RECENT_FOODS.USE_COUNT, RECENT_FOODS.USE_COUNT.plus(1))
                .set(RECENT_FOODS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .execute()
        }
    }

    fun createMeal(
        ownerUserId: UUID,
        name: String,
        normalizedName: String,
        items: List<PreparedMealItem>,
        servingDefinition: ServingDefinition? = null,
    ): MealInsertResult {
        val meal = dsl.insertInto(MEALS)
            .set(MEALS.OWNER_USER_ID, ownerUserId)
            .set(MEALS.NAME, name)
            .set(MEALS.NORMALIZED_NAME, normalizedName)
            .set(MEALS.TOTAL_BATCH_WEIGHT, servingDefinition?.totalBatchWeight)
            .set(MEALS.SERVING_WEIGHT, servingDefinition?.servingWeight)
            .set(MEALS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(MEALS.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning(MEALS.ID)
            .fetchOne()
            ?: error("Meal insert did not return a row.")

        val mealId = meal.get(MEALS.ID)
        val itemIds = items.mapIndexed { index, item ->
            dsl.insertInto(MEAL_ITEMS)
                .set(MEAL_ITEMS.MEAL_ID, mealId)
                .set(MEAL_ITEMS.FOOD_ID, item.foodId)
                .set(MEAL_ITEMS.QUANTITY, item.quantity)
                .set(MEAL_ITEMS.SERVING_UNIT_ID, item.servingUnit.id)
                .set(MEAL_ITEMS.SORT_ORDER, index)
                .set(MEAL_ITEMS.CREATED_AT, DSL.currentOffsetDateTime())
                .set(MEAL_ITEMS.UPDATED_AT, DSL.currentOffsetDateTime())
                .returning(MEAL_ITEMS.ID)
                .fetchOne()
                ?.get(MEAL_ITEMS.ID)
                ?: error("Meal item insert did not return a row.")
        }

        return MealInsertResult(
            mealId = mealId,
            itemIds = itemIds,
        )
    }

    fun replaceOwnerMealItems(
        ownerUserId: UUID,
        mealId: UUID,
        name: String,
        normalizedName: String,
        items: List<PreparedMealItem>,
        servingDefinition: ServingDefinition? = null,
    ): List<UUID>? {
        val updatedMeal = dsl.update(MEALS)
            .set(MEALS.NAME, name)
            .set(MEALS.NORMALIZED_NAME, normalizedName)
            .set(MEALS.TOTAL_BATCH_WEIGHT, servingDefinition?.totalBatchWeight)
            .set(MEALS.SERVING_WEIGHT, servingDefinition?.servingWeight)
            .set(MEALS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                MEALS.ID.eq(mealId)
                    .and(MEALS.OWNER_USER_ID.eq(ownerUserId))
                    .and(MEALS.ARCHIVED_AT.isNull)
            )
            .returning(MEALS.ID)
            .fetchOne()
            ?: return null

        dsl.deleteFrom(MEAL_ITEMS)
            .where(MEAL_ITEMS.MEAL_ID.eq(updatedMeal.get(MEALS.ID)))
            .execute()

        return items.mapIndexed { index, item ->
            dsl.insertInto(MEAL_ITEMS)
                .set(MEAL_ITEMS.MEAL_ID, mealId)
                .set(MEAL_ITEMS.FOOD_ID, item.foodId)
                .set(MEAL_ITEMS.QUANTITY, item.quantity)
                .set(MEAL_ITEMS.SERVING_UNIT_ID, item.servingUnit.id)
                .set(MEAL_ITEMS.SORT_ORDER, index)
                .set(MEAL_ITEMS.CREATED_AT, DSL.currentOffsetDateTime())
                .set(MEAL_ITEMS.UPDATED_AT, DSL.currentOffsetDateTime())
                .returning(MEAL_ITEMS.ID)
                .fetchOne()
                ?.get(MEAL_ITEMS.ID)
                ?: error("Meal item insert did not return a row.")
        }
    }

    fun archiveOwnerMeal(ownerUserId: UUID, mealId: UUID): Boolean {
        return dsl.update(MEALS)
            .set(MEALS.ARCHIVED_AT, DSL.currentOffsetDateTime())
            .set(MEALS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                MEALS.ID.eq(mealId)
                    .and(MEALS.OWNER_USER_ID.eq(ownerUserId))
                    .and(MEALS.ARCHIVED_AT.isNull)
            )
            .execute() == 1
    }

    private fun Record.toMealRecord(): MealRecord {
        return MealRecord(
            id = get(MEALS.ID),
            name = get(MEALS.NAME),
            createdAt = get(MEALS.CREATED_AT),
            servingDefinition = ServingDefinition.ofNullable(
                totalBatchWeight = get(MEALS.TOTAL_BATCH_WEIGHT),
                servingWeight = get(MEALS.SERVING_WEIGHT),
            ),
        )
    }

    private fun ownerMealCondition(
        ownerUserId: UUID,
        normalizedQuery: String?,
    ) = MEALS.OWNER_USER_ID.eq(ownerUserId)
        .and(MEALS.ARCHIVED_AT.isNull)
        .and(
            normalizedQuery
                ?.takeIf { it.isNotBlank() }
                ?.let { MEALS.NORMALIZED_NAME.contains(it) }
                ?: DSL.trueCondition()
        )

    private fun Record.toMealNutritionItem(
        selectedUnit: com.gyro.api.jooq.tables.ServingUnits,
        baseUnit: com.gyro.api.jooq.tables.ServingUnits,
    ): MealNutritionItemRecord {
        return MealNutritionItemRecord(
            id = get(MEAL_ITEMS.ID),
            quantity = get(MEAL_ITEMS.QUANTITY),
            selectedUnit = MealServingUnitRecord(
                id = get(selectedUnit.ID),
                code = get(selectedUnit.CODE),
                gramMultiplier = get(selectedUnit.GRAM_MULTIPLIER),
                milliliterMultiplier = get(selectedUnit.MILLILITER_MULTIPLIER),
            ),
            foodNutrition = MealFoodNutritionRecord(
                foodId = get(FOODS.ID),
                foodName = get(FOODS.NAME),
                baseQuantity = get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
                baseUnit = MealServingUnitRecord(
                    id = get(baseUnit.ID),
                    code = get(baseUnit.CODE),
                    gramMultiplier = get(baseUnit.GRAM_MULTIPLIER),
                    milliliterMultiplier = get(baseUnit.MILLILITER_MULTIPLIER),
                ),
                calories = get(FOOD_NUTRITION_FACTS.CALORIES),
                protein = get(FOOD_NUTRITION_FACTS.PROTEIN),
                carbs = get(FOOD_NUTRITION_FACTS.CARBS),
                fat = get(FOOD_NUTRITION_FACTS.FAT),
                fiber = get(FOOD_NUTRITION_FACTS.FIBER),
                sugar = get(FOOD_NUTRITION_FACTS.SUGAR),
                sodium = get(FOOD_NUTRITION_FACTS.SODIUM),
            ),
        )
    }
}

data class MealRecord(
    val id: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
    val servingDefinition: ServingDefinition? = null,
)

data class MealNutritionItemRecord(
    val id: UUID,
    val quantity: BigDecimal,
    val selectedUnit: MealServingUnitRecord,
    val foodNutrition: MealFoodNutritionRecord,
)

data class MealServingUnitRecord(
    val id: UUID,
    val code: String,
    val gramMultiplier: BigDecimal?,
    val milliliterMultiplier: BigDecimal?,
)

data class MealFoodNutritionRecord(
    val foodId: UUID,
    val foodName: String,
    val baseQuantity: BigDecimal,
    val baseUnit: MealServingUnitRecord,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class PreparedMealItem(
    val foodId: UUID,
    val quantity: BigDecimal,
    val servingUnit: MealServingUnitRecord,
)

data class MealInsertResult(
    val mealId: UUID,
    val itemIds: List<UUID>,
)
