package com.gyro.api.food.web.dto

import java.math.BigDecimal

data class FoodDetailResponse(
    val id: String,
    val type: FoodSearchType,
    val name: String,
    val displayName: String,
    val locale: String,
    val servingQuantity: BigDecimal,
    val servingUnit: ServingUnitSummaryResponse,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
    val portions: List<FoodServingPortionResponse>,
    val favorite: Boolean,
    val recent: Boolean,
    val source: String,
    val dataQuality: String,
)
