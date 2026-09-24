package com.gyro.api.diary.web

import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.common.observability.StageLog
import com.gyro.api.diary.application.DiaryService
import com.gyro.api.diary.web.dto.DiaryDayResponse
import com.gyro.api.diary.web.dto.DiaryEntryRequest
import com.gyro.api.diary.web.dto.BatchDiaryEntryRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.*

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/diary")
class DiaryController(
    private val diaryService: DiaryService,
    private val idempotencyService: IdempotencyService,
) {
    @GetMapping("/{date}")
    fun getDiaryDay(
        @AuthenticationPrincipal userId: String,
        @PathVariable date: LocalDate,
    ): DiaryDayResponse {
        return diaryService.getDay(
            userId = UUID.fromString(userId),
            date = date,
        )
    }

    @PostMapping("/{date}/entries")
    fun createEntry(
        @AuthenticationPrincipal userId: String,
        @PathVariable date: LocalDate,
        @Valid @RequestBody request: DiaryEntryRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "create",
            fields = diaryMutationFields(idempotencyKey),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:entries:create:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryCreateEntryIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/diary/{date}/entries",
                    date = date.toString(),
                    body = request,
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.createEntry(
                    userId = ownerUserId,
                    date = date,
                    request = request,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PostMapping("/{date}/entries/batch")
    fun createEntriesBatch(
        @AuthenticationPrincipal userId: String,
        @PathVariable date: LocalDate,
        @Valid @RequestBody request: BatchDiaryEntryRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "batch_create",
            fields = diaryMutationFields(idempotencyKey) + ("entryCount" to request.entries.size),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:entries:batch:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryCreateBatchIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/diary/{date}/entries/batch",
                    date = date.toString(),
                    body = request,
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.createEntriesBatch(ownerUserId, date, request)
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PatchMapping("/{date}/entries/{entryId}")
    fun updateEntry(
        @AuthenticationPrincipal userId: String,
        @PathVariable date: LocalDate,
        @PathVariable @Size(max = 80) entryId: String,
        @Valid @RequestBody request: DiaryEntryRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "edit",
            fields = diaryMutationFields(idempotencyKey) + ("entryIdPresent" to entryId.isNotBlank()),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:entries:update:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryUpdateEntryIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "PATCH /api/v1/diary/{date}/entries/{entryId}",
                    date = date.toString(),
                    entryId = entryId.trim(),
                    body = request,
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.updateEntry(
                    userId = ownerUserId,
                    date = date,
                    entryId = entryId,
                    request = request,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @DeleteMapping("/{date}/entries/{entryId}")
    fun deleteEntry(
        @AuthenticationPrincipal userId: String,
        @PathVariable date: LocalDate,
        @PathVariable @Size(max = 80) entryId: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "delete",
            fields = diaryMutationFields(idempotencyKey) + ("entryIdPresent" to entryId.isNotBlank()),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:entries:delete:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryDeleteEntryIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "DELETE /api/v1/diary/{date}/entries/{entryId}",
                    date = date.toString(),
                    entryId = entryId.trim(),
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.deleteEntry(
                    userId = ownerUserId,
                    date = date,
                    entryId = entryId,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PostMapping("/{targetDate}/copy-from/{sourceDate}")
    fun copyFromDate(
        @PathVariable targetDate: LocalDate,
        @PathVariable sourceDate: LocalDate,
        @AuthenticationPrincipal userId: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "copy",
            fields = diaryMutationFields(idempotencyKey),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:copy:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryCopyFromDateIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/diary/{targetDate}/copy-from/{sourceDate}",
                    targetDate = targetDate.toString(),
                    sourceDate = sourceDate.toString(),
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.copyFromDate(
                    userId = ownerUserId,
                    targetDate = targetDate,
                    sourceDate = sourceDate,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    @PostMapping("/{date}/entries/{entryId}/repeat")
    fun repeatEntry(
        @PathVariable date: LocalDate,
        @PathVariable @Size(max = 80) entryId: String,
        @AuthenticationPrincipal userId: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DiaryDayResponse> {
        return StageLog.around(
            logger = logger,
            event = DIARY_MUTATION_LOG_EVENT,
            stage = "repeat",
            fields = diaryMutationFields(idempotencyKey) + ("entryIdPresent" to entryId.isNotBlank()),
        ) {
            val ownerUserId = UUID.fromString(userId)
            val result = idempotencyService.execute(
                scope = "diary:repeat:$ownerUserId",
                ownerUserId = ownerUserId,
                idempotencyKey = idempotencyKey,
                request = DiaryRepeatEntryIdempotencyRequest(
                    ownerUserId = ownerUserId.toString(),
                    endpoint = "POST /api/v1/diary/{date}/entries/{entryId}/repeat",
                    targetDate = date.toString(),
                    entryId = entryId.trim(),
                ),
                responseType = DiaryDayResponse::class.java,
                responseStatus = HttpStatus.OK.value(),
            ) {
                diaryService.repeatEntry(
                    userId = ownerUserId,
                    targetDate = date,
                    entryId = entryId,
                )
            }

            ResponseEntity.status(result.responseStatus).body(result.body)
        }
    }

    private fun diaryMutationFields(idempotencyKey: String?): Map<String, Any?> {
        return mapOf(
            "idempotencyKeyPresent" to !idempotencyKey.isNullOrBlank(),
            "datePresent" to true,
        )
    }

    companion object {
        private const val DIARY_MUTATION_LOG_EVENT = "diary_mutation"
        private val logger = LoggerFactory.getLogger(DiaryController::class.java)
    }
}

private data class DiaryCreateEntryIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val date: String,
    val body: DiaryEntryRequest,
)

private data class DiaryCreateBatchIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val date: String,
    val body: BatchDiaryEntryRequest,
)

private data class DiaryUpdateEntryIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val date: String,
    val entryId: String,
    val body: DiaryEntryRequest,
)

private data class DiaryDeleteEntryIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val date: String,
    val entryId: String,
)

private data class DiaryCopyFromDateIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val targetDate: String,
    val sourceDate: String,
)

private data class DiaryRepeatEntryIdempotencyRequest(
    val ownerUserId: String,
    val endpoint: String,
    val targetDate: String,
    val entryId: String,
)
