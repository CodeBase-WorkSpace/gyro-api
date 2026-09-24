package com.gyro.api.food.application

import com.gyro.api.common.error.InvalidMealItemException
import com.gyro.api.common.error.InvalidServingDefinitionException
import com.gyro.api.common.error.PlanLimitReachedException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.id.UuidParser
import com.gyro.api.food.config.PlanLimitLockHelper
import com.gyro.api.food.config.PlanLimitProperties
import com.gyro.api.food.domain.ServingDefinition
import com.gyro.api.food.infrastructure.*
import com.gyro.api.food.web.dto.*
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.math.ceil

@Service
class MealService(
    private val mealRepository: MealRepository,
    private val foodSearchNormalizer: FoodSearchNormalizer,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val entitlementGateService: EntitlementGateService,
    private val planLimitProperties: PlanLimitProperties,
    private val planLimitLockHelper: PlanLimitLockHelper,
) {
    @Transactional(readOnly = true)
    fun get(
        ownerUserId: UUID,
        mealId: String,
    ): MealDetailResponse {
        val parsedMealId = UuidParser.parse(mealId.trim()) ?: throw ResourceNotFoundException("Meal")
        val meal = mealRepository.findOwnerMeal(
            ownerUserId = ownerUserId,
            mealId = parsedMealId,
        ) ?: throw ResourceNotFoundException("Meal")
        val itemResponses = mealRepository.findMealNutritionItems(
            ownerUserId = ownerUserId,
            mealIds = setOf(parsedMealId),
        )
            .get(parsedMealId)
            .orEmpty()
            .map { it.toMealItemResponse() }

        return mealDetailResponse(
            mealId = meal.id,
            name = meal.name,
            items = itemResponses,
            servingDefinition = meal.servingDefinition,
        )
    }

    @Transactional(readOnly = true)
    fun list(
        ownerUserId: UUID,
        query: String?,
        page: Int,
        size: Int,
    ): MealListResponse {
        val normalizedQuery = query
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { foodSearchNormalizer.normalizeFoodName(it) }
            ?.takeIf { it.isNotBlank() }
        val totalItems = mealRepository.countOwnerMeals(
            ownerUserId = ownerUserId,
            normalizedQuery = normalizedQuery,
        )

        if (totalItems == 0L) {
            return MealListResponse(
                items = emptyList(),
                page = page,
                size = size,
                totalItems = 0,
                totalPages = 0,
            )
        }

        val meals = mealRepository.findOwnerMeals(
            ownerUserId = ownerUserId,
            normalizedQuery = normalizedQuery,
            limit = size,
            offset = page * size,
        )
        val mealItems = mealRepository.findMealNutritionItems(
            ownerUserId = ownerUserId,
            mealIds = meals.map { it.id }.toSet(),
        )

        return MealListResponse(
            items = meals.map { meal ->
                val items = mealItems[meal.id].orEmpty()
                val nutrition = items.map {
                    calculateItemNutrition(
                        quantity = it.quantity,
                        selectedUnit = it.selectedUnit,
                        nutrition = it.foodNutrition,
                    )
                }

                val totalCalories = nutrition.sumOfCalculatedNutrition { calories }
                val totalProtein = nutrition.sumOfCalculatedNutrition { protein }
                val totalCarbs = nutrition.sumOfCalculatedNutrition { carbs }
                val totalFat = nutrition.sumOfCalculatedNutrition { fat }
                val totalFiber = nutrition.sumOfCalculatedNutrition { fiber }
                val totalSugar = nutrition.sumOfCalculatedNutrition { sugar }
                val totalSodium = nutrition.sumOfCalculatedNutrition { sodium }

                MealSummaryResponse(
                    id = meal.id.toString(),
                    name = meal.name,
                    itemCount = items.size,
                    calories = totalCalories,
                    protein = totalProtein,
                    carbs = totalCarbs,
                    fat = totalFat,
                    fiber = totalFiber,
                    sugar = totalSugar,
                    sodium = totalSodium,
                    servingDefinition = meal.servingDefinition?.toResponse(
                        calories = totalCalories,
                        protein = totalProtein,
                        carbs = totalCarbs,
                        fat = totalFat,
                        fiber = totalFiber,
                        sugar = totalSugar,
                        sodium = totalSodium,
                    ),
                )
            },
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = ceil(totalItems.toDouble() / size).toInt(),
        )
    }

    @Transactional
    fun create(ownerUserId: UUID, request: CreateMealRequest): MealDetailResponse {
        val name = request.name.trim()
        val servingDefinition = servingDefinitionFrom(request.totalBatchWeight, request.servingWeight)
        val preparedItems = prepareItems(ownerUserId, request.items)
        val insertResult = createLimitedMeal(
            ownerUserId = ownerUserId,
            name = name,
            items = preparedItems.map { it.item },
            servingDefinition = servingDefinition,
        )
        val itemResponses = preparedItems.mapIndexed { index, item ->
            item.response.copy(id = insertResult.itemIds[index].toString())
        }

        return mealDetailResponse(
            mealId = insertResult.mealId,
            name = name,
            items = itemResponses,
            servingDefinition = servingDefinition,
        )
    }

    @Transactional
    fun createLimitedMeal(
        ownerUserId: UUID,
        name: String,
        items: List<PreparedMealItem>,
        servingDefinition: ServingDefinition? = null,
    ): MealInsertResult {
        val normalizedName = foodSearchNormalizer.normalizeFoodName(name.trim())
        planLimitLockHelper.lockCustomMealCreation(ownerUserId)
        enforceCustomMealLimit(ownerUserId)
        return mealRepository.createMeal(
            ownerUserId = ownerUserId,
            name = name.trim(),
            normalizedName = normalizedName,
            items = items,
            servingDefinition = servingDefinition,
        )
    }

    @Transactional
    fun update(
        ownerUserId: UUID,
        mealId: String,
        request: UpdateMealRequest,
    ): MealDetailResponse {
        val parsedMealId = UuidParser.parse(mealId.trim()) ?: throw ResourceNotFoundException("Meal")
        mealRepository.findOwnerMeal(ownerUserId, parsedMealId) ?: throw ResourceNotFoundException("Meal")

        val name = request.name.trim()
        val servingDefinition = servingDefinitionFrom(request.totalBatchWeight, request.servingWeight)
        val preparedItems = prepareItems(ownerUserId, request.items)
        val itemIds = mealRepository.replaceOwnerMealItems(
            ownerUserId = ownerUserId,
            mealId = parsedMealId,
            name = name,
            normalizedName = foodSearchNormalizer.normalizeFoodName(name),
            items = preparedItems.map { it.item },
            servingDefinition = servingDefinition,
        ) ?: throw ResourceNotFoundException("Meal")
        val itemResponses = preparedItems.mapIndexed { index, item ->
            item.response.copy(id = itemIds[index].toString())
        }

        return mealDetailResponse(
            mealId = parsedMealId,
            name = name,
            items = itemResponses,
            servingDefinition = servingDefinition,
        )
    }

    @Transactional
    fun archive(ownerUserId: UUID, mealId: String): MealArchiveResponse {
        val parsedMealId = UuidParser.parse(mealId.trim()) ?: throw ResourceNotFoundException("Meal")
        if (!mealRepository.archiveOwnerMeal(ownerUserId, parsedMealId)) {
            throw ResourceNotFoundException("Meal")
        }

        return MealArchiveResponse(
            mealId = parsedMealId.toString(),
            archived = true,
        )
    }

    private fun prepareItems(
        ownerUserId: UUID,
        requestItems: List<com.gyro.api.food.web.dto.CreateMealItemRequest>,
    ): List<PreparedMealItemView> {
        val locale = userFoodLocale(ownerUserId)
        val normalizedItems = requestItems.map { item ->
            NormalizedMealItemRequest(
                foodIdentifier = item.foodId.trim(),
                quantity = item.quantity.setScale(4, RoundingMode.HALF_UP),
                servingUnitCode = item.servingUnit.trim().uppercase(Locale.ROOT),
            )
        }

        val selectedUnits = mealRepository.findServingUnitsByCode(normalizedItems.map { it.servingUnitCode }.toSet())
        val missingUnit = normalizedItems.firstOrNull { selectedUnits[it.servingUnitCode] == null }
        if (missingUnit != null) {
            throw InvalidMealItemException("servingUnit is invalid.")
        }

        val parsedFoodIds = normalizedItems.map { UuidParser.parse(it.foodIdentifier) }
        val nutritionById = mealRepository.findVisibleFoodNutrition(
            ownerUserId = ownerUserId,
            foodIds = parsedFoodIds.filterNotNull().toSet(),
            locale = locale,
        )
        val nutritionByPublicId = mealRepository.findVisibleFoodNutritionByPublicIds(
            ownerUserId = ownerUserId,
            publicIds = normalizedItems.map { it.foodIdentifier }.toSet(),
            locale = locale,
        )
        val resolvedNutrition = normalizedItems.mapIndexed { index, item ->
            parsedFoodIds[index]?.let(nutritionById::get) ?: nutritionByPublicId[item.foodIdentifier]
        }
        if (resolvedNutrition.any { it == null }) {
            throw InvalidMealItemException("Food was not found.")
        }

        return normalizedItems.mapIndexed { index, item ->
            val nutrition = requireNotNull(resolvedNutrition[index])
            val selectedUnit = selectedUnits.getValue(item.servingUnitCode)
            val itemNutrition = calculateItemNutrition(
                quantity = item.quantity,
                selectedUnit = selectedUnit,
                nutrition = nutrition,
            )

            PreparedMealItemView(
                item = PreparedMealItem(
                    foodId = nutrition.foodId,
                    quantity = item.quantity,
                    servingUnit = selectedUnit,
                ),
                response = MealItemResponse(
                    id = "",
                    foodId = nutrition.foodId.toString(),
                    foodName = nutrition.foodName,
                    quantity = item.quantity,
                    servingUnit = ServingUnitSummaryResponse(
                        id = selectedUnit.id.toString(),
                        code = selectedUnit.code,
                        label = selectedUnit.code,
                    ),
                    calories = itemNutrition.calories,
                    protein = itemNutrition.protein,
                    carbs = itemNutrition.carbs,
                    fat = itemNutrition.fat,
                    fiber = itemNutrition.fiber,
                    sugar = itemNutrition.sugar,
                    sodium = itemNutrition.sodium,
                ),
            )
        }
    }

    private fun mealDetailResponse(
        mealId: UUID,
        name: String,
        items: List<MealItemResponse>,
        servingDefinition: ServingDefinition? = null,
    ): MealDetailResponse {
        val calories = items.sumOfNutrition { calories }
        val protein = items.sumOfNutrition { protein }
        val carbs = items.sumOfNutrition { carbs }
        val fat = items.sumOfNutrition { fat }
        val fiber = items.sumOfNutrition { fiber }
        val sugar = items.sumOfNutrition { sugar }
        val sodium = items.sumOfNutrition { sodium }

        return MealDetailResponse(
            id = mealId.toString(),
            name = name,
            items = items,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber,
            sugar = sugar,
            sodium = sodium,
            servingDefinition = servingDefinition?.toResponse(
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber,
                sugar = sugar,
                sodium = sodium,
            ),
        )
    }

    private fun servingDefinitionFrom(
        totalBatchWeight: BigDecimal?,
        servingWeight: BigDecimal?,
    ): ServingDefinition? {
        return try {
            ServingDefinition.ofNullable(
                totalBatchWeight = totalBatchWeight?.setScale(4, RoundingMode.HALF_UP),
                servingWeight = servingWeight?.setScale(4, RoundingMode.HALF_UP),
            )
        } catch (exception: IllegalArgumentException) {
            throw InvalidServingDefinitionException(exception.message ?: "Serving definition is invalid.")
        }
    }

    private fun ServingDefinition.toResponse(
        calories: BigDecimal,
        protein: BigDecimal,
        carbs: BigDecimal,
        fat: BigDecimal,
        fiber: BigDecimal,
        sugar: BigDecimal,
        sodium: BigDecimal,
    ): ServingDefinitionResponse {
        val fraction = batchFraction(BigDecimal.ONE)

        return ServingDefinitionResponse(
            totalBatchWeight = totalBatchWeight,
            servingWeight = servingWeight,
            servingsPerBatch = servingsPerBatch,
            perServing = ServingNutritionResponse(
                calories = calories.multiply(fraction).nutritionScale(),
                protein = protein.multiply(fraction).nutritionScale(),
                carbs = carbs.multiply(fraction).nutritionScale(),
                fat = fat.multiply(fraction).nutritionScale(),
                fiber = fiber.multiply(fraction).nutritionScale(),
                sugar = sugar.multiply(fraction).nutritionScale(),
                sodium = sodium.multiply(fraction).nutritionScale(),
            ),
        )
    }

    private fun userFoodLocale(userId: UUID): String {
        val locale = userProfileRepository.findByUser_Id(userId)?.locale
            ?: userPreferencesProperties.normalizedDefaultLocale
        val normalized = locale.trim().lowercase(Locale.ROOT)

        return when {
            normalized == "fa" || normalized.startsWith("fa-") -> "fa"
            normalized == "en" || normalized.startsWith("en-") -> "en"
            else -> normalized.ifBlank { "en" }
        }
    }

    private fun enforceCustomMealLimit(ownerUserId: UUID) {
        if (entitlementGateService.hasFeatureAccess(ownerUserId, HIGHER_LIMITS_FEATURE)) {
            return
        }

        if (mealRepository.countOwnerMeals(ownerUserId, normalizedQuery = null) >= planLimitProperties.freeCustomMeals) {
            throw PlanLimitReachedException(
                featureKey = HIGHER_LIMITS_FEATURE,
                limitName = "custom_meals",
                limitValue = planLimitProperties.freeCustomMeals,
            )
        }
    }

    private fun calculateItemNutrition(
        quantity: BigDecimal,
        selectedUnit: MealServingUnitRecord,
        nutrition: MealFoodNutritionRecord,
    ): CalculatedNutrition {
        val baseQuantity = selectedQuantityInBaseUnit(
            quantity = quantity,
            selectedUnit = selectedUnit,
            baseUnit = nutrition.baseUnit,
        )
        val ratio = baseQuantity.divide(nutrition.baseQuantity, 8, RoundingMode.HALF_UP)

        return CalculatedNutrition(
            calories = nutrition.calories.multiply(ratio).nutritionScale(),
            protein = nutrition.protein.multiply(ratio).nutritionScale(),
            carbs = nutrition.carbs.multiply(ratio).nutritionScale(),
            fat = nutrition.fat.multiply(ratio).nutritionScale(),
            fiber = nutrition.fiber.multiply(ratio).nutritionScale(),
            sugar = nutrition.sugar.multiply(ratio).nutritionScale(),
            sodium = nutrition.sodium.multiply(ratio).nutritionScale(),
        )
    }

    private fun selectedQuantityInBaseUnit(
        quantity: BigDecimal,
        selectedUnit: MealServingUnitRecord,
        baseUnit: MealServingUnitRecord,
    ): BigDecimal {
        if (selectedUnit.id == baseUnit.id) {
            return quantity
        }

        if (selectedUnit.gramMultiplier != null && baseUnit.gramMultiplier != null) {
            return quantity.multiply(selectedUnit.gramMultiplier)
                .divide(baseUnit.gramMultiplier, 8, RoundingMode.HALF_UP)
        }

        if (selectedUnit.milliliterMultiplier != null && baseUnit.milliliterMultiplier != null) {
            return quantity.multiply(selectedUnit.milliliterMultiplier)
                .divide(baseUnit.milliliterMultiplier, 8, RoundingMode.HALF_UP)
        }

        throw InvalidMealItemException("servingUnit is incompatible with food base unit.")
    }

    private fun MealNutritionItemRecord.toMealItemResponse(): MealItemResponse {
        val itemNutrition = calculateItemNutrition(
            quantity = quantity,
            selectedUnit = selectedUnit,
            nutrition = foodNutrition,
        )

        return MealItemResponse(
            id = id.toString(),
            foodId = foodNutrition.foodId.toString(),
            foodName = foodNutrition.foodName,
            quantity = quantity,
            servingUnit = ServingUnitSummaryResponse(
                id = selectedUnit.id.toString(),
                code = selectedUnit.code,
                label = selectedUnit.code,
            ),
            calories = itemNutrition.calories,
            protein = itemNutrition.protein,
            carbs = itemNutrition.carbs,
            fat = itemNutrition.fat,
            fiber = itemNutrition.fiber,
            sugar = itemNutrition.sugar,
            sodium = itemNutrition.sodium,
        )
    }
}

private data class NormalizedMealItemRequest(
    val foodIdentifier: String,
    val quantity: BigDecimal,
    val servingUnitCode: String,
)

private data class PreparedMealItemView(
    val item: PreparedMealItem,
    val response: MealItemResponse,
)

private data class CalculatedNutrition(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

private fun BigDecimal.nutritionScale(): BigDecimal {
    return setScale(2, RoundingMode.HALF_UP)
}

private inline fun Iterable<com.gyro.api.food.web.dto.MealItemResponse>.sumOfNutrition(
    selector: com.gyro.api.food.web.dto.MealItemResponse.() -> BigDecimal,
): BigDecimal {
    return fold(BigDecimal.ZERO) { total, item -> total + item.selector() }.nutritionScale()
}

private inline fun Iterable<CalculatedNutrition>.sumOfCalculatedNutrition(
    selector: CalculatedNutrition.() -> BigDecimal,
): BigDecimal {
    return fold(BigDecimal.ZERO) { total, item -> total + item.selector() }.nutritionScale()
}

private const val HIGHER_LIMITS_FEATURE = "higher_limits"
