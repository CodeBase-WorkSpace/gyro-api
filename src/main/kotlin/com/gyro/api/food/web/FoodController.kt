package com.gyro.api.food.web

import com.gyro.api.food.application.CustomFoodService
import com.gyro.api.common.observability.StageLog
import com.gyro.api.food.application.FoodDetailService
import com.gyro.api.food.application.FoodFavoriteService
import com.gyro.api.food.application.FoodSearchService
import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.food.web.dto.CustomFoodArchiveResponse
import com.gyro.api.food.web.dto.CreateCustomFoodRequest
import com.gyro.api.food.web.dto.CustomFoodResponse
import com.gyro.api.food.web.dto.FoodDetailResponse
import com.gyro.api.food.web.dto.FoodFavoriteResponse
import com.gyro.api.food.web.dto.FoodSearchResponse
import com.gyro.api.food.web.dto.FoodSearchType
import com.gyro.api.food.web.dto.UpdateCustomFoodRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/foods")
class FoodController(
    private val foodSearchService: FoodSearchService,
    private val foodDetailService: FoodDetailService,
    private val customFoodService: CustomFoodService,
    private val foodFavoriteService: FoodFavoriteService,
    private val idempotencyService: IdempotencyService,
) {
    @GetMapping
    fun searchFoods(
        @AuthenticationPrincipal userId: String,
        @RequestParam(required = false)
        @Size(max = 120)
        query: String?,
        @RequestParam(required = false)
        @Size(max = 10)
        locale: String?,
        @RequestParam(required = false)
        type: FoodSearchType?,
        @RequestParam(required = false)
        favorite: Boolean?,
        @RequestParam(required = false)
        recent: Boolean?,
        @RequestParam(defaultValue = "0")
        @Min(0)
        page: Int,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): FoodSearchResponse {
        return StageLog.around(
            logger = logger,
            event = FOOD_SEARCH_LOG_EVENT,
            stage = "search",
            fields = mapOf(
                "queryPresent" to !query.isNullOrBlank(),
                "localePresent" to !locale.isNullOrBlank(),
                "typePresent" to (type != null),
                "favoriteFilterPresent" to (favorite != null),
                "recentFilterPresent" to (recent != null),
                "page" to page,
                "size" to size,
            ),
        ) {
            foodSearchService.search(
                currentUserId = UUID.fromString(userId),
                query = query,
                locale = locale,
                type = type,
                favorite = favorite,
                recent = recent,
                page = page,
                size = size,
            )
        }
    }

    @GetMapping("/{foodId}")
    fun getFoodDetail(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        foodId: String,
        @RequestParam(required = false)
        @Size(max = 10)
        locale: String?,
    ): FoodDetailResponse {
        return StageLog.around(
            logger = logger,
            event = FOOD_SEARCH_LOG_EVENT,
            stage = "preview",
            fields = mapOf(
                "foodIdPresent" to foodId.isNotBlank(),
                "localePresent" to !locale.isNullOrBlank(),
            ),
        ) {
            foodDetailService.getFoodDetail(
                currentUserId = UUID.fromString(userId),
                foodId = foodId,
                locale = locale,
            )
        }
    }

    @PostMapping("/custom")
    fun createCustomFood(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: CreateCustomFoodRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<CustomFoodResponse> {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_FOOD_LOG_EVENT,
            stage = "persistence_create",
            fields = customFoodFields(idempotencyKey),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "foods:custom:create:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = CustomFoodCreateIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/foods/custom",
                    body = request,
                ),
                responseType = CustomFoodResponse::class.java,
                responseStatus = HttpStatus.CREATED.value(),
            ) {
                customFoodService.create(
                    ownerUserId = ownerUserId,
                    request = request,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PatchMapping("/custom/{foodId}")
    fun updateCustomFood(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        foodId: String,
        @Valid @RequestBody request: UpdateCustomFoodRequest,
    ): CustomFoodResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_FOOD_LOG_EVENT,
            stage = "persistence_update",
            fields = mapOf("foodIdPresent" to foodId.isNotBlank()),
        ) {
            customFoodService.update(
                ownerUserId = UUID.fromString(userId),
                foodId = foodId,
                request = request,
            )
        }
    }

    @PostMapping("/custom/{foodId}/archive")
    fun archiveCustomFood(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        foodId: String,
    ): CustomFoodArchiveResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_FOOD_LOG_EVENT,
            stage = "archive",
            fields = mapOf("foodIdPresent" to foodId.isNotBlank()),
        ) {
            customFoodService.archive(
                ownerUserId = UUID.fromString(userId),
                foodId = foodId,
            )
        }
    }

    @PostMapping("/{foodId}/favorite")
    fun favoriteFood(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        foodId: String,
    ): FoodFavoriteResponse {
        return foodFavoriteService.favorite(
            currentUserId = UUID.fromString(userId),
            foodId = foodId,
        )
    }

    @DeleteMapping("/{foodId}/favorite")
    fun unfavoriteFood(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        foodId: String,
    ): FoodFavoriteResponse {
        return foodFavoriteService.unfavorite(
            currentUserId = UUID.fromString(userId),
            foodId = foodId,
        )
    }

    private fun customFoodFields(idempotencyKey: String?): Map<String, Any?> {
        return mapOf("idempotencyKeyPresent" to !idempotencyKey.isNullOrBlank())
    }

    companion object {
        private const val FOOD_SEARCH_LOG_EVENT = "food_search"
        private const val CUSTOM_FOOD_LOG_EVENT = "custom_food"
        private val logger = LoggerFactory.getLogger(FoodController::class.java)
    }
}

private data class CustomFoodCreateIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val body: CreateCustomFoodRequest,
)
