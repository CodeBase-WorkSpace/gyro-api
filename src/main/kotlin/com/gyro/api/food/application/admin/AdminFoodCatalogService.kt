package com.gyro.api.food.application.admin

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.CatalogVersionConflictException
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.common.pagination.Pagination
import com.gyro.api.food.infrastructure.FoodSearchNormalizer
import com.gyro.api.food.infrastructure.admin.*
import com.gyro.api.food.web.dto.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Service
class AdminFoodCatalogService(
    private val repository: AdminFoodCatalogRepository,
    private val foodSearchNormalizer: FoodSearchNormalizer,
    private val accountAuditService: AccountAuditService,
) {
    @Transactional(readOnly = true)
    fun listFoods(
        query: String?,
        source: String?,
        type: String?,
        curationStatus: String?,
        archived: Boolean?,
        searchable: Boolean?,
        ownership: String?,
        page: Int?,
        size: Int?,
    ): PageResponse<AdminFoodSummaryResponse> {
        val pagination = Pagination.normalize(page, size)
        val trimmedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        return repository.listFoods(
            filters = AdminFoodListFilters(
                query = trimmedQuery,
                normalizedQuery = trimmedQuery?.let(foodSearchNormalizer::normalizeFoodName),
                source = source?.trim()?.takeIf { it.isNotBlank() }?.uppercase(),
                type = type?.trim()?.takeIf { it.isNotBlank() }?.uppercase(),
                curationStatus = curationStatus?.trim()?.takeIf { it.isNotBlank() }?.uppercase(),
                archived = archived,
                searchable = searchable,
                ownership = parseOwnership(ownership),
            ),
            page = pagination.page,
            size = pagination.size,
        )
    }

    private fun parseOwnership(ownership: String?): AdminFoodOwnership {
        val normalized = ownership?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            ?: return AdminFoodOwnership.CATALOG
        return AdminFoodOwnership.entries.firstOrNull { it.name == normalized }
            ?: throw FieldValidationException(
                fieldErrors = listOf(
                    ApiErrorResponse.FieldError(
                        field = "ownership",
                        errorMessage = "ownership must be one of CATALOG, USER, ALL.",
                    ),
                ),
            )
    }

    @Transactional(readOnly = true)
    fun getFood(foodId: String): AdminFoodDetailResponse {
        return repository.findDetail(foodId.trim()) ?: throw ResourceNotFoundException("Food")
    }

    @Transactional(readOnly = true)
    fun duplicateSuggestions(name: String, excludeFoodId: String?): List<AdminDuplicateSuggestionResponse> {
        val normalizedName = foodSearchNormalizer.normalizeFoodName(name)
        val excludedId = excludeFoodId?.trim()?.takeIf { it.isNotBlank() }?.let(repository::findFoodIdByPublicId)
        return repository.findDuplicates(
            normalizedName = normalizedName,
            excludeFoodId = excludedId,
            limit = DUPLICATE_SUGGESTION_LIMIT,
        )
    }

    @Transactional
    fun createFood(actorId: UUID, request: AdminCreateFoodRequest): AdminFoodMutationResponse {
        val model = buildWriteModel(
            name = request.name,
            brandName = request.brandName,
            categoryId = request.categoryId,
            curationStatus = request.curationStatus,
            isSearchable = request.isSearchable,
            localizations = request.localizations,
            aliases = request.aliases,
            nutrition = request.nutrition,
            portions = request.portions,
        )
        val warnings = collectWarnings(model, request.portions, excludeFoodId = null)

        val publicId = "food_${UUID.randomUUID().toString().replace("-", "")}"
        repository.createFood(
            publicId = publicId,
            model = model,
            searchTerms = buildSearchTerms(model),
        )

        val detail = repository.findDetail(publicId) ?: error("Created food $publicId could not be read back.")
        return AdminFoodMutationResponse(food = detail, warnings = warnings).also { result ->
            recordCatalogChange(
                actorId = actorId,
                scope = "food_create",
                foodId = result.food.id,
                metadata = mapOf("warningCount" to result.warnings.size),
            )
        }
    }

    @Transactional
    fun updateFood(
        actorId: UUID,
        foodId: String,
        request: com.gyro.api.food.web.dto.AdminUpdateFoodRequest,
    ): AdminFoodMutationResponse {
        val trimmedFoodId = foodId.trim()
        val internalId = repository.findFoodIdByPublicId(trimmedFoodId) ?: throw ResourceNotFoundException("Food")

        val model = buildWriteModel(
            name = request.name,
            brandName = request.brandName,
            categoryId = request.categoryId,
            curationStatus = request.curationStatus,
            isSearchable = request.isSearchable,
            localizations = request.localizations,
            aliases = request.aliases,
            nutrition = request.nutrition,
            portions = request.portions,
        )
        val warnings = collectWarnings(model, request.portions, excludeFoodId = internalId)

        val updated = repository.updateFood(
            foodId = internalId,
            expectedLockVersion = request.expectedLockVersion,
            model = model,
            searchTerms = buildSearchTerms(model),
        )
        if (!updated) {
            val actual = repository.currentLockVersion(internalId) ?: throw ResourceNotFoundException("Food")
            throw CatalogVersionConflictException(
                expectedLockVersion = request.expectedLockVersion,
                actualLockVersion = actual,
            )
        }

        val detail = repository.findDetail(trimmedFoodId) ?: error("Updated food $trimmedFoodId could not be read back.")
        return AdminFoodMutationResponse(food = detail, warnings = warnings).also { result ->
            recordCatalogChange(
                actorId = actorId,
                scope = "food_update",
                foodId = result.food.id,
                metadata = mapOf(
                    "lockVersion" to result.food.lockVersion,
                    "warningCount" to result.warnings.size,
                ),
            )
        }
    }

    @Transactional
    fun setArchived(actorId: UUID, foodId: String, archived: Boolean): AdminFoodArchiveResponse {
        val trimmedFoodId = foodId.trim()
        if (repository.findFoodIdByPublicId(trimmedFoodId) == null) {
            throw ResourceNotFoundException("Food")
        }
        val changed = repository.setArchived(trimmedFoodId, archived)
        if (!changed) {
            throw FieldValidationException(
                message = "Food archive state is already ${if (archived) "archived" else "active"}.",
                fieldErrors = listOf(ApiErrorResponse.FieldError(field = "archived", errorMessage = "No state change.")),
            )
        }
        return AdminFoodArchiveResponse(id = trimmedFoodId, archived = archived).also { result ->
            recordCatalogChange(
                actorId = actorId,
                scope = if (archived) "food_archive" else "food_restore",
                foodId = result.id,
            )
        }
    }

    @Transactional(readOnly = true)
    fun listServingUnits(): List<AdminServingUnitResponse> = repository.listServingUnits()

    @Transactional(readOnly = true)
    fun listCategories(): List<AdminFoodCategoryResponse> = repository.listCategories()

    @Transactional
    fun createCategory(actorId: UUID, request: AdminCreateCategoryRequest): AdminFoodCategoryResponse {
        val name = request.name.trim()
        val normalizedName = foodSearchNormalizer.normalizeFoodName(name)
        if (normalizedName.isBlank()) {
            throw FieldValidationException(
                fieldErrors = listOf(ApiErrorResponse.FieldError(field = "name", errorMessage = "Category name is invalid.")),
            )
        }
        repository.findCategoryBySourceAndNormalizedName(CURATED_SOURCE, normalizedName)?.let { return it }
        return repository.createCategory(CURATED_SOURCE, name, normalizedName).also { result ->
            recordCatalogChange(
                actorId = actorId,
                scope = "category_create",
                metadata = mapOf("categoryId" to result.id),
            )
        }
    }

    private fun recordCatalogChange(
        actorId: UUID,
        scope: String,
        foodId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        val auditMetadata = metadata.toMutableMap().apply {
            put("scope", scope)
            foodId?.let { put("foodId", it) }
        }
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = actorId,
            eventType = AccountAuditEventType.ADMIN_CATALOG_CHANGED,
            metadata = auditMetadata,
        )
    }

    private fun buildWriteModel(
        name: String,
        brandName: String?,
        categoryId: UUID?,
        curationStatus: String,
        isSearchable: Boolean,
        localizations: List<AdminFoodLocalizationRequest>,
        aliases: List<AdminFoodAliasRequest>,
        nutrition: AdminFoodNutritionRequest,
        portions: List<AdminFoodPortionRequest>,
    ): AdminFoodWriteModel {
        val fieldErrors = mutableListOf<ApiErrorResponse.FieldError>()

        val trimmedName = name.trim()
        val normalizedName = foodSearchNormalizer.normalizeFoodName(trimmedName)
        if (normalizedName.isBlank()) {
            fieldErrors += ApiErrorResponse.FieldError(field = "name", errorMessage = "Name is invalid after normalization.")
        }

        val trimmedBrand = brandName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedBrand = trimmedBrand?.let(foodSearchNormalizer::normalizeFoodName)

        if (categoryId != null && !repository.categoryExists(categoryId)) {
            fieldErrors += ApiErrorResponse.FieldError(field = "categoryId", errorMessage = "Category does not exist.")
        }

        val localizationModels = localizations.map { localization ->
            AdminLocalizationWriteModel(
                locale = localization.locale,
                displayName = localization.displayName.trim(),
                normalizedDisplayName = foodSearchNormalizer.normalizeFoodName(localization.displayName.trim()),
                reviewStatus = localization.reviewStatus,
            )
        }
        localizationModels.groupBy { it.locale }.filterValues { it.size > 1 }.keys.forEach { locale ->
            fieldErrors += ApiErrorResponse.FieldError(
                field = "localizations",
                errorMessage = "Locale $locale appears more than once.",
            )
        }

        val aliasModels = aliases.map { alias ->
            AdminAliasWriteModel(
                locale = alias.locale,
                alias = alias.alias.trim(),
                normalizedAlias = foodSearchNormalizer.normalizeFoodName(alias.alias.trim()),
                reviewStatus = alias.reviewStatus,
            )
        }.distinctBy { it.locale to it.normalizedAlias }

        val baseUnit = repository.findServingUnitByCode(nutrition.baseUnitCode.trim().uppercase())
        if (baseUnit == null) {
            fieldErrors += ApiErrorResponse.FieldError(field = "nutrition.baseUnitCode", errorMessage = "Serving unit does not exist.")
        }

        val portionModels = portions.mapIndexed { index, portion ->
            val unitCode = portion.servingUnitCode?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            val unit = unitCode?.let(repository::findServingUnitByCode)
            if (unitCode != null && unit == null) {
                fieldErrors += ApiErrorResponse.FieldError(
                    field = "portions[$index].servingUnitCode",
                    errorMessage = "Serving unit does not exist.",
                )
            }
            if (unitCode == null && portion.portionDescription.isNullOrBlank()) {
                fieldErrors += ApiErrorResponse.FieldError(
                    field = "portions[$index]",
                    errorMessage = "A portion needs a serving unit or a description.",
                )
            }
            AdminPortionWriteModel(
                servingUnitId = unit?.id,
                amount = portion.amount.setScale(4, RoundingMode.HALF_UP),
                gramWeight = portion.gramWeight?.setScale(4, RoundingMode.HALF_UP),
                modifier = portion.modifier?.trim()?.takeIf { it.isNotBlank() },
                portionDescription = portion.portionDescription?.trim()?.takeIf { it.isNotBlank() },
                sortOrder = portion.sortOrder,
            )
        }

        if (fieldErrors.isNotEmpty()) {
            throw FieldValidationException(fieldErrors = fieldErrors)
        }

        return AdminFoodWriteModel(
            name = trimmedName,
            normalizedName = normalizedName,
            brandName = trimmedBrand,
            normalizedBrandName = normalizedBrand,
            categoryId = categoryId,
            curationStatus = curationStatus,
            isSearchable = isSearchable,
            localizations = localizationModels,
            aliases = aliasModels,
            nutrition = AdminNutritionWriteModel(
                baseQuantity = nutrition.baseQuantity.setScale(4, RoundingMode.HALF_UP),
                baseUnitId = requireNotNull(baseUnit).id,
                calories = nutrition.calories.setScale(2, RoundingMode.HALF_UP),
                protein = nutrition.protein.setScale(2, RoundingMode.HALF_UP),
                carbs = nutrition.carbs.setScale(2, RoundingMode.HALF_UP),
                fat = nutrition.fat.setScale(2, RoundingMode.HALF_UP),
                fiber = nutrition.fiber.setScale(2, RoundingMode.HALF_UP),
                sugar = nutrition.sugar.setScale(2, RoundingMode.HALF_UP),
                sodium = nutrition.sodium.setScale(2, RoundingMode.HALF_UP),
            ),
            portions = portionModels,
        )
    }

    private fun collectWarnings(
        model: AdminFoodWriteModel,
        portionRequests: List<AdminFoodPortionRequest>,
        excludeFoodId: UUID?,
    ): List<AdminCatalogValidationWarning> {
        val warnings = mutableListOf<AdminCatalogValidationWarning>()

        val expectedCalories = model.nutrition.protein.multiply(BigDecimal(4))
            .add(model.nutrition.carbs.multiply(BigDecimal(4)))
            .add(model.nutrition.fat.multiply(BigDecimal(9)))
        val tolerance = maxOf(
            BigDecimal(20),
            model.nutrition.calories.multiply(BigDecimal("0.15")),
        )
        if (model.nutrition.calories.subtract(expectedCalories).abs() > tolerance) {
            warnings += AdminCatalogValidationWarning(
                code = "MACRO_INCONSISTENT",
                message = "Calories (${model.nutrition.calories.stripTrailingZeros().toPlainString()}) differ from the " +
                    "macro-derived estimate (${expectedCalories.setScale(0, RoundingMode.HALF_UP).toPlainString()}) " +
                    "by more than the accepted tolerance.",
            )
        }

        portionRequests.forEachIndexed { index, portion ->
            val unitCode = portion.servingUnitCode?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            val unit = unitCode?.let(repository::findServingUnitByCode)
            val convertible = portion.gramWeight != null ||
                unit?.gramMultiplier != null ||
                unit?.milliliterMultiplier != null
            if (!convertible) {
                warnings += AdminCatalogValidationWarning(
                    code = "PORTION_NOT_CONVERTIBLE",
                    message = "Portion ${index + 1} has no gram weight and its unit has no mass or volume multiplier; " +
                        "nutrition cannot be derived for it.",
                )
            }
        }

        if (model.localizations.none { it.locale == "fa" }) {
            warnings += AdminCatalogValidationWarning(
                code = "MISSING_FA_LOCALIZATION",
                message = "No Farsi localization was provided; the food will fall back to its base name.",
            )
        }

        val duplicates = repository.findDuplicates(
            normalizedName = model.normalizedName,
            excludeFoodId = excludeFoodId,
            limit = DUPLICATE_SUGGESTION_LIMIT,
        )
        if (duplicates.isNotEmpty()) {
            warnings += AdminCatalogValidationWarning(
                code = "DUPLICATE_CANDIDATES",
                message = "Similar foods already exist: ${duplicates.joinToString { "${it.name} (${it.id})" }}.",
            )
        }

        return warnings
    }

    private fun buildSearchTerms(model: AdminFoodWriteModel): List<AdminSearchTermWriteModel> {
        val terms = mutableListOf<AdminSearchTermWriteModel>()

        val nameLocale = foodSearchNormalizer.normalizeSearchQuery(model.name).locale
        terms += AdminSearchTermWriteModel(
            locale = nameLocale,
            term = model.name,
            normalizedTerm = model.normalizedName,
            termKind = "NAME",
            weight = BigDecimal.ONE,
        )

        model.localizations.forEach { localization ->
            if (localization.normalizedDisplayName.isNotBlank()) {
                terms += AdminSearchTermWriteModel(
                    locale = localization.locale,
                    term = localization.displayName,
                    normalizedTerm = localization.normalizedDisplayName,
                    termKind = "NAME",
                    weight = BigDecimal.ONE,
                )
            }
        }

        model.aliases.forEach { alias ->
            if (alias.normalizedAlias.isNotBlank() && alias.reviewStatus != "REJECTED") {
                terms += AdminSearchTermWriteModel(
                    locale = alias.locale,
                    term = alias.alias,
                    normalizedTerm = alias.normalizedAlias,
                    termKind = "ALIAS",
                    weight = BigDecimal("0.8"),
                )
            }
        }

        if (model.brandName != null && !model.normalizedBrandName.isNullOrBlank()) {
            val brandLocale = foodSearchNormalizer.normalizeSearchQuery(model.brandName).locale
            terms += AdminSearchTermWriteModel(
                locale = brandLocale,
                term = model.brandName,
                normalizedTerm = model.normalizedBrandName,
                termKind = "BRAND",
                weight = BigDecimal("0.6"),
            )
        }

        return terms.distinctBy { Triple(it.locale, it.termKind, it.normalizedTerm) }
    }

    private companion object {
        private const val DUPLICATE_SUGGESTION_LIMIT = 5
        private const val CURATED_SOURCE = "GYRO_CURATED"
    }
}
