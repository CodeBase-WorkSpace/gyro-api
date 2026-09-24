package com.gyro.api.weight.web.dto

import com.gyro.api.weight.application.SaveWeightEntriesBatchCommand
import com.gyro.api.weight.application.SaveWeightEntryBatchItemCommand
import com.gyro.api.weight.application.SaveWeightEntryCommand
import com.gyro.api.weight.application.WeightEntryBatchDiagnostic
import com.gyro.api.weight.application.WeightEntryBatchSaveResult
import com.gyro.api.weight.application.WeightEntryReadModel
import com.gyro.api.weight.domain.WeightEntrySource
import com.gyro.api.weight.domain.WeightUnit
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SaveWeightEntryRequest(
    @field:NotNull
    val recordedDate: LocalDate,

    val recordedAt: Instant? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.001")
    val weight: BigDecimal,

    @field:NotNull
    val unit: WeightUnit,

    val source: WeightEntrySource = WeightEntrySource.MANUAL,

    val notes: String? = null,
)

data class SaveWeightEntriesBatchRequest(
    @field:NotEmpty
    @field:Size(max = 100)
    @field:Valid
    val entries: List<SaveWeightEntryBatchItemRequest>,
)

data class SaveWeightEntryBatchItemRequest(
    @field:Size(max = 120)
    val clientEntryId: String? = null,

    val recordedDate: LocalDate? = null,

    val recordedAt: Instant? = null,

    val weight: BigDecimal? = null,

    val unit: WeightUnit? = null,

    val source: WeightEntrySource? = null,

    @field:Size(max = 2000)
    val notes: String? = null,
)

data class WeightEntryResponse(
    val id: UUID,
    val recordedDate: LocalDate,
    val recordedAt: Instant,
    val weightKg: BigDecimal,
    val displayWeight: BigDecimal,
    val displayUnit: WeightUnit,
    val source: WeightEntrySource,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WeightEntriesBatchResponse(
    val accepted: List<AcceptedWeightEntryResponse>,
    val diagnostics: List<WeightEntryBatchDiagnosticResponse>,
)

data class AcceptedWeightEntryResponse(
    val clientEntryId: String?,
    val entry: WeightEntryResponse,
)

data class WeightEntryBatchDiagnosticResponse(
    val index: Int,
    val clientEntryId: String?,
    val field: String,
    val code: String,
    val message: String,
)

fun SaveWeightEntryRequest.toCommand(): SaveWeightEntryCommand {
    return SaveWeightEntryCommand(
        recordedDate = recordedDate,
        recordedAt = recordedAt,
        weight = weight,
        unit = unit,
        source = source,
        notes = notes,
    )
}

fun SaveWeightEntriesBatchRequest.toCommand(): SaveWeightEntriesBatchCommand {
    return SaveWeightEntriesBatchCommand(
        entries = entries.map { it.toCommand() },
    )
}

private fun SaveWeightEntryBatchItemRequest.toCommand(): SaveWeightEntryBatchItemCommand {
    return SaveWeightEntryBatchItemCommand(
        clientEntryId = clientEntryId,
        recordedDate = recordedDate,
        recordedAt = recordedAt,
        weight = weight,
        unit = unit,
        source = source,
        notes = notes,
    )
}

fun WeightEntryReadModel.toResponse(): WeightEntryResponse {
    return WeightEntryResponse(
        id = id,
        recordedDate = recordedDate,
        recordedAt = recordedAt,
        weightKg = weightKg,
        displayWeight = displayWeight,
        displayUnit = displayUnit,
        source = source,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun WeightEntryBatchSaveResult.toResponse(): WeightEntriesBatchResponse {
    return WeightEntriesBatchResponse(
        accepted = accepted.map {
            AcceptedWeightEntryResponse(
                clientEntryId = it.clientEntryId,
                entry = it.entry.toResponse(),
            )
        },
        diagnostics = diagnostics.map { it.toResponse() },
    )
}

private fun WeightEntryBatchDiagnostic.toResponse(): WeightEntryBatchDiagnosticResponse {
    return WeightEntryBatchDiagnosticResponse(
        index = index,
        clientEntryId = clientEntryId,
        field = field,
        code = code,
        message = message,
    )
}
