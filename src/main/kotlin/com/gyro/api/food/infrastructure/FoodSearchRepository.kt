package com.gyro.api.food.infrastructure

import com.gyro.api.food.web.dto.FoodSearchItemResponse
import com.gyro.api.food.web.dto.FoodSearchResponse
import com.gyro.api.food.web.dto.FoodServingPortionResponse
import com.gyro.api.food.web.dto.FoodSearchType
import com.gyro.api.food.web.dto.ServingUnitSummaryResponse
import com.gyro.api.jooq.Tables.FOOD_FAVORITES
import com.gyro.api.jooq.Tables.FOOD_LOCALIZATIONS
import com.gyro.api.jooq.Tables.FOOD_NUTRITION_FACTS
import com.gyro.api.jooq.Tables.FOOD_SEARCH_TERMS
import com.gyro.api.jooq.Tables.FOOD_SERVING_PORTIONS
import com.gyro.api.jooq.Tables.FOODS
import com.gyro.api.jooq.Tables.RECENT_FOODS
import com.gyro.api.jooq.Tables.SERVING_UNIT_ALIASES
import com.gyro.api.jooq.Tables.SERVING_UNIT_LOCALIZATIONS
import com.gyro.api.jooq.Tables.SERVING_UNITS
import com.gyro.api.jooq.tables.FoodFavorites
import com.gyro.api.jooq.tables.FoodSearchTerms
import com.gyro.api.jooq.tables.Foods
import com.gyro.api.jooq.tables.RecentFoods
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.ceil

@Repository
class FoodSearchRepository(
    private val dsl: DSLContext,
) {
    fun search(
        currentUserId: UUID,
        normalizedQuery: NormalizedFoodSearchQuery,
        requestedLocale: String?,
        type: FoodSearchType?,
        favorite: Boolean?,
        recent: Boolean?,
        page: Int,
        size: Int,
    ): FoodSearchResponse {
        val locale = requestedLocale?.takeIf { it.isNotBlank() } ?: normalizedQuery.locale
        val localeFilter = requestedLocale?.takeIf { it.isNotBlank() }
        val offset = page * size

        val f = FOODS.`as`("f")
        val fst = FOOD_SEARCH_TERMS.`as`("fst")
        val nf = FOOD_NUTRITION_FACTS.`as`("nf")
        val su = SERVING_UNITS.`as`("su")
        val fl = FOOD_LOCALIZATIONS.`as`("fl")
        val sua = SERVING_UNIT_ALIASES.`as`("sua")
        val fav = FOOD_FAVORITES.`as`("fav")
        val recentFoods = RECENT_FOODS.`as`("recent")

        val conditions = searchConditions(
            currentUserId = currentUserId,
            normalizedQuery = normalizedQuery,
            type = type,
            favorite = favorite,
            recent = recent,
            localeFilter = localeFilter,
            f = f,
            fst = fst,
            fav = fav,
            recentFoods = recentFoods,
        )

        val totalItems = dsl.select(DSL.countDistinct(f.ID))
            .from(fst)
            .join(f).on(f.ID.eq(fst.FOOD_ID))
            .join(nf).on(nf.FOOD_ID.eq(f.ID))
            .join(su).on(su.ID.eq(nf.BASE_UNIT_ID))
            .leftJoin(fl).on(fl.FOOD_ID.eq(f.ID).and(fl.LOCALE.eq(locale)).and(fl.REVIEW_STATUS.ne("REJECTED")))
            .leftJoin(sua).on(sua.SERVING_UNIT_ID.eq(su.ID).and(sua.LOCALE.eq(locale)).and(sua.IS_PRIMARY.isTrue))
            .leftJoin(fav).on(fav.FOOD_ID.eq(f.ID).and(fav.USER_ID.eq(currentUserId)))
            .leftJoin(recentFoods).on(recentFoods.FOOD_ID.eq(f.ID).and(recentFoods.USER_ID.eq(currentUserId)))
            .where(conditions)
            .fetchOne(0, Long::class.java) ?: 0L

        val items = if (totalItems == 0L) {
            emptyList()
        } else {
            fetchRankedItems(
                currentUserId = currentUserId,
                normalizedQuery = normalizedQuery,
                locale = locale,
                localeFilter = localeFilter,
                type = type,
                favorite = favorite,
                recent = recent,
                limit = size,
                offset = offset,
            )
        }

        val portionsByFoodId = fetchServingPortions(
            currentUserId = currentUserId,
            foodIds = items.map { it.id }.toSet(),
            locale = locale,
        )

        return FoodSearchResponse(
            items = items.map { item -> item.copy(portions = portionsByFoodId[item.id].orEmpty()) },
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = if (totalItems == 0L) 0 else ceil(totalItems.toDouble() / size).toInt(),
        )
    }

    private fun fetchServingPortions(
        currentUserId: UUID,
        foodIds: Set<String>,
        locale: String,
    ): Map<String, List<FoodServingPortionResponse>> {
        if (foodIds.isEmpty()) return emptyMap()

        val f = FOODS.`as`("portion_food")
        val fsp = FOOD_SERVING_PORTIONS.`as`("fsp")
        val su = SERVING_UNITS.`as`("portion_unit")
        val sul = SERVING_UNIT_LOCALIZATIONS.`as`("portion_unit_locale")
        val sua = SERVING_UNIT_ALIASES.`as`("portion_unit_alias")
        val unitName = DSL.coalesce(sul.DISPLAY_NAME, sua.ALIAS, fsp.RAW_UNIT_NAME, su.CODE)

        return dsl.select(
            f.PUBLIC_ID,
            fsp.AMOUNT,
            unitName.`as`("portion_unit_name"),
            su.CODE,
            fsp.MODIFIER,
            fsp.GRAM_WEIGHT,
            fsp.PORTION_DESCRIPTION,
            su.ID,
        )
            .from(f)
            .join(fsp).on(fsp.FOOD_ID.eq(f.ID))
            .leftJoin(su).on(su.ID.eq(fsp.SERVING_UNIT_ID))
            .leftJoin(sul).on(sul.SERVING_UNIT_ID.eq(su.ID).and(sul.LOCALE.eq(locale)).and(sul.REVIEW_STATUS.eq("REVIEWED")))
            .leftJoin(sua).on(sua.SERVING_UNIT_ID.eq(su.ID).and(sua.LOCALE.eq(locale)).and(sua.IS_PRIMARY.isTrue))
            .where(
                f.PUBLIC_ID.`in`(foodIds)
                    .and(f.ARCHIVED_AT.isNull)
                    .and(f.IS_SEARCHABLE.isTrue)
                    .and(f.CURATION_STATUS.ne("HIDDEN"))
                    .and(f.TYPE.eq("SYSTEM").or(f.OWNER_USER_ID.eq(currentUserId)))
            )
            .orderBy(f.PUBLIC_ID.asc(), fsp.SORT_ORDER.asc(), fsp.AMOUNT.asc(), fsp.ID.asc())
            .fetchGroups(
                { record -> record.get(f.PUBLIC_ID) },
                { record ->
                    val amount = record.get(fsp.AMOUNT) ?: BigDecimal.ZERO
                    val name = record.get("portion_unit_name", String::class.java)
                    val modifier = record.get(fsp.MODIFIER)
                    val gramWeight = record.get(fsp.GRAM_WEIGHT)
                    FoodServingPortionResponse(
                        amount = amount,
                        unitName = name,
                        unitAbbreviation = record.get(su.CODE),
                        modifier = modifier,
                        gramWeight = gramWeight,
                        displayText = localizedPortionDisplayText(
                            locale = locale,
                            amount = amount,
                            unitName = name,
                            modifier = modifier,
                            gramWeight = gramWeight,
                            portionDescription = record.get(fsp.PORTION_DESCRIPTION),
                        ),
                        servingUnitId = record.get(su.ID)?.toString(),
                        servingUnitCode = record.get(su.CODE),
                    )
                },
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

    private fun fetchRankedItems(
        currentUserId: UUID,
        normalizedQuery: NormalizedFoodSearchQuery,
        locale: String,
        localeFilter: String?,
        type: FoodSearchType?,
        favorite: Boolean?,
        recent: Boolean?,
        limit: Int,
        offset: Int,
    ): List<FoodSearchItemResponse> {
        val f = FOODS.`as`("f")
        val fst = FOOD_SEARCH_TERMS.`as`("fst")
        val nf = FOOD_NUTRITION_FACTS.`as`("nf")
        val su = SERVING_UNITS.`as`("su")
        val fl = FOOD_LOCALIZATIONS.`as`("fl")
        val sua = SERVING_UNIT_ALIASES.`as`("sua")
        val fav = FOOD_FAVORITES.`as`("fav")
        val recentFoods = RECENT_FOODS.`as`("recent")

        val rawScore = scoreExpression(
            normalizedQuery = normalizedQuery,
            locale = locale,
            f = f,
            fst = fst,
            fav = fav,
            recentFoods = recentFoods,
        )
        val score = rawScore.`as`("score")
        val aliasDisplayName = DSL.field(
            """
            (
                select fa.alias
                from food_aliases fa
                where fa.food_id = {0}
                  and fa.locale = {1}
                  and fa.review_status <> 'REJECTED'
                order by
                  case when fa.review_status = 'REVIEWED' then 0 else 1 end,
                  fa.updated_at desc,
                  fa.created_at desc
                limit 1
            )
            """.trimIndent(),
            String::class.java,
            f.ID,
            DSL.inline(locale),
        )
        val displayName = DSL.coalesce(fl.DISPLAY_NAME, aliasDisplayName, f.NAME).`as`("display_name")
        val servingUnitLabel = DSL.coalesce(sua.ALIAS, su.CODE).`as`("serving_unit_label")
        val isFavorite = fav.FOOD_ID.isNotNull.`as`("is_favorite")
        val isRecent = recentFoods.FOOD_ID.isNotNull.`as`("is_recent")
        val rowNumber = DSL.rowNumber().over(
            DSL.partitionBy(f.ID)
                .orderBy(rawScore.desc(), fst.WEIGHT.desc(), f.NAME.asc())
        ).`as`("row_number")
        val conditions = searchConditions(
            currentUserId = currentUserId,
            normalizedQuery = normalizedQuery,
            type = type,
            favorite = favorite,
            recent = recent,
            localeFilter = localeFilter,
            f = f,
            fst = fst,
            fav = fav,
            recentFoods = recentFoods,
        )

        val rankedMatches = dsl.select(
            f.PUBLIC_ID.`as`("public_id"),
            f.TYPE.`as`("type"),
            f.NAME.`as`("name"),
            displayName,
            fst.LOCALE.`as`("term_locale"),
            nf.BASE_QUANTITY.`as`("base_quantity"),
            su.ID.`as`("serving_unit_id"),
            su.CODE.`as`("serving_unit_code"),
            servingUnitLabel,
            nf.CALORIES.`as`("calories"),
            nf.PROTEIN.`as`("protein"),
            nf.CARBS.`as`("carbs"),
            nf.FAT.`as`("fat"),
            nf.FIBER.`as`("fiber"),
            nf.SUGAR.`as`("sugar"),
            nf.SODIUM.`as`("sodium"),
            isFavorite,
            isRecent,
            f.SOURCE.`as`("source"),
            f.DATA_QUALITY.`as`("data_quality"),
            score,
            rowNumber,
        )
            .from(fst)
            .join(f).on(f.ID.eq(fst.FOOD_ID))
            .join(nf).on(nf.FOOD_ID.eq(f.ID))
            .join(su).on(su.ID.eq(nf.BASE_UNIT_ID))
            .leftJoin(fl).on(fl.FOOD_ID.eq(f.ID).and(fl.LOCALE.eq(locale)).and(fl.REVIEW_STATUS.ne("REJECTED")))
            .leftJoin(sua).on(sua.SERVING_UNIT_ID.eq(su.ID).and(sua.LOCALE.eq(locale)).and(sua.IS_PRIMARY.isTrue))
            .leftJoin(fav).on(fav.FOOD_ID.eq(f.ID).and(fav.USER_ID.eq(currentUserId)))
            .leftJoin(recentFoods).on(recentFoods.FOOD_ID.eq(f.ID).and(recentFoods.USER_ID.eq(currentUserId)))
            .where(conditions)
            .asTable("ranked_matches")

        val rowNumberField = rankedMatches.field("row_number", Int::class.java) ?: error("Missing row_number field.")
        val scoreField = rankedMatches.field("score", Double::class.java) ?: error("Missing score field.")
        val nameField = rankedMatches.field("name", String::class.java) ?: error("Missing name field.")

        return dsl.selectFrom(rankedMatches)
            .where(rowNumberField.eq(1))
            .orderBy(scoreField.desc(), nameField.asc())
            .limit(limit)
            .offset(offset)
            .fetch { it.toFoodSearchItem() }
    }

    private fun searchConditions(
        currentUserId: UUID,
        normalizedQuery: NormalizedFoodSearchQuery,
        type: FoodSearchType?,
        favorite: Boolean?,
        recent: Boolean?,
        localeFilter: String?,
        f: Foods,
        fst: FoodSearchTerms,
        fav: FoodFavorites,
        recentFoods: RecentFoods,
    ): Condition {
        return DSL.and(
            f.ARCHIVED_AT.isNull,
            f.IS_SEARCHABLE.isTrue,
            f.CURATION_STATUS.ne("HIDDEN"),
            f.TYPE.eq(FoodSearchType.SYSTEM.name).or(f.OWNER_USER_ID.eq(currentUserId)),
            type?.let { f.TYPE.eq(it.name) } ?: DSL.trueCondition(),
            when (favorite) {
                true -> fav.FOOD_ID.isNotNull
                false -> fav.FOOD_ID.isNull
                null -> DSL.trueCondition()
            },
            when (recent) {
                true -> recentFoods.FOOD_ID.isNotNull
                false -> recentFoods.FOOD_ID.isNull
                null -> DSL.trueCondition()
            },
            localeFilter?.let { fst.LOCALE.eq(it) } ?: DSL.trueCondition(),
            defaultCategoryVisibilityCondition(f, normalizedQuery),
            matchCondition(fst, normalizedQuery),
        )
    }

    private fun defaultCategoryVisibilityCondition(
        f: Foods,
        normalizedQuery: NormalizedFoodSearchQuery,
    ): Condition {
        if (isBabyFoodQuery(normalizedQuery)) {
            return DSL.trueCondition()
        }

        return DSL.condition(
            """
            not exists (
                select 1
                from food_categories fc
                where fc.id = {0}
                  and fc.normalized_name = 'baby foods'
            )
            """.trimIndent(),
            f.CATEGORY_ID,
        )
    }

    private fun isBabyFoodQuery(normalizedQuery: NormalizedFoodSearchQuery): Boolean {
        if (normalizedQuery.isBlank) {
            return false
        }

        val query = normalizedQuery.value
        return babyFoodQueryTerms.any { query.contains(it) }
    }

    private fun matchCondition(
        fst: FoodSearchTerms,
        normalizedQuery: NormalizedFoodSearchQuery,
    ): Condition {
        if (normalizedQuery.isBlank) {
            return DSL.trueCondition()
        }

        val query = normalizedQuery.value
        return fst.NORMALIZED_TERM.eq(query)
            .or(fst.NORMALIZED_TERM.like("$query%"))
            .or(
                DSL.condition(
                    "{0} @@ plainto_tsquery(cast((case when {1} = 'fa' then 'simple' else 'english' end) as regconfig), {2})",
                    fst.SEARCH_VECTOR,
                    fst.LOCALE,
                    DSL.inline(query),
                )
            )
            .or(similarity(fst, query).gt(0.15))
    }

    private fun scoreExpression(
        normalizedQuery: NormalizedFoodSearchQuery,
        locale: String,
        f: Foods,
        fst: FoodSearchTerms,
        fav: FoodFavorites,
        recentFoods: RecentFoods,
    ): Field<Double> {
        val query = normalizedQuery.value
        return DSL.field(
            """
            (
                case when {0} = false then 0 else 0 end
                + case when {1} = {2} then 1000 else 0 end
                + case when {1} like {3} then 700 else 0 end
                + (
                    greatest(
                        case when {0} = true then similarity({1}, {2}) else 0 end,
                        case
                            when {0} = true
                            then ts_rank({4}, plainto_tsquery(cast((case when {5} = 'fa' then 'simple' else 'english' end) as regconfig), {2}))
                            else 0
                        end
                    ) * cast({6} as double precision) * 120
                )
                + case when {7} is not null then 180 else 0 end
                + case when {8} is not null then 120 + least({9}, 20) * 6 else 0 end
                + case when {10} = 'REVIEWED' then 80 else 0 end
                + case when {11} = 'CURATED' then 70 when {11} = 'FOUNDATION' then 55 when {11} = 'SR_LEGACY' then 10 else 0 end
                + case when {5} = {12} then 25 else 0 end
                + {13}
                + {14}
            )
            """.trimIndent(),
            Double::class.java,
            DSL.inline(!normalizedQuery.isBlank),
            fst.NORMALIZED_TERM,
            DSL.inline(query),
            DSL.inline("$query%"),
            fst.SEARCH_VECTOR,
            fst.LOCALE,
            fst.WEIGHT,
            fav.FOOD_ID,
            recentFoods.FOOD_ID,
            recentFoods.USE_COUNT,
            f.CURATION_STATUS,
            f.DATA_QUALITY,
            DSL.inline(locale),
            restaurantFoodPenalty(f),
            curatedFarsiPriorityBoost(f, locale),
        )
    }

    private fun curatedFarsiPriorityBoost(f: Foods, locale: String): Field<Int> {
        return DSL.field(
            "case when {0} = 'fa' and {1} = 'CURATED' then 10000 else 0 end",
            Int::class.java,
            DSL.inline(locale),
            f.DATA_QUALITY,
        )
    }

    private fun restaurantFoodPenalty(f: Foods): Field<Int> {
        return DSL.field(
            """
            case
                when exists (
                    select 1
                    from food_categories fc
                    where fc.id = {0}
                      and fc.normalized_name = 'restaurant foods'
                )
                then -300
                else 0
            end
            """.trimIndent(),
            Int::class.java,
            f.CATEGORY_ID,
        )
    }

    private fun similarity(fst: FoodSearchTerms, query: String): Field<Double> {
        return DSL.field(
            "similarity({0}, {1})",
            Double::class.java,
            fst.NORMALIZED_TERM,
            DSL.inline(query),
        )
    }

    private fun textRank(fst: FoodSearchTerms, query: String): Field<Double> {
        return DSL.field(
            "ts_rank({0}, plainto_tsquery(cast((case when {1} = 'fa' then 'simple' else 'english' end) as regconfig), {2}))",
            Double::class.java,
            fst.SEARCH_VECTOR,
            fst.LOCALE,
            DSL.inline(query),
        )
    }

    private companion object {
        val babyFoodQueryTerms = setOf(
            "baby",
            "babies",
            "infant",
            "infants",
            "toddler",
            "toddlers",
            "نوزاد",
            "نوزادی",
            "کودک",
            "کودکان",
            "بچه",
        )
    }

    private fun Record.toFoodSearchItem(): FoodSearchItemResponse {
        val servingUnitCode = get("serving_unit_code", String::class.java) ?: ""

        return FoodSearchItemResponse(
            id = get("public_id", String::class.java) ?: "",
            type = FoodSearchType.valueOf(get("type", String::class.java) ?: FoodSearchType.SYSTEM.name),
            name = get("name", String::class.java) ?: "",
            displayName = get("display_name", String::class.java) ?: "",
            locale = get("term_locale", String::class.java),
            servingQuantity = get("base_quantity", BigDecimal::class.java) ?: BigDecimal.ZERO,
            servingUnit = ServingUnitSummaryResponse(
                id = requireNotNull(get("serving_unit_id", UUID::class.java)) {
                    "Food search result is missing serving_unit_id."
                }.toString(),
                code = servingUnitCode,
                label = get("serving_unit_label", String::class.java) ?: servingUnitCode,
            ),
            calories = get("calories", BigDecimal::class.java) ?: BigDecimal.ZERO,
            protein = get("protein", BigDecimal::class.java) ?: BigDecimal.ZERO,
            carbs = get("carbs", BigDecimal::class.java) ?: BigDecimal.ZERO,
            fat = get("fat", BigDecimal::class.java) ?: BigDecimal.ZERO,
            fiber = get("fiber", BigDecimal::class.java) ?: BigDecimal.ZERO,
            sugar = get("sugar", BigDecimal::class.java) ?: BigDecimal.ZERO,
            sodium = get("sodium", BigDecimal::class.java) ?: BigDecimal.ZERO,
            favorite = get("is_favorite", Boolean::class.java) ?: false,
            recent = get("is_recent", Boolean::class.java) ?: false,
            source = get("source", String::class.java) ?: "",
            dataQuality = get("data_quality", String::class.java) ?: "",
        )
    }
}
