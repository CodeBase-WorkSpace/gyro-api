package com.gyro.api.food.infrastructure.admin

import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.food.web.dto.*
import com.gyro.api.jooq.Tables.*
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.*

data class AdminFoodListFilters(
    val query: String?,
    val normalizedQuery: String?,
    val source: String?,
    val type: String?,
    val curationStatus: String?,
    val archived: Boolean?,
    val searchable: Boolean?,
    val ownership: AdminFoodOwnership = AdminFoodOwnership.CATALOG,
)

enum class AdminFoodOwnership {
    CATALOG,
    USER,
    ALL,
}

data class AdminFoodWriteModel(
    val name: String,
    val normalizedName: String,
    val brandName: String?,
    val normalizedBrandName: String?,
    val categoryId: UUID?,
    val curationStatus: String,
    val isSearchable: Boolean,
    val localizations: List<AdminLocalizationWriteModel>,
    val aliases: List<AdminAliasWriteModel>,
    val nutrition: AdminNutritionWriteModel,
    val portions: List<AdminPortionWriteModel>,
)

data class AdminLocalizationWriteModel(
    val locale: String,
    val displayName: String,
    val normalizedDisplayName: String,
    val reviewStatus: String,
)

data class AdminAliasWriteModel(
    val locale: String,
    val alias: String,
    val normalizedAlias: String,
    val reviewStatus: String,
)

data class AdminNutritionWriteModel(
    val baseQuantity: BigDecimal,
    val baseUnitId: UUID,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class AdminPortionWriteModel(
    val servingUnitId: UUID?,
    val amount: BigDecimal,
    val gramWeight: BigDecimal?,
    val modifier: String?,
    val portionDescription: String?,
    val sortOrder: Int,
)

data class AdminSearchTermWriteModel(
    val locale: String,
    val term: String,
    val normalizedTerm: String,
    val termKind: String,
    val weight: BigDecimal,
)

data class AdminServingUnitRecord(
    val id: UUID,
    val code: String,
    val unitType: String,
    val gramMultiplier: BigDecimal?,
    val milliliterMultiplier: BigDecimal?,
)

@Repository
class AdminFoodCatalogRepository(
    private val dsl: DSLContext,
) {
    fun listFoods(filters: AdminFoodListFilters, page: Int, size: Int): PageResponse<AdminFoodSummaryResponse> {
        val condition = ownershipCondition(filters.ownership).and(filters.toCondition())

        val totalItems = dsl.selectCount()
            .from(FOODS)
            .where(condition)
            .fetchOne(0, Long::class.java) ?: 0L

        val rows = dsl.select(
            FOODS.ID,
            FOODS.PUBLIC_ID,
            FOODS.NAME,
            FOODS.BRAND_NAME,
            FOODS.TYPE,
            FOODS.SOURCE,
            FOODS.DATA_QUALITY,
            FOODS.CURATION_STATUS,
            FOODS.IS_SEARCHABLE,
            FOODS.ARCHIVED_AT,
            FOODS.LOCK_VERSION,
            FOODS.UPDATED_AT,
            FOODS.OWNER_USER_ID,
            FOOD_CATEGORIES.NAME,
        )
            .from(FOODS)
            .leftJoin(FOOD_CATEGORIES).on(FOOD_CATEGORIES.ID.eq(FOODS.CATEGORY_ID))
            .where(condition)
            .orderBy(FOODS.UPDATED_AT.desc(), FOODS.ID.asc())
            .limit(size)
            .offset(page * size)
            .fetch()

        val foodIds = rows.map { it.get(FOODS.ID) }
        val localeCoverage = fetchLocaleCoverage(foodIds)
        val nutritionByFood = fetchNutritionPresence(foodIds)
        val portionCounts = fetchPortionCounts(foodIds)

        val items = rows.map { row ->
            val foodId = row.get(FOODS.ID)
            AdminFoodSummaryResponse(
                id = row.get(FOODS.PUBLIC_ID),
                name = row.get(FOODS.NAME),
                brandName = row.get(FOODS.BRAND_NAME),
                type = row.get(FOODS.TYPE),
                source = row.get(FOODS.SOURCE),
                dataQuality = row.get(FOODS.DATA_QUALITY),
                curationStatus = row.get(FOODS.CURATION_STATUS),
                isSearchable = row.get(FOODS.IS_SEARCHABLE),
                archived = row.get(FOODS.ARCHIVED_AT) != null,
                categoryName = row.get(FOOD_CATEGORIES.NAME),
                localeCoverage = localeCoverage[foodId].orEmpty(),
                hasNutrition = nutritionByFood.contains(foodId),
                portionCount = portionCounts[foodId] ?: 0,
                lockVersion = row.get(FOODS.LOCK_VERSION),
                updatedAt = row.get(FOODS.UPDATED_AT).toInstant(),
                editable = isCatalogFood(
                    type = row.get(FOODS.TYPE),
                    source = row.get(FOODS.SOURCE),
                    ownerUserId = row.get(FOODS.OWNER_USER_ID),
                ),
            )
        }

        val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
        return PageResponse(
            items = items,
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages,
        )
    }

    fun findFoodIdByPublicId(publicId: String): UUID? {
        return dsl.select(FOODS.ID)
            .from(FOODS)
            .where(FOODS.PUBLIC_ID.eq(publicId).and(catalogFoodCondition()))
            .fetchOne(FOODS.ID)
    }

    fun findDetail(publicId: String): AdminFoodDetailResponse? {
        val food = dsl.select(
            FOODS.ID,
            FOODS.PUBLIC_ID,
            FOODS.NAME,
            FOODS.BRAND_NAME,
            FOODS.TYPE,
            FOODS.SOURCE,
            FOODS.DATA_QUALITY,
            FOODS.CURATION_STATUS,
            FOODS.IS_SEARCHABLE,
            FOODS.ARCHIVED_AT,
            FOODS.CATEGORY_ID,
            FOODS.LOCK_VERSION,
            FOODS.CREATED_AT,
            FOODS.UPDATED_AT,
            FOODS.OWNER_USER_ID,
            FOOD_CATEGORIES.NAME,
        )
            .from(FOODS)
            .leftJoin(FOOD_CATEGORIES).on(FOOD_CATEGORIES.ID.eq(FOODS.CATEGORY_ID))
            .where(FOODS.PUBLIC_ID.eq(publicId).and(catalogFoodCondition().or(userFoodCondition())))
            .fetchOne() ?: return null

        val foodId = food.get(FOODS.ID)

        val localizations = dsl.select(
            FOOD_LOCALIZATIONS.LOCALE,
            FOOD_LOCALIZATIONS.DISPLAY_NAME,
            FOOD_LOCALIZATIONS.SOURCE,
            FOOD_LOCALIZATIONS.REVIEW_STATUS,
        )
            .from(FOOD_LOCALIZATIONS)
            .where(FOOD_LOCALIZATIONS.FOOD_ID.eq(foodId))
            .orderBy(FOOD_LOCALIZATIONS.LOCALE.asc())
            .fetch { record ->
                AdminFoodLocalizationResponse(
                    locale = record.get(FOOD_LOCALIZATIONS.LOCALE),
                    displayName = record.get(FOOD_LOCALIZATIONS.DISPLAY_NAME),
                    source = record.get(FOOD_LOCALIZATIONS.SOURCE),
                    reviewStatus = record.get(FOOD_LOCALIZATIONS.REVIEW_STATUS),
                )
            }

        val aliases = dsl.select(
            FOOD_ALIASES.LOCALE,
            FOOD_ALIASES.ALIAS,
            FOOD_ALIASES.SOURCE,
            FOOD_ALIASES.REVIEW_STATUS,
        )
            .from(FOOD_ALIASES)
            .where(FOOD_ALIASES.FOOD_ID.eq(foodId))
            .orderBy(FOOD_ALIASES.LOCALE.asc(), FOOD_ALIASES.ALIAS.asc())
            .fetch { record ->
                AdminFoodAliasResponse(
                    locale = record.get(FOOD_ALIASES.LOCALE),
                    alias = record.get(FOOD_ALIASES.ALIAS),
                    source = record.get(FOOD_ALIASES.SOURCE),
                    reviewStatus = record.get(FOOD_ALIASES.REVIEW_STATUS),
                )
            }

        val nutrition = dsl.select(
            FOOD_NUTRITION_FACTS.BASE_QUANTITY,
            SERVING_UNITS.CODE,
            FOOD_NUTRITION_FACTS.CALORIES,
            FOOD_NUTRITION_FACTS.PROTEIN,
            FOOD_NUTRITION_FACTS.CARBS,
            FOOD_NUTRITION_FACTS.FAT,
            FOOD_NUTRITION_FACTS.FIBER,
            FOOD_NUTRITION_FACTS.SUGAR,
            FOOD_NUTRITION_FACTS.SODIUM,
        )
            .from(FOOD_NUTRITION_FACTS)
            .join(SERVING_UNITS).on(SERVING_UNITS.ID.eq(FOOD_NUTRITION_FACTS.BASE_UNIT_ID))
            .where(FOOD_NUTRITION_FACTS.FOOD_ID.eq(foodId))
            .fetchOne { record ->
                AdminFoodNutritionResponse(
                    baseQuantity = record.get(FOOD_NUTRITION_FACTS.BASE_QUANTITY),
                    baseUnitCode = record.get(SERVING_UNITS.CODE),
                    calories = record.get(FOOD_NUTRITION_FACTS.CALORIES),
                    protein = record.get(FOOD_NUTRITION_FACTS.PROTEIN),
                    carbs = record.get(FOOD_NUTRITION_FACTS.CARBS),
                    fat = record.get(FOOD_NUTRITION_FACTS.FAT),
                    fiber = record.get(FOOD_NUTRITION_FACTS.FIBER),
                    sugar = record.get(FOOD_NUTRITION_FACTS.SUGAR),
                    sodium = record.get(FOOD_NUTRITION_FACTS.SODIUM),
                )
            }

        val portions = dsl.select(
            FOOD_SERVING_PORTIONS.ID,
            SERVING_UNITS.CODE,
            FOOD_SERVING_PORTIONS.AMOUNT,
            FOOD_SERVING_PORTIONS.GRAM_WEIGHT,
            FOOD_SERVING_PORTIONS.MODIFIER,
            FOOD_SERVING_PORTIONS.PORTION_DESCRIPTION,
            FOOD_SERVING_PORTIONS.SORT_ORDER,
        )
            .from(FOOD_SERVING_PORTIONS)
            .leftJoin(SERVING_UNITS).on(SERVING_UNITS.ID.eq(FOOD_SERVING_PORTIONS.SERVING_UNIT_ID))
            .where(FOOD_SERVING_PORTIONS.FOOD_ID.eq(foodId))
            .orderBy(FOOD_SERVING_PORTIONS.SORT_ORDER.asc(), FOOD_SERVING_PORTIONS.AMOUNT.asc())
            .fetch { record ->
                AdminFoodPortionResponse(
                    id = record.get(FOOD_SERVING_PORTIONS.ID).toString(),
                    servingUnitCode = record.get(SERVING_UNITS.CODE),
                    amount = record.get(FOOD_SERVING_PORTIONS.AMOUNT),
                    gramWeight = record.get(FOOD_SERVING_PORTIONS.GRAM_WEIGHT),
                    modifier = record.get(FOOD_SERVING_PORTIONS.MODIFIER),
                    portionDescription = record.get(FOOD_SERVING_PORTIONS.PORTION_DESCRIPTION),
                    sortOrder = record.get(FOOD_SERVING_PORTIONS.SORT_ORDER),
                )
            }

        return AdminFoodDetailResponse(
            id = food.get(FOODS.PUBLIC_ID),
            name = food.get(FOODS.NAME),
            brandName = food.get(FOODS.BRAND_NAME),
            type = food.get(FOODS.TYPE),
            source = food.get(FOODS.SOURCE),
            dataQuality = food.get(FOODS.DATA_QUALITY),
            curationStatus = food.get(FOODS.CURATION_STATUS),
            isSearchable = food.get(FOODS.IS_SEARCHABLE),
            archived = food.get(FOODS.ARCHIVED_AT) != null,
            categoryId = food.get(FOODS.CATEGORY_ID)?.toString(),
            categoryName = food.get(FOOD_CATEGORIES.NAME),
            localizations = localizations,
            aliases = aliases,
            nutrition = nutrition,
            portions = portions,
            lockVersion = food.get(FOODS.LOCK_VERSION),
            createdAt = food.get(FOODS.CREATED_AT).toInstant(),
            updatedAt = food.get(FOODS.UPDATED_AT).toInstant(),
            editable = isCatalogFood(
                type = food.get(FOODS.TYPE),
                source = food.get(FOODS.SOURCE),
                ownerUserId = food.get(FOODS.OWNER_USER_ID),
            ),
        )
    }

    fun createFood(
        publicId: String,
        model: AdminFoodWriteModel,
        searchTerms: List<AdminSearchTermWriteModel>,
    ) {
        val foodId = dsl.insertInto(FOODS)
            .set(FOODS.PUBLIC_ID, publicId)
            .set(FOODS.TYPE, "SYSTEM")
            .set(FOODS.SOURCE, "GYRO_CURATED")
            .set(FOODS.SOURCE_FOOD_ID, publicId)
            .set(FOODS.CATEGORY_ID, model.categoryId)
            .set(FOODS.NAME, model.name)
            .set(FOODS.NORMALIZED_NAME, model.normalizedName)
            .set(FOODS.BRAND_NAME, model.brandName)
            .set(FOODS.NORMALIZED_BRAND_NAME, model.normalizedBrandName)
            .set(FOODS.DATA_QUALITY, "CURATED")
            .set(FOODS.CURATION_STATUS, model.curationStatus)
            .set(FOODS.IS_SEARCHABLE, model.isSearchable)
            .set(FOODS.LOCK_VERSION, 0)
            .set(FOODS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning(FOODS.ID)
            .fetchOne()
            ?.get(FOODS.ID)
            ?: error("Admin food insert did not return a row.")

        insertChildren(foodId, model, searchTerms)
    }

    fun updateFood(
        foodId: UUID,
        expectedLockVersion: Int,
        model: AdminFoodWriteModel,
        searchTerms: List<AdminSearchTermWriteModel>,
    ): Boolean {
        val updated = dsl.update(FOODS)
            .set(FOODS.NAME, model.name)
            .set(FOODS.NORMALIZED_NAME, model.normalizedName)
            .set(FOODS.BRAND_NAME, model.brandName)
            .set(FOODS.NORMALIZED_BRAND_NAME, model.normalizedBrandName)
            .set(FOODS.CATEGORY_ID, model.categoryId)
            .set(FOODS.CURATION_STATUS, model.curationStatus)
            .set(FOODS.IS_SEARCHABLE, model.isSearchable)
            .set(FOODS.LOCK_VERSION, FOODS.LOCK_VERSION.plus(1))
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                FOODS.ID.eq(foodId)
                    .and(FOODS.LOCK_VERSION.eq(expectedLockVersion))
                    .and(catalogFoodCondition())
            )
            .execute() == 1

        if (!updated) {
            return false
        }

        dsl.deleteFrom(FOOD_NUTRITION_FACTS).where(FOOD_NUTRITION_FACTS.FOOD_ID.eq(foodId)).execute()
        dsl.deleteFrom(FOOD_SERVING_PORTIONS).where(FOOD_SERVING_PORTIONS.FOOD_ID.eq(foodId)).execute()
        dsl.deleteFrom(FOOD_LOCALIZATIONS).where(FOOD_LOCALIZATIONS.FOOD_ID.eq(foodId)).execute()
        dsl.deleteFrom(FOOD_ALIASES).where(FOOD_ALIASES.FOOD_ID.eq(foodId)).execute()
        dsl.deleteFrom(FOOD_SEARCH_TERMS).where(FOOD_SEARCH_TERMS.FOOD_ID.eq(foodId)).execute()

        insertChildren(foodId, model, searchTerms)
        return true
    }

    fun currentLockVersion(foodId: UUID): Int? {
        return dsl.select(FOODS.LOCK_VERSION)
            .from(FOODS)
            .where(FOODS.ID.eq(foodId).and(catalogFoodCondition()))
            .fetchOne(FOODS.LOCK_VERSION)
    }

    fun setArchived(publicId: String, archived: Boolean): Boolean {
        val archivedAt = if (archived) DSL.currentOffsetDateTime() else DSL.inline(null, FOODS.ARCHIVED_AT)
        return dsl.update(FOODS)
            .set(FOODS.ARCHIVED_AT, archivedAt)
            .set(FOODS.LOCK_VERSION, FOODS.LOCK_VERSION.plus(1))
            .set(FOODS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(
                FOODS.PUBLIC_ID.eq(publicId)
                    .and(if (archived) FOODS.ARCHIVED_AT.isNull else FOODS.ARCHIVED_AT.isNotNull)
                    .and(catalogFoodCondition())
            )
            .execute() == 1
    }

    fun findDuplicates(
        normalizedName: String,
        excludeFoodId: UUID?,
        limit: Int,
    ): List<AdminDuplicateSuggestionResponse> {
        if (normalizedName.isBlank()) {
            return emptyList()
        }
        val pattern = "%${escapeLike(normalizedName)}%"

        val nameCondition = FOODS.NORMALIZED_NAME.like(pattern, '\\')
        val aliasCondition = DSL.exists(
            DSL.selectOne()
                .from(FOOD_ALIASES)
                .where(
                    FOOD_ALIASES.FOOD_ID.eq(FOODS.ID)
                        .and(FOOD_ALIASES.NORMALIZED_ALIAS.like(pattern, '\\'))
                )
        )

        var condition = catalogFoodCondition().and(nameCondition.or(aliasCondition))
        if (excludeFoodId != null) {
            condition = condition.and(FOODS.ID.ne(excludeFoodId))
        }

        return dsl.select(
            FOODS.PUBLIC_ID,
            FOODS.NAME,
            FOODS.BRAND_NAME,
            FOODS.SOURCE,
            FOODS.ARCHIVED_AT,
            FOODS.NORMALIZED_NAME,
        )
            .from(FOODS)
            .where(condition)
            .orderBy(FOODS.UPDATED_AT.desc())
            .limit(limit)
            .fetch { record ->
                AdminDuplicateSuggestionResponse(
                    id = record.get(FOODS.PUBLIC_ID),
                    name = record.get(FOODS.NAME),
                    brandName = record.get(FOODS.BRAND_NAME),
                    source = record.get(FOODS.SOURCE),
                    archived = record.get(FOODS.ARCHIVED_AT) != null,
                    matchedOn = if (record.get(FOODS.NORMALIZED_NAME).contains(normalizedName)) "NAME" else "ALIAS",
                )
            }
    }

    fun listServingUnits(): List<AdminServingUnitResponse> {
        return dsl.select(
            SERVING_UNITS.ID,
            SERVING_UNITS.CODE,
            SERVING_UNITS.UNIT_TYPE,
            SERVING_UNITS.GRAM_MULTIPLIER,
            SERVING_UNITS.MILLILITER_MULTIPLIER,
            SERVING_UNITS.IS_ACTIVE,
            SERVING_UNITS.SORT_ORDER,
        )
            .from(SERVING_UNITS)
            .orderBy(SERVING_UNITS.SORT_ORDER.asc(), SERVING_UNITS.CODE.asc())
            .fetch { record ->
                AdminServingUnitResponse(
                    id = record.get(SERVING_UNITS.ID).toString(),
                    code = record.get(SERVING_UNITS.CODE),
                    unitType = record.get(SERVING_UNITS.UNIT_TYPE),
                    gramMultiplier = record.get(SERVING_UNITS.GRAM_MULTIPLIER),
                    milliliterMultiplier = record.get(SERVING_UNITS.MILLILITER_MULTIPLIER),
                    isActive = record.get(SERVING_UNITS.IS_ACTIVE),
                    sortOrder = record.get(SERVING_UNITS.SORT_ORDER),
                )
            }
    }

    fun findServingUnitByCode(code: String): AdminServingUnitRecord? {
        return dsl.select(
            SERVING_UNITS.ID,
            SERVING_UNITS.CODE,
            SERVING_UNITS.UNIT_TYPE,
            SERVING_UNITS.GRAM_MULTIPLIER,
            SERVING_UNITS.MILLILITER_MULTIPLIER,
        )
            .from(SERVING_UNITS)
            .where(SERVING_UNITS.CODE.eq(code))
            .fetchOne { record ->
                AdminServingUnitRecord(
                    id = record.get(SERVING_UNITS.ID),
                    code = record.get(SERVING_UNITS.CODE),
                    unitType = record.get(SERVING_UNITS.UNIT_TYPE),
                    gramMultiplier = record.get(SERVING_UNITS.GRAM_MULTIPLIER),
                    milliliterMultiplier = record.get(SERVING_UNITS.MILLILITER_MULTIPLIER),
                )
            }
    }

    fun categoryExists(categoryId: UUID): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(FOOD_CATEGORIES)
                .where(FOOD_CATEGORIES.ID.eq(categoryId))
        )
    }

    fun listCategories(): List<AdminFoodCategoryResponse> {
        return dsl.select(FOOD_CATEGORIES.ID, FOOD_CATEGORIES.NAME, FOOD_CATEGORIES.SOURCE)
            .from(FOOD_CATEGORIES)
            .orderBy(FOOD_CATEGORIES.NAME.asc())
            .fetch { record ->
                AdminFoodCategoryResponse(
                    id = record.get(FOOD_CATEGORIES.ID).toString(),
                    name = record.get(FOOD_CATEGORIES.NAME),
                    source = record.get(FOOD_CATEGORIES.SOURCE),
                )
            }
    }

    fun findCategoryBySourceAndNormalizedName(source: String, normalizedName: String): AdminFoodCategoryResponse? {
        return dsl.select(FOOD_CATEGORIES.ID, FOOD_CATEGORIES.NAME, FOOD_CATEGORIES.SOURCE)
            .from(FOOD_CATEGORIES)
            .where(
                FOOD_CATEGORIES.SOURCE.eq(source)
                    .and(FOOD_CATEGORIES.NORMALIZED_NAME.eq(normalizedName))
            )
            .fetchOne { record ->
                AdminFoodCategoryResponse(
                    id = record.get(FOOD_CATEGORIES.ID).toString(),
                    name = record.get(FOOD_CATEGORIES.NAME),
                    source = record.get(FOOD_CATEGORIES.SOURCE),
                )
            }
    }

    fun createCategory(source: String, name: String, normalizedName: String): AdminFoodCategoryResponse {
        val record = dsl.insertInto(FOOD_CATEGORIES)
            .set(FOOD_CATEGORIES.SOURCE, source)
            .set(FOOD_CATEGORIES.NAME, name)
            .set(FOOD_CATEGORIES.NORMALIZED_NAME, normalizedName)
            .set(FOOD_CATEGORIES.CREATED_AT, DSL.currentOffsetDateTime())
            .set(FOOD_CATEGORIES.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning(FOOD_CATEGORIES.ID, FOOD_CATEGORIES.NAME, FOOD_CATEGORIES.SOURCE)
            .fetchOne()
            ?: error("Category insert did not return a row.")

        return AdminFoodCategoryResponse(
            id = record.get(FOOD_CATEGORIES.ID).toString(),
            name = record.get(FOOD_CATEGORIES.NAME),
            source = record.get(FOOD_CATEGORIES.SOURCE),
        )
    }

    private fun insertChildren(
        foodId: UUID,
        model: AdminFoodWriteModel,
        searchTerms: List<AdminSearchTermWriteModel>,
    ) {
        dsl.insertInto(FOOD_NUTRITION_FACTS)
            .set(FOOD_NUTRITION_FACTS.FOOD_ID, foodId)
            .set(FOOD_NUTRITION_FACTS.BASE_QUANTITY, model.nutrition.baseQuantity)
            .set(FOOD_NUTRITION_FACTS.BASE_UNIT_ID, model.nutrition.baseUnitId)
            .set(FOOD_NUTRITION_FACTS.CALORIES, model.nutrition.calories)
            .set(FOOD_NUTRITION_FACTS.PROTEIN, model.nutrition.protein)
            .set(FOOD_NUTRITION_FACTS.CARBS, model.nutrition.carbs)
            .set(FOOD_NUTRITION_FACTS.FAT, model.nutrition.fat)
            .set(FOOD_NUTRITION_FACTS.FIBER, model.nutrition.fiber)
            .set(FOOD_NUTRITION_FACTS.SUGAR, model.nutrition.sugar)
            .set(FOOD_NUTRITION_FACTS.SODIUM, model.nutrition.sodium)
            .set(FOOD_NUTRITION_FACTS.CREATED_AT, DSL.currentOffsetDateTime())
            .set(FOOD_NUTRITION_FACTS.UPDATED_AT, DSL.currentOffsetDateTime())
            .execute()

        model.portions.forEach { portion ->
            dsl.insertInto(FOOD_SERVING_PORTIONS)
                .set(FOOD_SERVING_PORTIONS.FOOD_ID, foodId)
                .set(FOOD_SERVING_PORTIONS.SERVING_UNIT_ID, portion.servingUnitId)
                .set(FOOD_SERVING_PORTIONS.AMOUNT, portion.amount)
                .set(FOOD_SERVING_PORTIONS.GRAM_WEIGHT, portion.gramWeight)
                .set(FOOD_SERVING_PORTIONS.MODIFIER, portion.modifier)
                .set(FOOD_SERVING_PORTIONS.PORTION_DESCRIPTION, portion.portionDescription)
                .set(FOOD_SERVING_PORTIONS.SORT_ORDER, portion.sortOrder)
                .set(FOOD_SERVING_PORTIONS.CREATED_AT, DSL.currentOffsetDateTime())
                .set(FOOD_SERVING_PORTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .execute()
        }

        model.localizations.forEach { localization ->
            dsl.insertInto(FOOD_LOCALIZATIONS)
                .set(FOOD_LOCALIZATIONS.FOOD_ID, foodId)
                .set(FOOD_LOCALIZATIONS.LOCALE, localization.locale)
                .set(FOOD_LOCALIZATIONS.DISPLAY_NAME, localization.displayName)
                .set(FOOD_LOCALIZATIONS.NORMALIZED_DISPLAY_NAME, localization.normalizedDisplayName)
                .set(FOOD_LOCALIZATIONS.SOURCE, "GYRO_CURATED")
                .set(FOOD_LOCALIZATIONS.REVIEW_STATUS, localization.reviewStatus)
                .set(FOOD_LOCALIZATIONS.CREATED_AT, DSL.currentOffsetDateTime())
                .set(FOOD_LOCALIZATIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .execute()
        }

        model.aliases.forEach { alias ->
            dsl.insertInto(FOOD_ALIASES)
                .set(FOOD_ALIASES.FOOD_ID, foodId)
                .set(FOOD_ALIASES.LOCALE, alias.locale)
                .set(FOOD_ALIASES.ALIAS, alias.alias)
                .set(FOOD_ALIASES.NORMALIZED_ALIAS, alias.normalizedAlias)
                .set(FOOD_ALIASES.SOURCE, "GYRO_CURATED")
                .set(FOOD_ALIASES.REVIEW_STATUS, alias.reviewStatus)
                .set(FOOD_ALIASES.CREATED_AT, DSL.currentOffsetDateTime())
                .set(FOOD_ALIASES.UPDATED_AT, DSL.currentOffsetDateTime())
                .execute()
        }

        searchTerms.forEach { term ->
            dsl.insertInto(FOOD_SEARCH_TERMS)
                .set(FOOD_SEARCH_TERMS.FOOD_ID, foodId)
                .set(FOOD_SEARCH_TERMS.LOCALE, term.locale)
                .set(FOOD_SEARCH_TERMS.TERM, term.term)
                .set(FOOD_SEARCH_TERMS.NORMALIZED_TERM, term.normalizedTerm)
                .set(FOOD_SEARCH_TERMS.TERM_KIND, term.termKind)
                .set(FOOD_SEARCH_TERMS.WEIGHT, term.weight)
                .set(
                    FOOD_SEARCH_TERMS.SEARCH_VECTOR,
                    DSL.field(
                        "to_tsvector(cast((case when {0} = 'fa' then 'simple' else 'english' end) as regconfig), {1})",
                        String::class.java,
                        DSL.inline(term.locale),
                        DSL.inline(term.normalizedTerm),
                    )
                )
                .set(FOOD_SEARCH_TERMS.CREATED_AT, DSL.currentOffsetDateTime())
                .execute()
        }
    }

    private fun AdminFoodListFilters.toCondition(): Condition {
        var condition: Condition = DSL.trueCondition()

        if (!normalizedQuery.isNullOrBlank()) {
            val pattern = "%${escapeLike(normalizedQuery)}%"
            var queryCondition = FOODS.NORMALIZED_NAME.like(pattern, '\\')
                .or(FOODS.NORMALIZED_BRAND_NAME.like(pattern, '\\'))
                .or(
                    DSL.exists(
                        DSL.selectOne()
                            .from(FOOD_ALIASES)
                            .where(
                                FOOD_ALIASES.FOOD_ID.eq(FOODS.ID)
                                    .and(FOOD_ALIASES.NORMALIZED_ALIAS.like(pattern, '\\'))
                            )
                    )
                )
            if (!query.isNullOrBlank()) {
                queryCondition = queryCondition.or(FOODS.PUBLIC_ID.eq(query.trim()))
            }
            condition = condition.and(queryCondition)
        }

        if (!source.isNullOrBlank()) {
            condition = condition.and(FOODS.SOURCE.eq(source))
        }
        if (!type.isNullOrBlank()) {
            condition = condition.and(FOODS.TYPE.eq(type))
        }
        if (!curationStatus.isNullOrBlank()) {
            condition = condition.and(FOODS.CURATION_STATUS.eq(curationStatus))
        }
        if (archived != null) {
            condition = condition.and(if (archived) FOODS.ARCHIVED_AT.isNotNull else FOODS.ARCHIVED_AT.isNull)
        }
        if (searchable != null) {
            condition = condition.and(FOODS.IS_SEARCHABLE.eq(searchable))
        }

        return condition
    }

    private fun catalogFoodCondition(): Condition =
        FOODS.TYPE.eq("SYSTEM")
            .and(FOODS.SOURCE.eq("GYRO_CURATED"))
            .and(FOODS.OWNER_USER_ID.isNull)

    private fun userFoodCondition(): Condition =
        FOODS.TYPE.eq("CUSTOM")
            .and(FOODS.OWNER_USER_ID.isNotNull)

    private fun ownershipCondition(ownership: AdminFoodOwnership): Condition = when (ownership) {
        AdminFoodOwnership.CATALOG -> catalogFoodCondition()
        AdminFoodOwnership.USER -> userFoodCondition()
        AdminFoodOwnership.ALL -> catalogFoodCondition().or(userFoodCondition())
    }

    private fun isCatalogFood(type: String, source: String, ownerUserId: UUID?): Boolean =
        type == "SYSTEM" && source == "GYRO_CURATED" && ownerUserId == null

    private fun fetchLocaleCoverage(foodIds: List<UUID>): Map<UUID, List<String>> {
        if (foodIds.isEmpty()) return emptyMap()
        return dsl.select(FOOD_LOCALIZATIONS.FOOD_ID, FOOD_LOCALIZATIONS.LOCALE)
            .from(FOOD_LOCALIZATIONS)
            .where(FOOD_LOCALIZATIONS.FOOD_ID.`in`(foodIds))
            .fetch()
            .groupBy({ it.get(FOOD_LOCALIZATIONS.FOOD_ID) }, { it.get(FOOD_LOCALIZATIONS.LOCALE) })
            .mapValues { (_, locales) -> locales.sorted() }
    }

    private fun fetchNutritionPresence(foodIds: List<UUID>): Set<UUID> {
        if (foodIds.isEmpty()) return emptySet()
        return dsl.select(FOOD_NUTRITION_FACTS.FOOD_ID)
            .from(FOOD_NUTRITION_FACTS)
            .where(FOOD_NUTRITION_FACTS.FOOD_ID.`in`(foodIds))
            .fetchSet(FOOD_NUTRITION_FACTS.FOOD_ID)
    }

    private fun fetchPortionCounts(foodIds: List<UUID>): Map<UUID, Int> {
        if (foodIds.isEmpty()) return emptyMap()
        val countField = DSL.count()
        return dsl.select(FOOD_SERVING_PORTIONS.FOOD_ID, countField)
            .from(FOOD_SERVING_PORTIONS)
            .where(FOOD_SERVING_PORTIONS.FOOD_ID.`in`(foodIds))
            .groupBy(FOOD_SERVING_PORTIONS.FOOD_ID)
            .fetchMap(FOOD_SERVING_PORTIONS.FOOD_ID, countField)
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
