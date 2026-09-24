package com.gyro.api.food.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class MealListResponse(
    val items: List<MealSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

data class MealSummaryResponse(
    val id: String,
    val name: String,
    val itemCount: Int,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val servingDefinition: ServingDefinitionResponse? = null,
)

data class ServingDefinitionResponse(
    val totalBatchWeight: BigDecimal,
    val servingWeight: BigDecimal,
    val servingsPerBatch: BigDecimal,
    val perServing: ServingNutritionResponse,
)

data class ServingNutritionResponse(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class CreateMealRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,

    @field:NotEmpty
    @field:Size(max = 50)
    val items: List<@Valid CreateMealItemRequest>,

    @field:DecimalMin(value = "0.001")
    @field:Digits(integer = 8, fraction = 4)
    val totalBatchWeight: BigDecimal? = null,

    @field:DecimalMin(value = "0.001")
    @field:Digits(integer = 8, fraction = 4)
    val servingWeight: BigDecimal? = null,
)

data class UpdateMealRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,

    @field:NotEmpty
    @field:Size(max = 50)
    val items: List<@Valid CreateMealItemRequest>,

    @field:DecimalMin(value = "0.001")
    @field:Digits(integer = 8, fraction = 4)
    val totalBatchWeight: BigDecimal? = null,

    @field:DecimalMin(value = "0.001")
    @field:Digits(integer = 8, fraction = 4)
    val servingWeight: BigDecimal? = null,
)

data class CreateMealItemRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val foodId: String,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val quantity: BigDecimal,

    @field:NotBlank
    @field:Size(max = 40)
    val servingUnit: String,
)

data class MealDetailResponse(
    val id: String,
    val name: String,
    val items: List<MealItemResponse>,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val servingDefinition: ServingDefinitionResponse? = null,
)

data class MealArchiveResponse(
    val mealId: String,
    val archived: Boolean,
)

data class MealItemResponse(
    val id: String,
    val foodId: String,
    val foodName: String,
    val quantity: BigDecimal,
    val servingUnit: ServingUnitSummaryResponse,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)
