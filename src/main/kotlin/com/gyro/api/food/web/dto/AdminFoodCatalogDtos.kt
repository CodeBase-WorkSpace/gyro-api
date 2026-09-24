package com.gyro.api.food.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AdminFoodLocalizationRequest(
    @field:NotBlank
    @field:Pattern(regexp = "en|fa")
    val locale: String,

    @field:NotBlank
    @field:Size(max = 500)
    val displayName: String,

    @field:Pattern(regexp = "UNREVIEWED|REVIEWED|REJECTED")
    val reviewStatus: String = "REVIEWED",
)

data class AdminFoodAliasRequest(
    @field:NotBlank
    @field:Pattern(regexp = "en|fa")
    val locale: String,

    @field:NotBlank
    @field:Size(max = 500)
    val alias: String,

    @field:Pattern(regexp = "UNREVIEWED|REVIEWED|REJECTED")
    val reviewStatus: String = "REVIEWED",
)

data class AdminFoodNutritionRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val baseQuantity: BigDecimal,

    @field:NotBlank
    @field:Size(max = 50)
    val baseUnitCode: String,

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
)

data class AdminFoodPortionRequest(
    @field:Size(max = 50)
    val servingUnitCode: String? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val amount: BigDecimal,

    @field:DecimalMin(value = "0.001")
    val gramWeight: BigDecimal? = null,

    @field:Size(max = 255)
    val modifier: String? = null,

    @field:Size(max = 500)
    val portionDescription: String? = null,

    @field:Min(0)
    val sortOrder: Int = 0,
)

data class AdminCreateFoodRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val name: String,

    @field:Size(max = 255)
    val brandName: String? = null,

    val categoryId: UUID? = null,

    @field:Pattern(regexp = "REVIEWED|UNREVIEWED|HIDDEN")
    val curationStatus: String = "REVIEWED",

    val isSearchable: Boolean = true,

    @field:Valid
    @field:Size(max = 4)
    val localizations: List<AdminFoodLocalizationRequest> = emptyList(),

    @field:Valid
    @field:Size(max = 40)
    val aliases: List<AdminFoodAliasRequest> = emptyList(),

    @field:NotNull
    @field:Valid
    val nutrition: AdminFoodNutritionRequest,

    @field:Valid
    @field:Size(max = 40)
    val portions: List<AdminFoodPortionRequest> = emptyList(),
)

data class AdminUpdateFoodRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val name: String,

    @field:Size(max = 255)
    val brandName: String? = null,

    val categoryId: UUID? = null,

    @field:Pattern(regexp = "REVIEWED|UNREVIEWED|HIDDEN")
    val curationStatus: String = "REVIEWED",

    val isSearchable: Boolean = true,

    @field:Valid
    @field:Size(max = 4)
    val localizations: List<AdminFoodLocalizationRequest> = emptyList(),

    @field:Valid
    @field:Size(max = 40)
    val aliases: List<AdminFoodAliasRequest> = emptyList(),

    @field:NotNull
    @field:Valid
    val nutrition: AdminFoodNutritionRequest,

    @field:Valid
    @field:Size(max = 40)
    val portions: List<AdminFoodPortionRequest> = emptyList(),

    @field:NotNull
    @field:Min(0)
    val expectedLockVersion: Int,
)

data class AdminCatalogValidationWarning(
    val code: String,
    val message: String,
)

data class AdminFoodLocalizationResponse(
    val locale: String,
    val displayName: String,
    val source: String,
    val reviewStatus: String,
)

data class AdminFoodAliasResponse(
    val locale: String,
    val alias: String,
    val source: String,
    val reviewStatus: String,
)

data class AdminFoodNutritionResponse(
    val baseQuantity: BigDecimal,
    val baseUnitCode: String,
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

data class AdminFoodPortionResponse(
    val id: String,
    val servingUnitCode: String?,
    val amount: BigDecimal,
    val gramWeight: BigDecimal?,
    val modifier: String?,
    val portionDescription: String?,
    val sortOrder: Int,
)

data class AdminFoodSummaryResponse(
    val id: String,
    val name: String,
    val brandName: String?,
    val type: String,
    val source: String,
    val dataQuality: String,
    val curationStatus: String,
    val isSearchable: Boolean,
    val archived: Boolean,
    val categoryName: String?,
    val localeCoverage: List<String>,
    val hasNutrition: Boolean,
    val portionCount: Int,
    val lockVersion: Int,
    val updatedAt: Instant,
    val editable: Boolean,
)

data class AdminFoodDetailResponse(
    val id: String,
    val name: String,
    val brandName: String?,
    val type: String,
    val source: String,
    val dataQuality: String,
    val curationStatus: String,
    val isSearchable: Boolean,
    val archived: Boolean,
    val categoryId: String?,
    val categoryName: String?,
    val localizations: List<AdminFoodLocalizationResponse>,
    val aliases: List<AdminFoodAliasResponse>,
    val nutrition: AdminFoodNutritionResponse?,
    val portions: List<AdminFoodPortionResponse>,
    val lockVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val editable: Boolean,
)

data class AdminFoodMutationResponse(
    val food: AdminFoodDetailResponse,
    val warnings: List<AdminCatalogValidationWarning>,
)

data class AdminFoodArchiveResponse(
    val id: String,
    val archived: Boolean,
)

data class AdminDuplicateSuggestionResponse(
    val id: String,
    val name: String,
    val brandName: String?,
    val source: String,
    val archived: Boolean,
    val matchedOn: String,
)

data class AdminServingUnitResponse(
    val id: String,
    val code: String,
    val unitType: String,
    val gramMultiplier: BigDecimal?,
    val milliliterMultiplier: BigDecimal?,
    val isActive: Boolean,
    val sortOrder: Int,
)

data class AdminFoodCategoryResponse(
    val id: String,
    val name: String,
    val source: String,
)

data class AdminCreateCategoryRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
)
