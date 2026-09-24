package com.gyro.api.food.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class CustomFoodPortionRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val gramWeight: BigDecimal,
)

data class CreateCustomFoodRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val servingQuantity: BigDecimal,

    @field:NotBlank
    @field:Size(max = 40)
    val servingUnit: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val calories: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val protein: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val carbs: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val fat: BigDecimal,

    @field:DecimalMin(value = "0.0")
    val fiber: BigDecimal = BigDecimal.ZERO,

    @field:DecimalMin(value = "0.0")
    val sugar: BigDecimal = BigDecimal.ZERO,

    @field:DecimalMin(value = "0.0")
    val sodium: BigDecimal = BigDecimal.ZERO,

    @field:Valid
    @field:Size(max = 10)
    val portions: List<CustomFoodPortionRequest> = emptyList(),
)

data class UpdateCustomFoodRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val servingQuantity: BigDecimal,

    @field:NotBlank
    @field:Size(max = 40)
    val servingUnit: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val calories: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val protein: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val carbs: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val fat: BigDecimal,

    @field:DecimalMin(value = "0.0")
    val fiber: BigDecimal = BigDecimal.ZERO,

    @field:DecimalMin(value = "0.0")
    val sugar: BigDecimal = BigDecimal.ZERO,

    @field:DecimalMin(value = "0.0")
    val sodium: BigDecimal = BigDecimal.ZERO,

    @field:Valid
    @field:Size(max = 10)
    val portions: List<CustomFoodPortionRequest> = emptyList(),
)

data class CustomFoodResponse(
    val id: String,
    val name: String,
    val type: FoodSearchType,
    val servingQuantity: BigDecimal,
    val servingUnit: ServingUnitSummaryResponse,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val portions: List<FoodServingPortionResponse> = emptyList(),
)
