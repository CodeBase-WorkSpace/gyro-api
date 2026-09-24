package com.gyro.api.food.infrastructure

import com.gyro.api.common.error.InvalidServingUnitException
import com.gyro.api.jooq.Tables.FOODS
import com.gyro.api.jooq.Tables.FOOD_NUTRITION_FACTS
import com.gyro.api.jooq.Tables.FOOD_SEARCH_TERMS
import com.gyro.api.jooq.Tables.FOOD_SERVING_PORTIONS
import com.gyro.api.jooq.Tables.SERVING_UNITS
import com.gyro.api.food.web.dto.CreateCustomFoodRequest
import com.gyro.api.food.web.dto.CustomFoodPortionRequest
import com.gyro.api.food.web.dto.CustomFoodResponse
import com.gyro.api.food.web.dto.FoodSearchType
import com.gyro.api.food.web.dto.FoodServingPortionResponse
import com.gyro.api.food.web.dto.ServingUnitSummaryResponse
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

@Repository
class CustomFoodRepository(
    private val dsl: DSLContext,
) {
    fun countActiveOwnerCustomFoods(ownerUserId: UUID): Long {
        return dsl.selectCount()
            .from(FOODS)
            .where(
                FOODS.OWNER_USER_ID.eq(ownerUserId)
                    .and(FOODS.TYPE.eq(FoodSearchType.CUSTOM.name))
                    .and(FOODS.ARCHIVED_AT.isNull)
            )
            .fetchOne(0, Long::class.java) ?: 0L
    }

    fun servingUnitExists(servingUnitCode: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(SERVING_UNITS)
                .where(SERVING_UNITS.CODE.eq(servingUnitCode))
        )
    }

    fun create(
        ownerUserId: UUID,
        request: CreateCustomFoodRequest,
        normalizedName: String,
        locale: String,
    ): CustomFoodResponse {
        val publicId = "food_${UUID.randomUUID().toString().replace("-", "")}"

        val selectedUnit = dsl.select(SERVING_UNITS.ID, SERVING_UNITS.CODE)
            .from(SERVING_UNITS)
            .where(SERVING_UNITS.CODE.eq(request.servingUnit))
            .fetchOne() ?: throw InvalidServingUnitException()

        val insertedFood = dsl.insertInto(FOODS)
            .set(FOODS.PUBLIC_ID, publicId)
            .set(FOODS.OWNER_USER_ID, ownerUserId)
            .set(FOODS.TYPE, FoodSearchType.CUSTOM.name)
            .set(FOODS.NAME, request.name)
            .set(FOODS.NORMALIZED_NAME, normalizedName)
            .set(FOODS.SOURCE, "USER_CURATED")
            .set(FOODS.CURATION_STATUS, "REVIEWED")
            .set(FOODS.DATA_QUALITY, "USER_SUBMITTED")
            .set(FOODS.IS_SEARCHABLE, true)
            .set(FOODS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning(FOODS.ID, FOODS.PUBLIC_ID, FOODS.TYPE, FOODS.NAME)
            .fetchOne()
            ?: error("Custom food insert did not return a row.")

        val insertedNutrition = dsl.insertInto(FOOD_NUTRITION_FACTS)
            .set(FOOD_NUTRITION_FACTS.FOOD_ID, insertedFood.get(FOODS.ID))
            .set(FOOD_NUTRITION_FACTS.BASE_QUANTITY, request.servingQuantity)
            .set(FOOD_NUTRITION_FACTS.BASE_UNIT_ID, selectedUnit.get(SERVING_UNITS.ID))
            .set(FOOD_NUTRITION_FACTS.CALORIES, request.calories)
            .set(FOOD_NUTRITION_FACTS.PROTEIN, request.protein)
            .set(FOOD_NUTRITION_FACTS.CARBS, request.carbs)
            .set(FOOD_NUTRITION_FACTS.FAT, request.fat)
            .set(FOOD_NUTRITION_FACTS.FIBER, request.fiber)
            .set(FOOD_NUTRITION_FACTS.SUGAR, request.sugar)
            .set(FOOD_NUTRITION_FACTS.SODIUM, request.sodium)
            .set(FOOD_NUTRITION_FACTS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(FOOD_NUTRITION_FACTS.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning()
            .fetchOne()
            ?: error("Custom food nutrition insert did not return a row.")

        dsl.insertInto(FOOD_SEARCH_TERMS)
            .set(FOOD_SEARCH_TERMS.FOOD_ID, insertedFood.get(FOODS.ID))
            .set(FOOD_SEARCH_TERMS.TERM, insertedFood.get(FOODS.NAME))
            .set(FOOD_SEARCH_TERMS.NORMALIZED_TERM, normalizedName)
            .set(FOOD_SEARCH_TERMS.LOCALE, locale)
            .set(FOOD_SEARCH_TERMS.TERM_KIND, "NAME")
            .set(FOOD_SEARCH_TERMS.WEIGHT, BigDecimal.ONE)
            .set(
                FOOD_SEARCH_TERMS.SEARCH_VECTOR,
                DSL.field(
                    "to_tsvector(cast((case when {0} = 'fa' then 'simple' else 'english' end) as regconfig), {1})",
                    String::class.java,
                    DSL.inline(locale),
                    DSL.inline(normalizedName),
                )
            )
            .set(FOOD_SEARCH_TERMS.CREATED_AT, DSL.currentOffsetDateTime())
            .execute()

        insertPortions(insertedFood.get(FOODS.ID), request.portions)

        return CustomFoodResponse(
            id = insertedFood.get(FOODS.PUBLIC_ID),
            type = FoodSearchType.valueOf(insertedFood.get(FOODS.TYPE)),
            name = insertedFood.get(FOODS.NAME),
            servingQuantity = insertedNutrition.get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
            servingUnit = ServingUnitSummaryResponse(
                id = selectedUnit.get(SERVING_UNITS.ID).toString(),
                code = selectedUnit.get(SERVING_UNITS.CODE),
                label = selectedUnit.get(SERVING_UNITS.CODE),
            ),
            calories = insertedNutrition.get(FOOD_NUTRITION_FACTS.CALORIES),
            protein = insertedNutrition.get(FOOD_NUTRITION_FACTS.PROTEIN),
            carbs = insertedNutrition.get(FOOD_NUTRITION_FACTS.CARBS),
            fat = insertedNutrition.get(FOOD_NUTRITION_FACTS.FAT),
            fiber = insertedNutrition.get(FOOD_NUTRITION_FACTS.FIBER),
            sugar = insertedNutrition.get(FOOD_NUTRITION_FACTS.SUGAR),
            sodium = insertedNutrition.get(FOOD_NUTRITION_FACTS.SODIUM),
            portions = portionResponses(request.portions, locale),
        )
    }

    fun updateOwnerCustomFood(
        ownerUserId: UUID,
        foodId: String,
        request: CreateCustomFoodRequest,
        normalizedName: String,
        locale: String,
    ): CustomFoodResponse? {
        val selectedUnit = dsl.select(SERVING_UNITS.ID, SERVING_UNITS.CODE)
            .from(SERVING_UNITS)
            .where(SERVING_UNITS.CODE.eq(request.servingUnit))
            .fetchOne() ?: throw InvalidServingUnitException()

        val existingFood = dsl.select(FOODS.ID, FOODS.PUBLIC_ID, FOODS.TYPE)
            .from(FOODS)
            .where(
                FOODS.PUBLIC_ID.eq(foodId)
                    .and(FOODS.OWNER_USER_ID.eq(ownerUserId))
                    .and(FOODS.TYPE.eq(FoodSearchType.CUSTOM.name))
                    .and(FOODS.ARCHIVED_AT.isNull)
            )
            .forUpdate()
            .fetchOne() ?: return null

        dsl.update(FOODS)
            .set(FOODS.NAME, request.name)
            .set(FOODS.NORMALIZED_NAME, normalizedName)
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(FOODS.ID.eq(existingFood.get(FOODS.ID)))
            .execute()

        val updatedNutrition = dsl.update(FOOD_NUTRITION_FACTS)
            .set(FOOD_NUTRITION_FACTS.BASE_QUANTITY, request.servingQuantity)
            .set(FOOD_NUTRITION_FACTS.BASE_UNIT_ID, selectedUnit.get(SERVING_UNITS.ID))
            .set(FOOD_NUTRITION_FACTS.CALORIES, request.calories)
            .set(FOOD_NUTRITION_FACTS.PROTEIN, request.protein)
            .set(FOOD_NUTRITION_FACTS.CARBS, request.carbs)
            .set(FOOD_NUTRITION_FACTS.FAT, request.fat)
            .set(FOOD_NUTRITION_FACTS.FIBER, request.fiber)
            .set(FOOD_NUTRITION_FACTS.SUGAR, request.sugar)
            .set(FOOD_NUTRITION_FACTS.SODIUM, request.sodium)
            .set(FOOD_NUTRITION_FACTS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(FOOD_NUTRITION_FACTS.FOOD_ID.eq(existingFood.get(FOODS.ID)))
            .returning()
            .fetchOne()
            ?: error("Custom food nutrition update did not return a row.")

        dsl.deleteFrom(FOOD_SEARCH_TERMS)
            .where(
                FOOD_SEARCH_TERMS.FOOD_ID.eq(existingFood.get(FOODS.ID))
                    .and(FOOD_SEARCH_TERMS.TERM_KIND.eq("NAME"))
            )
            .execute()

        dsl.insertInto(FOOD_SEARCH_TERMS)
            .set(FOOD_SEARCH_TERMS.FOOD_ID, existingFood.get(FOODS.ID))
            .set(FOOD_SEARCH_TERMS.TERM, request.name)
            .set(FOOD_SEARCH_TERMS.NORMALIZED_TERM, normalizedName)
            .set(FOOD_SEARCH_TERMS.LOCALE, locale)
            .set(FOOD_SEARCH_TERMS.TERM_KIND, "NAME")
            .set(FOOD_SEARCH_TERMS.WEIGHT, BigDecimal.ONE)
            .set(
                FOOD_SEARCH_TERMS.SEARCH_VECTOR,
                DSL.field(
                    "to_tsvector(cast((case when {0} = 'fa' then 'simple' else 'english' end) as regconfig), {1})",
                    String::class.java,
                    DSL.inline(locale),
                    DSL.inline(normalizedName),
                )
            )
            .set(FOOD_SEARCH_TERMS.CREATED_AT, DSL.currentOffsetDateTime())
            .execute()

        dsl.deleteFrom(FOOD_SERVING_PORTIONS)
            .where(FOOD_SERVING_PORTIONS.FOOD_ID.eq(existingFood.get(FOODS.ID)))
            .execute()
        insertPortions(existingFood.get(FOODS.ID), request.portions)

        val updatedFood = dsl.select(FOODS.PUBLIC_ID, FOODS.TYPE, FOODS.NAME)
            .from(FOODS)
            .where(FOODS.ID.eq(existingFood.get(FOODS.ID)))
            .fetchOne() ?: error("Custom food update did not return a row.")

        return updatedFood.toCustomFoodResponse(
            nutrition = updatedNutrition,
            servingUnitId = selectedUnit.get(SERVING_UNITS.ID),
            servingUnitCode = selectedUnit.get(SERVING_UNITS.CODE),
            portions = portionResponses(request.portions, locale),
        )
    }

    private fun insertPortions(foodId: UUID, portions: List<CustomFoodPortionRequest>) {
        portions.forEachIndexed { index, portion ->
            dsl.insertInto(FOOD_SERVING_PORTIONS)
                .set(FOOD_SERVING_PORTIONS.FOOD_ID, foodId)
                .set(FOOD_SERVING_PORTIONS.AMOUNT, BigDecimal.ONE)
                .set(FOOD_SERVING_PORTIONS.GRAM_WEIGHT, portion.gramWeight)
                .set(FOOD_SERVING_PORTIONS.RAW_UNIT_NAME, portion.name)
                .set(FOOD_SERVING_PORTIONS.SORT_ORDER, index)
                .set(FOOD_SERVING_PORTIONS.CREATED_AT, DSL.currentOffsetDateTime())
                .set(FOOD_SERVING_PORTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .execute()
        }
    }

    private fun portionResponses(
        portions: List<CustomFoodPortionRequest>,
        locale: String,
    ): List<FoodServingPortionResponse> {
        return portions.map { portion ->
            FoodServingPortionResponse(
                amount = BigDecimal.ONE,
                unitName = portion.name,
                unitAbbreviation = null,
                modifier = null,
                gramWeight = portion.gramWeight,
                displayText = localizedPortionDisplayText(
                    locale = locale,
                    amount = BigDecimal.ONE,
                    unitName = portion.name,
                    modifier = null,
                    gramWeight = portion.gramWeight,
                    portionDescription = null,
                ),
            )
        }
    }

    fun archiveOwnerCustomFood(ownerUserId: UUID, foodId: String): Boolean {
        return dsl.update(FOODS)
            .set(FOODS.ARCHIVED_AT, DSL.currentOffsetDateTime())
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                FOODS.PUBLIC_ID.eq(foodId)
                    .and(FOODS.OWNER_USER_ID.eq(ownerUserId))
                    .and(FOODS.TYPE.eq(FoodSearchType.CUSTOM.name))
                    .and(FOODS.ARCHIVED_AT.isNull)
            )
            .execute() == 1
    }

    private fun Record.toCustomFoodResponse(
        nutrition: Record,
        servingUnitId: UUID,
        servingUnitCode: String,
        portions: List<FoodServingPortionResponse>,
    ): CustomFoodResponse {
        return CustomFoodResponse(
            id = get(FOODS.PUBLIC_ID),
            type = FoodSearchType.valueOf(get(FOODS.TYPE)),
            name = get(FOODS.NAME),
            servingQuantity = nutrition.get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
            servingUnit = ServingUnitSummaryResponse(
                id = servingUnitId.toString(),
                code = servingUnitCode,
                label = servingUnitCode,
            ),
            calories = nutrition.get(FOOD_NUTRITION_FACTS.CALORIES),
            protein = nutrition.get(FOOD_NUTRITION_FACTS.PROTEIN),
            carbs = nutrition.get(FOOD_NUTRITION_FACTS.CARBS),
            fat = nutrition.get(FOOD_NUTRITION_FACTS.FAT),
            fiber = nutrition.get(FOOD_NUTRITION_FACTS.FIBER),
            sugar = nutrition.get(FOOD_NUTRITION_FACTS.SUGAR),
            sodium = nutrition.get(FOOD_NUTRITION_FACTS.SODIUM),
            portions = portions,
        )
    }
}
