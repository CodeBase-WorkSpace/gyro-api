package com.gyro.api.food.infrastructure

import com.gyro.api.food.web.dto.FoodDetailResponse
import com.gyro.api.food.web.dto.FoodSearchType
import com.gyro.api.food.web.dto.FoodServingPortionResponse
import com.gyro.api.common.id.UuidParser
import com.gyro.api.food.web.dto.ServingUnitSummaryResponse
import com.gyro.api.jooq.Tables.FOOD_FAVORITES
import com.gyro.api.jooq.Tables.FOOD_LOCALIZATIONS
import com.gyro.api.jooq.Tables.FOOD_NUTRITION_FACTS
import com.gyro.api.jooq.Tables.FOOD_SERVING_PORTIONS
import com.gyro.api.jooq.Tables.FOODS
import com.gyro.api.jooq.Tables.RECENT_FOODS
import com.gyro.api.jooq.Tables.SERVING_UNIT_ALIASES
import com.gyro.api.jooq.Tables.SERVING_UNIT_LOCALIZATIONS
import com.gyro.api.jooq.Tables.SERVING_UNITS
import com.gyro.api.jooq.tables.FoodNutritionFacts
import com.gyro.api.jooq.tables.Foods
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

@Repository
class FoodDetailRepository(
    private val dsl: DSLContext,
) {
    fun findVisibleFoodDetail(
        currentUserId: UUID,
        foodId: String,
        requestedLocale: String?,
    ): FoodDetailResponse? {
        val locale = requestedLocale ?: "fa"
        val f = FOODS.`as`("f")
        val nf = FOOD_NUTRITION_FACTS.`as`("nf")
        val su = SERVING_UNITS.`as`("su")
        val fl = FOOD_LOCALIZATIONS.`as`("fl")
        val sul = SERVING_UNIT_LOCALIZATIONS.`as`("sul")
        val sua = SERVING_UNIT_ALIASES.`as`("sua")
        val fav = FOOD_FAVORITES.`as`("fav")
        val recent = RECENT_FOODS.`as`("recent")

        val displayName = DSL.coalesce(fl.DISPLAY_NAME, f.NAME).`as`("display_name")
        val displayLocale = DSL.coalesce(fl.LOCALE, DSL.inline("en")).`as`("display_locale")
        val servingUnitLabel = DSL.coalesce(sul.DISPLAY_NAME, sua.ALIAS, su.CODE).`as`("serving_unit_label")
        val isFavorite = fav.FOOD_ID.isNotNull.`as`("is_favorite")
        val isRecent = recent.FOOD_ID.isNotNull.`as`("is_recent")

        val base = dsl.select(
            f.PUBLIC_ID,
            f.TYPE,
            f.NAME,
            displayName,
            displayLocale,
            nf.BASE_QUANTITY,
            su.ID,
            su.CODE,
            servingUnitLabel,
            nf.CALORIES,
            nf.PROTEIN,
            nf.CARBS,
            nf.FAT,
            nf.FIBER,
            nf.SUGAR,
            nf.SODIUM,
            isFavorite,
            isRecent,
            f.SOURCE,
            f.DATA_QUALITY,
        )
            .from(f)
            .join(nf).on(nf.FOOD_ID.eq(f.ID))
            .join(su).on(su.ID.eq(nf.BASE_UNIT_ID))
            .leftJoin(fl).on(
                fl.FOOD_ID.eq(f.ID)
                    .and(fl.LOCALE.eq(locale))
                    .and(fl.REVIEW_STATUS.eq("REVIEWED"))
            )
            .leftJoin(sul).on(
                sul.SERVING_UNIT_ID.eq(su.ID)
                    .and(sul.LOCALE.eq(locale))
                    .and(sul.REVIEW_STATUS.eq("REVIEWED"))
            )
            .leftJoin(sua).on(
                sua.SERVING_UNIT_ID.eq(su.ID)
                    .and(sua.LOCALE.eq(locale))
                    .and(sua.IS_PRIMARY.isTrue)
            )
            .leftJoin(fav).on(fav.FOOD_ID.eq(f.ID).and(fav.USER_ID.eq(currentUserId)))
            .leftJoin(recent).on(recent.FOOD_ID.eq(f.ID).and(recent.USER_ID.eq(currentUserId)))
            .where(visibleFoodCondition(f, foodId, currentUserId))
            .limit(1)
            .fetchOne()
            ?.toFoodDetailBase(
                f = f,
                nf = nf,
                suId = su.ID,
                suCode = su.CODE,
                displayName = displayName,
                displayLocale = displayLocale,
                servingUnitLabel = servingUnitLabel,
                isFavorite = isFavorite,
                isRecent = isRecent,
            ) ?: return null

        val portions = fetchPortions(
            currentUserId = currentUserId,
            foodId = foodId,
            locale = locale,
        )

        return base.copy(portions = portions)
    }

    private fun fetchPortions(
        currentUserId: UUID,
        foodId: String,
        locale: String,
    ): List<FoodServingPortionResponse> {
        val f = FOODS.`as`("f")
        val fsp = FOOD_SERVING_PORTIONS.`as`("fsp")
        val su = SERVING_UNITS.`as`("su")
        val sul = SERVING_UNIT_LOCALIZATIONS.`as`("sul")
        val sua = SERVING_UNIT_ALIASES.`as`("sua")
        val unitName = DSL.coalesce(sul.DISPLAY_NAME, sua.ALIAS, fsp.RAW_UNIT_NAME, su.CODE).`as`("unit_name")

        return dsl.select(
            fsp.AMOUNT,
            unitName,
            su.ID,
            su.CODE,
            fsp.MODIFIER,
            fsp.GRAM_WEIGHT,
            fsp.PORTION_DESCRIPTION,
        )
            .from(f)
            .join(fsp).on(fsp.FOOD_ID.eq(f.ID))
            .leftJoin(su).on(su.ID.eq(fsp.SERVING_UNIT_ID))
            .leftJoin(sul).on(
                sul.SERVING_UNIT_ID.eq(su.ID)
                    .and(sul.LOCALE.eq(locale))
                    .and(sul.REVIEW_STATUS.eq("REVIEWED"))
            )
            .leftJoin(sua).on(
                sua.SERVING_UNIT_ID.eq(su.ID)
                    .and(sua.LOCALE.eq(locale))
                    .and(sua.IS_PRIMARY.isTrue)
            )
            .where(visibleFoodCondition(f, foodId, currentUserId))
            .orderBy(fsp.SORT_ORDER.asc(), fsp.AMOUNT.asc(), fsp.ID.asc())
            .fetch { record ->
                record.toFoodServingPortion(
                    locale = locale,
                    amount = fsp.AMOUNT,
                    unitName = unitName,
                    unitAbbreviation = su.CODE,
                    modifier = fsp.MODIFIER,
                    gramWeight = fsp.GRAM_WEIGHT,
                    portionDescription = fsp.PORTION_DESCRIPTION,
                    servingUnitId = su.ID,
                    servingUnitCode = su.CODE,
                )
            }
    }

    private fun visibleFoodCondition(
        f: Foods,
        foodId: String,
        currentUserId: UUID,
    ) = f.PUBLIC_ID.eq(foodId)
        .or(UuidParser.parse(foodId)?.let(f.ID::eq) ?: DSL.falseCondition())
        .and(f.ARCHIVED_AT.isNull)
        .and(f.IS_SEARCHABLE.isTrue)
        .and(f.CURATION_STATUS.ne("HIDDEN"))
        .and(f.TYPE.eq(FoodSearchType.SYSTEM.name).or(f.OWNER_USER_ID.eq(currentUserId)))

    private fun Record.toFoodDetailBase(
        f: Foods,
        nf: FoodNutritionFacts,
        suId: Field<UUID>,
        suCode: Field<String>,
        displayName: Field<String>,
        displayLocale: Field<String>,
        servingUnitLabel: Field<String>,
        isFavorite: Field<Boolean>,
        isRecent: Field<Boolean>,
    ): FoodDetailResponse {
        val servingUnitCode = get(suCode) ?: ""

        return FoodDetailResponse(
            id = get(f.PUBLIC_ID) ?: "",
            type = FoodSearchType.valueOf(get(f.TYPE) ?: FoodSearchType.SYSTEM.name),
            name = get(f.NAME) ?: "",
            displayName = get(displayName) ?: "",
            locale = get(displayLocale) ?: "en",
            servingQuantity = get(nf.BASE_QUANTITY) ?: BigDecimal.ZERO,
            servingUnit = ServingUnitSummaryResponse(
                id = requireNotNull(get(suId)).toString(),
                code = servingUnitCode,
                label = get(servingUnitLabel) ?: servingUnitCode,
            ),
            calories = get(nf.CALORIES) ?: BigDecimal.ZERO,
            protein = get(nf.PROTEIN) ?: BigDecimal.ZERO,
            carbs = get(nf.CARBS) ?: BigDecimal.ZERO,
            fat = get(nf.FAT) ?: BigDecimal.ZERO,
            fiber = get(nf.FIBER) ?: BigDecimal.ZERO,
            sugar = get(nf.SUGAR) ?: BigDecimal.ZERO,
            sodium = get(nf.SODIUM) ?: BigDecimal.ZERO,
            portions = emptyList(),
            favorite = get(isFavorite) ?: false,
            recent = get(isRecent) ?: false,
            source = get(f.SOURCE) ?: "",
            dataQuality = get(f.DATA_QUALITY) ?: "",
        )
    }

    private fun Record.toFoodServingPortion(
        locale: String,
        amount: Field<BigDecimal>,
        unitName: Field<String>,
        unitAbbreviation: Field<String>,
        modifier: Field<String>,
        gramWeight: Field<BigDecimal>,
        portionDescription: Field<String>,
        servingUnitId: Field<UUID>,
        servingUnitCode: Field<String>,
    ): FoodServingPortionResponse {
        val portionAmount = get(amount) ?: BigDecimal.ZERO
        val portionUnitName = get(unitName)
        val portionModifier = get(modifier)
        val displayText = localizedPortionDisplayText(
            locale = locale,
            amount = portionAmount,
            unitName = portionUnitName,
            modifier = portionModifier,
            gramWeight = get(gramWeight),
            portionDescription = get(portionDescription),
        )

        return FoodServingPortionResponse(
            amount = portionAmount,
            unitName = portionUnitName,
            unitAbbreviation = get(unitAbbreviation),
            modifier = portionModifier,
            gramWeight = get(gramWeight),
            displayText = displayText,
            servingUnitId = get(servingUnitId)?.toString(),
            servingUnitCode = get(servingUnitCode),
        )
    }

    private fun localizedPortionDisplayText(
        locale: String,
        amount: BigDecimal,
        unitName: String?,
        modifier: String?,
        gramWeight: BigDecimal?,
        portionDescription: String?,
    ): String {
        return com.gyro.api.food.infrastructure.localizedPortionDisplayText(
            locale = locale,
            amount = amount,
            unitName = unitName,
            modifier = modifier,
            gramWeight = gramWeight,
            portionDescription = portionDescription,
        )
    }
}
