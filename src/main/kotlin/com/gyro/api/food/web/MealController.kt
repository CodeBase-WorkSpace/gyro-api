package com.gyro.api.food.web

import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.common.observability.StageLog
import com.gyro.api.food.application.MealService
import com.gyro.api.food.web.dto.CreateMealRequest
import com.gyro.api.food.web.dto.MealArchiveResponse
import com.gyro.api.food.web.dto.MealDetailResponse
import com.gyro.api.food.web.dto.MealListResponse
import com.gyro.api.food.web.dto.UpdateMealRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/meals")
class MealController(
    private val mealService: MealService,
    private val idempotencyService: IdempotencyService,
) {
    @GetMapping("/{mealId}")
    fun getMeal(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        mealId: String,
    ): MealDetailResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_MEAL_LOG_EVENT,
            stage = "preview",
            fields = mapOf("mealIdPresent" to mealId.isNotBlank()),
        ) {
            mealService.get(
                ownerUserId = UUID.fromString(userId),
                mealId = mealId,
            )
        }
    }

    @GetMapping
    fun listMeals(
        @AuthenticationPrincipal userId: String,
        @RequestParam(required = false)
        @Size(max = 120)
        query: String?,
        @RequestParam(defaultValue = "0")
        @Min(0)
        page: Int,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): MealListResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_MEAL_LOG_EVENT,
            stage = "search",
            fields = mapOf(
                "queryPresent" to !query.isNullOrBlank(),
                "page" to page,
                "size" to size,
            ),
        ) {
            mealService.list(
                ownerUserId = UUID.fromString(userId),
                query = query,
                page = page,
                size = size,
            )
        }
    }

    @PostMapping
    fun createMeal(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: CreateMealRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<MealDetailResponse> {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_MEAL_LOG_EVENT,
            stage = "persistence_create",
            fields = mealMutationFields(idempotencyKey) + ("itemCount" to request.items.size),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "meals:create:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = MealCreateIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/meals",
                    body = request,
                ),
                responseType = MealDetailResponse::class.java,
                responseStatus = HttpStatus.CREATED.value(),
            ) {
                mealService.create(
                    ownerUserId = ownerUserId,
                    request = request,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PatchMapping("/{mealId}")
    fun updateMeal(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        mealId: String,
        @Valid @RequestBody request: UpdateMealRequest,
    ): MealDetailResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_MEAL_LOG_EVENT,
            stage = "persistence_update",
            fields = mapOf(
                "mealIdPresent" to mealId.isNotBlank(),
                "itemCount" to request.items.size,
            ),
        ) {
            mealService.update(
                ownerUserId = UUID.fromString(userId),
                mealId = mealId,
                request = request,
            )
        }
    }

    @PostMapping("/{mealId}/archive")
    fun archiveMeal(
        @AuthenticationPrincipal userId: String,
        @PathVariable
        @Size(max = 80)
        mealId: String,
    ): MealArchiveResponse {
        return StageLog.around(
            logger = logger,
            event = CUSTOM_MEAL_LOG_EVENT,
            stage = "archive",
            fields = mapOf("mealIdPresent" to mealId.isNotBlank()),
        ) {
            mealService.archive(
                ownerUserId = UUID.fromString(userId),
                mealId = mealId,
            )
        }
    }

    private fun mealMutationFields(idempotencyKey: String?): Map<String, Any?> {
        return mapOf("idempotencyKeyPresent" to !idempotencyKey.isNullOrBlank())
    }

    companion object {
        private const val CUSTOM_MEAL_LOG_EVENT = "custom_meal"
        private val logger = LoggerFactory.getLogger(MealController::class.java)
    }
}

private data class MealCreateIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val body: CreateMealRequest,
)
