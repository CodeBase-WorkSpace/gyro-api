package com.gyro.api.food.web.admin

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.food.application.admin.AdminFoodCatalogService
import com.gyro.api.food.web.dto.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.util.*

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/admin/catalog")
@PreAuthorize("hasRole('ADMIN')")
class AdminFoodCatalogController(
    private val adminFoodCatalogService: AdminFoodCatalogService,
    private val accountAuditService: AccountAuditService,
) {
    @GetMapping("/foods")
    fun listFoods(
        @AuthenticationPrincipal adminId: String,
        @RequestParam(required = false) @Size(max = 120) query: String?,
        @RequestParam(required = false) @Size(max = 50) source: String?,
        @RequestParam(required = false) @Size(max = 50) type: String?,
        @RequestParam(required = false) @Size(max = 50) curationStatus: String?,
        @RequestParam(required = false) archived: Boolean?,
        @RequestParam(required = false) searchable: Boolean?,
        @RequestParam(required = false) @Size(max = 20) ownership: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) size: Int,
    ): PageResponse<AdminFoodSummaryResponse> {
        val result = adminFoodCatalogService.listFoods(
            query = query,
            source = source,
            type = type,
            curationStatus = curationStatus,
            archived = archived,
            searchable = searchable,
            ownership = ownership,
            page = page,
            size = size,
        )
        audit(
            adminId = adminId,
            eventType = AccountAuditEventType.ADMIN_CATALOG_READ,
            metadata = mapOf(
                "scope" to "food_list",
                "queryProvided" to !query.isNullOrBlank(),
                "ownership" to (ownership?.trim()?.uppercase() ?: "CATALOG"),
                "resultCount" to result.items.size,
            ),
        )
        return result
    }

    @GetMapping("/foods/{foodId}")
    fun getFood(
        @AuthenticationPrincipal adminId: String,
        @PathVariable @Size(max = 80) foodId: String,
    ): AdminFoodDetailResponse {
        val detail = adminFoodCatalogService.getFood(foodId)
        audit(
            adminId = adminId,
            eventType = AccountAuditEventType.ADMIN_CATALOG_READ,
            metadata = mapOf("scope" to "food_detail", "foodId" to detail.id),
        )
        return detail
    }

    @GetMapping("/foods/duplicates")
    fun duplicateSuggestions(
        @AuthenticationPrincipal adminId: String,
        @RequestParam @NotBlank @Size(max = 500) name: String,
        @RequestParam(required = false) @Size(max = 80) excludeFoodId: String?,
    ): List<AdminDuplicateSuggestionResponse> {
        return adminFoodCatalogService.duplicateSuggestions(name = name, excludeFoodId = excludeFoodId)
    }

    @PostMapping("/foods")
    fun createFood(
        @AuthenticationPrincipal adminId: String,
        @Valid @RequestBody request: AdminCreateFoodRequest,
    ): ResponseEntity<AdminFoodMutationResponse> {
        val result = adminFoodCatalogService.createFood(UUID.fromString(adminId), request)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PutMapping("/foods/{foodId}")
    fun updateFood(
        @AuthenticationPrincipal adminId: String,
        @PathVariable @Size(max = 80) foodId: String,
        @Valid @RequestBody request: AdminUpdateFoodRequest,
    ): AdminFoodMutationResponse {
        val result = adminFoodCatalogService.updateFood(UUID.fromString(adminId), foodId, request)
        return result
    }

    @PostMapping("/foods/{foodId}/archive")
    fun archiveFood(
        @AuthenticationPrincipal adminId: String,
        @PathVariable @Size(max = 80) foodId: String,
    ): AdminFoodArchiveResponse {
        val result = adminFoodCatalogService.setArchived(UUID.fromString(adminId), foodId, archived = true)
        return result
    }

    @PostMapping("/foods/{foodId}/restore")
    fun restoreFood(
        @AuthenticationPrincipal adminId: String,
        @PathVariable @Size(max = 80) foodId: String,
    ): AdminFoodArchiveResponse {
        val result = adminFoodCatalogService.setArchived(UUID.fromString(adminId), foodId, archived = false)
        return result
    }

    @GetMapping("/serving-units")
    fun listServingUnits(): List<AdminServingUnitResponse> {
        return adminFoodCatalogService.listServingUnits()
    }

    @GetMapping("/categories")
    fun listCategories(): List<AdminFoodCategoryResponse> {
        return adminFoodCatalogService.listCategories()
    }

    @PostMapping("/categories")
    fun createCategory(
        @AuthenticationPrincipal adminId: String,
        @Valid @RequestBody request: AdminCreateCategoryRequest,
    ): ResponseEntity<AdminFoodCategoryResponse> {
        val result = adminFoodCatalogService.createCategory(UUID.fromString(adminId), request)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    private fun audit(adminId: String, eventType: AccountAuditEventType, metadata: Map<String, Any?>) {
        val actorId = UUID.fromString(adminId)
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = actorId,
            eventType = eventType,
            metadata = metadata,
        )
    }
}
