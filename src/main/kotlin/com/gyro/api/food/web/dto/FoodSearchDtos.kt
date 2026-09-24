package com.gyro.api.food.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class FoodSearchRequest(
    @field:Size(max = 120)
    val query: String? = null,

    val type: FoodSearchType? = null,

    val favorite: Boolean? = null,

    val recent: Boolean? = null,

    @field:Size(max = 10)
    val locale: String? = null,

    @field:Min(0)
    val page: Int = 0,

    @field:Min(1)
    @field:Max(50)
    val size: Int = 20,
)

enum class FoodSearchType {
    SYSTEM,
    CUSTOM,
}

data class FoodSearchResponse(
    val items: List<FoodSearchItemResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

data class FoodSearchItemResponse(
    val id: String,
    val type: FoodSearchType,
    val name: String,
    val displayName: String,
    val locale: String?,
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
    val favorite: Boolean,
    val recent: Boolean,
    val source: String,
    val dataQuality: String,
)

data class ServingUnitSummaryResponse(
    val id: String,
    val code: String,
    val label: String,
)

data class FoodServingPortionResponse(
    val amount: BigDecimal,
    val unitName: String?,
    val unitAbbreviation: String?,
    val modifier: String?,
    val gramWeight: BigDecimal?,
    val displayText: String,
    val servingUnitId: String? = null,
    val servingUnitCode: String? = null,
)
