package com.gyro.api.food.application

import com.gyro.api.common.error.InvalidCustomFoodPortionException
import com.gyro.api.common.error.InvalidServingUnitException
import com.gyro.api.common.error.PlanLimitReachedException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.food.config.PlanLimitLockHelper
import com.gyro.api.food.config.PlanLimitProperties
import com.gyro.api.food.infrastructure.CustomFoodRepository
import com.gyro.api.food.infrastructure.FoodSearchNormalizer
import com.gyro.api.food.web.dto.CreateCustomFoodRequest
import com.gyro.api.food.web.dto.CustomFoodArchiveResponse
import com.gyro.api.food.web.dto.CustomFoodPortionRequest
import com.gyro.api.food.web.dto.CustomFoodResponse
import com.gyro.api.food.web.dto.UpdateCustomFoodRequest
import com.gyro.api.subscription.application.EntitlementGateService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Service
class CustomFoodService(
    private val customFoodRepository: CustomFoodRepository,
    private val foodSearchNormalizer: FoodSearchNormalizer,
    private val entitlementGateService: EntitlementGateService,
    private val planLimitProperties: PlanLimitProperties,
    private val planLimitLockHelper: PlanLimitLockHelper,
) {

    @Transactional
    fun create(ownerUserId: UUID, request: CreateCustomFoodRequest): CustomFoodResponse {
        val normalizedRequest = normalize(request.toEditableCustomFoodRequest())
        planLimitLockHelper.lockCustomFoodCreation(ownerUserId)
        enforceCustomFoodLimit(ownerUserId)

        return customFoodRepository.create(
            ownerUserId = ownerUserId,
            request = normalizedRequest.toCreateRequest(),
            normalizedName = normalizedRequest.normalizedName,
            locale = normalizedRequest.locale,
        )
    }

    @Transactional
    fun update(ownerUserId: UUID, foodId: String, request: UpdateCustomFoodRequest): CustomFoodResponse {
        val normalizedRequest = normalize(request.toEditableCustomFoodRequest())

        return customFoodRepository.updateOwnerCustomFood(
            ownerUserId = ownerUserId,
            foodId = foodId.trim(),
            request = normalizedRequest.toCreateRequest(),
            normalizedName = normalizedRequest.normalizedName,
            locale = normalizedRequest.locale,
        ) ?: throw ResourceNotFoundException("Food")
    }

    @Transactional
    fun archive(ownerUserId: UUID, foodId: String): CustomFoodArchiveResponse {
        val normalizedFoodId = foodId.trim()
        val archived = customFoodRepository.archiveOwnerCustomFood(
            ownerUserId = ownerUserId,
            foodId = normalizedFoodId,
        )
        if (!archived) {
            throw ResourceNotFoundException("Food")
        }

        return CustomFoodArchiveResponse(
            foodId = normalizedFoodId,
            archived = true,
        )
    }

    private fun normalize(request: EditableCustomFoodRequest): NormalizedCustomFoodRequest {
        val servingUnitCode = request.servingUnit.trim().uppercase(Locale.ROOT)
        if (!customFoodRepository.servingUnitExists(servingUnitCode)) {
            throw InvalidServingUnitException()
        }

        val name = request.name.trim()
        return NormalizedCustomFoodRequest(
            name = name,
            servingQuantity = request.servingQuantity.setScale(4, RoundingMode.HALF_UP),
            servingUnit = servingUnitCode,
            portions = normalizePortions(request.portions, servingUnitCode),
            calories = request.calories.setScale(2, RoundingMode.HALF_UP),
            protein = request.protein.setScale(2, RoundingMode.HALF_UP),
            carbs = request.carbs.setScale(2, RoundingMode.HALF_UP),
            fat = request.fat.setScale(2, RoundingMode.HALF_UP),
            fiber = request.fiber.setScale(2, RoundingMode.HALF_UP),
            sugar = request.sugar.setScale(2, RoundingMode.HALF_UP),
            sodium = request.sodium.setScale(2, RoundingMode.HALF_UP),
            normalizedName = foodSearchNormalizer.normalizeFoodName(name),
            locale = foodSearchNormalizer.normalizeSearchQuery(name).locale,
        )
    }

    private fun normalizePortions(
        portions: List<CustomFoodPortionRequest>,
        servingUnitCode: String,
    ): List<CustomFoodPortionRequest> {
        if (portions.isEmpty()) return emptyList()
        if (servingUnitCode != GRAM_UNIT_CODE) {
            throw InvalidCustomFoodPortionException("Portions are only supported for gram-based foods.")
        }

        val normalized = portions.map { portion ->
            val portionName = portion.name.trim()
            if (portionName.isEmpty()) {
                throw InvalidCustomFoodPortionException("Portion name must not be blank.")
            }
            CustomFoodPortionRequest(
                name = portionName,
                gramWeight = portion.gramWeight.setScale(4, RoundingMode.HALF_UP),
            )
        }

        val duplicated = normalized.groupingBy { it.name }.eachCount().any { it.value > 1 }
        if (duplicated) {
            throw InvalidCustomFoodPortionException("Portion names must be unique.")
        }

        return normalized
    }

    private fun enforceCustomFoodLimit(ownerUserId: UUID) {
        if (entitlementGateService.hasFeatureAccess(ownerUserId, HIGHER_LIMITS_FEATURE)) {
            return
        }

        if (customFoodRepository.countActiveOwnerCustomFoods(ownerUserId) >= planLimitProperties.freeCustomFoods) {
            throw PlanLimitReachedException(
                featureKey = HIGHER_LIMITS_FEATURE,
                limitName = "custom_foods",
                limitValue = planLimitProperties.freeCustomFoods,
            )
        }
    }

    private companion object {
        private const val HIGHER_LIMITS_FEATURE = "higher_limits"
        private const val GRAM_UNIT_CODE = "GRAM"
    }
}

private data class EditableCustomFoodRequest(
    val name: String,
    val servingQuantity: BigDecimal,
    val servingUnit: String,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val portions: List<CustomFoodPortionRequest>,
)

private data class NormalizedCustomFoodRequest(
    val name: String,
    val servingQuantity: BigDecimal,
    val servingUnit: String,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val portions: List<CustomFoodPortionRequest>,
    val normalizedName: String,
    val locale: String,
)

private fun CreateCustomFoodRequest.toEditableCustomFoodRequest(): EditableCustomFoodRequest {
    return EditableCustomFoodRequest(
        name = name,
        servingQuantity = servingQuantity,
        servingUnit = servingUnit,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
        sugar = sugar,
        sodium = sodium,
        portions = portions,
    )
}

private fun UpdateCustomFoodRequest.toEditableCustomFoodRequest(): EditableCustomFoodRequest {
    return EditableCustomFoodRequest(
        name = name,
        servingQuantity = servingQuantity,
        servingUnit = servingUnit,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
        sugar = sugar,
        sodium = sodium,
        portions = portions,
    )
}

private fun NormalizedCustomFoodRequest.toCreateRequest(): CreateCustomFoodRequest {
    return CreateCustomFoodRequest(
        name = name,
        servingQuantity = servingQuantity,
        servingUnit = servingUnit,
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        fiber = fiber,
        sugar = sugar,
        sodium = sodium,
        portions = portions,
    )
}
