package com.gyro.api.diary.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal

data class DiaryDayResponse(
    val date: String,
    val timezone: String,
    val canWriteDiary: Boolean = true,
    val goal: DiaryGoalSummaryResponse,
    val totals: DiaryNutritionSummaryResponse,
    val remainingCalories: DiaryRemainingCaloriesResponse,
    val macroProgress: DiaryMacroProgressResponse,
    val mealGroups: List<DiaryMealGroupResponse>,
    val warnings: List<DiaryWarningResponse>,
)

data class DiaryGoalSummaryResponse(
    val configured: Boolean,
    val calories: BigDecimal?,
    val protein: BigDecimal?,
    val carbs: BigDecimal?,
    val fat: BigDecimal?,
    /** How the day's target was resolved (e.g. DEGRADED_AVERAGE after a lapse). */
    val targetSource: String? = null,
)

data class DiaryRemainingCaloriesResponse(
    val configured: Boolean,
    val value: BigDecimal?,
)

data class DiaryMacroProgressResponse(
    val configured: Boolean,
    val protein: DiaryNutrientProgressResponse,
    val carbs: DiaryNutrientProgressResponse,
    val fat: DiaryNutrientProgressResponse,
)

data class DiaryNutrientProgressResponse(
    val consumed: BigDecimal,
    val target: BigDecimal?,
    val remaining: BigDecimal?,
    val goalPercent: BigDecimal?,
)

data class DiaryNutritionSummaryResponse(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class DiaryMealGroupResponse(
    val mealType: String,
    val entries: List<DiaryEntryResponse>,
    val totals: DiaryNutritionSummaryResponse,
)

data class DiaryEntryResponse(
    val id: String,
    val mealType: String,
    val sourceType: String,
    val sourceFoodId: String?,
    val sourceMealId: String?,
    val displayName: String,
    val servingQuantity: BigDecimal,
    val servingUnitCode: String,
    val servingUnitName: String,
    val sortOrder: Int,
    val nutrition: DiaryNutritionSummaryResponse,
)

data class DiaryWarningResponse(
    val code: String,
    val message: String,
)

data class DiaryEntryRequest(
    @field:NotBlank
    @field:Pattern(regexp = "BREAKFAST|LUNCH|DINNER|SNACK|CUSTOM")
    val mealType: String,

    @field:NotBlank
    @field:Pattern(regexp = "FOOD|MEAL|MANUAL")
    val sourceType: String,

    @field:Size(max = 80)
    val sourceFoodId: String? = null,

    @field:Size(max = 80)
    val sourceMealId: String? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val servingQuantity: BigDecimal,

    @field:Size(max = 40)
    val servingUnit: String? = null,

    @field:Size(max = 500)
    val displayName: String? = null,

    @field:Valid
    val manualNutrition: ManualDiaryNutritionRequest? = null,
)

data class BatchDiaryEntryRequest(
    @field:NotBlank
    @field:Pattern(regexp = "BREAKFAST|LUNCH|DINNER|SNACK")
    val mealType: String,

    @field:NotEmpty
    @field:Size(max = 100)
    @field:Valid
    val entries: List<BatchDiaryEntryItemRequest>,

    @field:Valid
    val quickPlate: QuickPlateRequest? = null,
)

data class QuickPlateRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,
)

data class BatchDiaryEntryItemRequest(
    @field:NotBlank
    @field:Pattern(regexp = "FOOD")
    val sourceType: String,

    @field:NotBlank
    @field:Size(max = 80)
    val sourceId: String,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val quantity: BigDecimal,

    @field:NotBlank
    @field:Size(max = 80)
    val servingUnitId: String,
)

data class ManualDiaryNutritionRequest(
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

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val fiber: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val sugar: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0")
    val sodium: BigDecimal,
)
