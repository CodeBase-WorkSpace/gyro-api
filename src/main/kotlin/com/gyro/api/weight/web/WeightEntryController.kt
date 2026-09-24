package com.gyro.api.weight.web

import com.gyro.api.common.idempotency.service.IdempotencyResult
import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.common.observability.HealthTrackingObservability
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.weight.application.WeightEntryService
import com.gyro.api.weight.web.dto.SaveWeightEntriesBatchRequest
import com.gyro.api.weight.web.dto.SaveWeightEntryRequest
import com.gyro.api.weight.web.dto.WeightEntriesBatchResponse
import com.gyro.api.weight.web.dto.WeightEntryResponse
import com.gyro.api.weight.web.dto.toCommand
import com.gyro.api.weight.web.dto.toResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/weight-entries")
class WeightEntryController(
    private val weightEntryService: WeightEntryService,
    private val idempotencyService: IdempotencyService,
    private val healthTrackingObservability: HealthTrackingObservability,
) {
    @GetMapping
    fun listWeightEntries(
        @AuthenticationPrincipal userId: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @RequestParam(defaultValue = "0")
        @Min(0)
        page: Int,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(50)
        size: Int,
    ): PageResponse<WeightEntryResponse> {
        return StageLog.around(
            logger = logger,
            event = WEIGHT_LOG_EVENT,
            stage = "range_query",
            fields = mapOf(
                "rangePresent" to true,
                "page" to page,
                "size" to size,
            ),
        ) {
            val entries = weightEntryService.listEntries(
                userId = UUID.fromString(userId),
                from = from,
                to = to,
                page = page,
                size = size,
            )

            PageResponse(
                items = entries.content.map { it.toResponse() },
                page = page,
                size = size,
                totalItems = entries.totalElements,
                totalPages = entries.totalPages,
            )
        }
    }

    @PostMapping
    fun saveWeightEntry(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: SaveWeightEntryRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<WeightEntryResponse> {
        return StageLog.around(
            logger = logger,
            event = WEIGHT_LOG_EVENT,
            stage = "save",
            fields = weightMutationFields(idempotencyKey),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "weight:entries:save:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = WeightEntrySaveIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/weight-entries",
                    body = request,
                ),
                responseType = WeightEntryResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                val savedEntry = weightEntryService.saveEntry(
                    userId = ownerUserId,
                    command = request.toCommand(),
                )

                healthTrackingObservability.weightEntrySaved(
                    userId = ownerUserId,
                    source = savedEntry.source.name,
                )

                savedEntry.toResponse()
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PostMapping("/batch")
    fun saveWeightEntriesBatch(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: SaveWeightEntriesBatchRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<WeightEntriesBatchResponse> {
        return StageLog.around(
            logger = logger,
            event = WEIGHT_LOG_EVENT,
            stage = "batch_save",
            fields = weightMutationFields(idempotencyKey) + ("entryCount" to request.entries.size),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.executeResult(
                scope = "weight:entries:batch:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = WeightEntriesBatchSaveIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/weight-entries/batch",
                    body = request,
                ),
                responseType = WeightEntriesBatchResponse::class.java,
            ) {
                val savedEntries = weightEntryService.saveEntriesBatch(
                    userId = ownerUserId,
                    command = request.toCommand(),
                )

                if (savedEntries.diagnostics.isEmpty()) {
                    healthTrackingObservability.weightEntriesBatchAccepted(
                        userId = ownerUserId,
                        submittedCount = request.entries.size,
                        acceptedCount = savedEntries.accepted.size,
                    )
                }

                val status = if (savedEntries.diagnostics.isEmpty()) {
                    HttpStatus.OK
                } else {
                    HttpStatus.BAD_REQUEST
                }

                IdempotencyResult(
                    responseStatus = status.value(),
                    body = savedEntries.toResponse(),
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    private fun weightMutationFields(idempotencyKey: String?): Map<String, Any?> {
        return mapOf("idempotencyKeyPresent" to !idempotencyKey.isNullOrBlank())
    }

    companion object {
        private const val WEIGHT_LOG_EVENT = "weight"
        private val logger = LoggerFactory.getLogger(WeightEntryController::class.java)
    }
}

private data class WeightEntrySaveIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val body: SaveWeightEntryRequest,
)

private data class WeightEntriesBatchSaveIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val body: SaveWeightEntriesBatchRequest,
)
