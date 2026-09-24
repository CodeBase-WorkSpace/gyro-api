package com.gyro.api.weight.application

import com.gyro.api.common.decimal.NutritionDecimal
import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.error.InvalidWeightEntryException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import com.gyro.api.weight.domain.WeightEntry
import com.gyro.api.weight.domain.WeightEntrySource
import com.gyro.api.weight.domain.WeightUnit
import com.gyro.api.weight.domain.toKilograms
import com.gyro.api.weight.infrastructure.WeightEntryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

data class SaveWeightEntryCommand(
    val recordedDate: LocalDate,
    val weight: BigDecimal,
    val unit: WeightUnit,
    val source: WeightEntrySource = WeightEntrySource.MANUAL,
    val notes: String? = null,
    val recordedAt: Instant? = null,
)

data class SaveWeightEntriesBatchCommand(
    val entries: List<SaveWeightEntryBatchItemCommand>,
)

data class SaveWeightEntryBatchItemCommand(
    val clientEntryId: String?,
    val recordedDate: LocalDate?,
    val weight: BigDecimal?,
    val unit: WeightUnit?,
    val source: WeightEntrySource?,
    val notes: String?,
    val recordedAt: Instant?,
)

data class WeightEntryReadModel(
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

data class AcceptedWeightEntryReadModel(
    val clientEntryId: String?,
    val entry: WeightEntryReadModel,
)

data class WeightEntryBatchDiagnostic(
    val index: Int,
    val clientEntryId: String?,
    val field: String,
    val code: String,
    val message: String,
)

data class WeightEntryBatchSaveResult(
    val accepted: List<AcceptedWeightEntryReadModel>,
    val diagnostics: List<WeightEntryBatchDiagnostic>,
)

@Service
class WeightEntryService(
    private val weightEntryRepository: WeightEntryRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val timeProvider: TimeProvider,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
) {

    @Transactional
    fun saveEntry(
        userId: UUID,
        command: SaveWeightEntryCommand,
    ): WeightEntryReadModel {
        val now = timeProvider.now()
        validateEntry(command, userToday(userId))
        val saved = weightEntryRepository.saveAndFlush(
            upsertEntry(
                userId = userId,
                command = command,
                now = now,
                existingEntriesByDate = null,
            )
        ).toReadModel()
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.WEIGHT)
        return saved
    }

    @Transactional
    fun saveEntriesBatch(
        userId: UUID,
        command: SaveWeightEntriesBatchCommand,
    ): WeightEntryBatchSaveResult {
        val today = userToday(userId)
        val diagnostics = validateBatch(today, command)
        if (diagnostics.isNotEmpty()) {
            return WeightEntryBatchSaveResult(
                accepted = emptyList(),
                diagnostics = diagnostics,
            )
        }

        val now = timeProvider.now()
        val existingEntriesByDate = weightEntryRepository.findByUserIdAndRecordedDateIn(
            userId = userId,
            recordedDates = command.entries.map { requireNotNull(it.recordedDate) },
        ).associateBy { it.recordedDate }
        val batchItems = command.entries.map { item ->
            item to upsertEntry(
                userId = userId,
                command = item.toSaveCommand(),
                now = now,
                existingEntriesByDate = existingEntriesByDate,
            )
        }
        val savedEntriesById = weightEntryRepository.saveAllAndFlush(batchItems.map { it.second })
            .associateBy { requireNotNull(it.id) }
        val accepted = batchItems.map { (item, entry) ->
            AcceptedWeightEntryReadModel(
                clientEntryId = item.clientEntryId,
                entry = requireNotNull(savedEntriesById[requireNotNull(entry.id)]).toReadModel(),
            )
        }
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.WEIGHT)

        return WeightEntryBatchSaveResult(
            accepted = accepted,
            diagnostics = emptyList(),
        )
    }

    @Transactional(readOnly = true)
    fun listEntries(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        page: Int,
        size: Int,
    ): Page<WeightEntryReadModel> {
        validateDateRange(from, to)
        return weightEntryRepository.findByUserIdAndRecordedDateBetween(
            userId = userId,
            from = from,
            to = to,
            pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "recordedDate")),
        ).map { it.toReadModel() }
    }

    private fun validateEntry(
        command: SaveWeightEntryCommand,
        today: LocalDate,
    ) {
        if (command.notes != null && command.notes.length > 2000) {
            throw fieldValidation("notes", "SIZE", "notes must be 2000 characters or fewer.")
        }
        if (command.recordedDate.isAfter(today)) {
            throw fieldValidation("recordedDate", "FUTURE_DATE", "recordedDate cannot be in the future.")
        }
        validateCanonicalRange(command.unit.toKilograms(NutritionDecimal.weight(command.weight)))
    }

    private fun validateBatch(
        today: LocalDate,
        command: SaveWeightEntriesBatchCommand,
    ): List<WeightEntryBatchDiagnostic> {
        // Batch imports are all-or-nothing: collect every row-level issue first,
        // then let the caller decide whether it is safe to write anything.
        val diagnostics = mutableListOf<WeightEntryBatchDiagnostic>()
        val seenDates = mutableMapOf<LocalDate, Int>()

        command.entries.forEachIndexed { index, item ->
            val itemDiagnostics = item.validateRequiredFields(index)
            diagnostics += itemDiagnostics

            // The MVP stores one weight entry per user local date, so a batch
            // cannot contain two rows that would upsert the same persisted row.
            val recordedDate = item.recordedDate
            if (recordedDate != null) {
                val previousIndex = seenDates.putIfAbsent(recordedDate, index)
                if (previousIndex != null) {
                    diagnostics += item.toDiagnostic(
                        index = index,
                        field = "recordedDate",
                        code = "DUPLICATE_RECORDED_DATE",
                        message = "recordedDate duplicates entry at index $previousIndex.",
                    )
                }
            }

            // Reuse single-entry business validation only after DTO-level
            // required fields are present, so diagnostics stay row-specific.
            if (item.hasRequiredFields() && itemDiagnostics.isEmpty()) {
                try {
                    validateEntry(
                        command = item.toSaveCommand(),
                        today = today,
                    )
                } catch (ex: FieldValidationException) {
                    val fieldError = ex.fieldErrors.first()
                    diagnostics += item.toDiagnostic(
                        index = index,
                        field = fieldError.field,
                        code = fieldError.code,
                        message = fieldError.errorMessage,
                    )
                } catch (ex: InvalidWeightEntryException) {
                    diagnostics += item.toDiagnostic(
                        index = index,
                        field = ex.toDiagnosticField(),
                        code = "INVALID_WEIGHT_ENTRY",
                        message = ex.message,
                    )
                }
            }
        }

        return diagnostics
    }

    private fun upsertEntry(
        userId: UUID,
        command: SaveWeightEntryCommand,
        now: Instant,
        existingEntriesByDate: Map<LocalDate, WeightEntry>?,
    ): WeightEntry {
        val displayWeight = NutritionDecimal.weight(command.weight)
        val weightKg = command.unit.toKilograms(displayWeight)
        val normalizedNotes = command.notes?.trim()?.takeIf { it.isNotBlank() }
        val recordedAt = command.recordedAt ?: now

        val entry = if (existingEntriesByDate == null) {
            weightEntryRepository.findByUserIdAndRecordedDate(
                userId = userId,
                recordedDate = command.recordedDate,
            )
        } else {
            existingEntriesByDate[command.recordedDate]
        } ?: WeightEntry(
            userId = userId,
            recordedDate = command.recordedDate,
            recordedAt = recordedAt,
            weightKg = weightKg,
            displayWeight = displayWeight,
            displayUnit = command.unit,
            source = command.source,
            createdAt = now,
            updatedAt = now,
        )

        entry.recordedAt = recordedAt
        entry.weightKg = weightKg
        entry.displayWeight = displayWeight
        entry.displayUnit = command.unit
        entry.source = command.source
        entry.notes = normalizedNotes
        entry.updatedAt = now

        return entry
    }

    private fun validateDateRange(
        from: LocalDate,
        to: LocalDate,
    ) {
        if (to.isBefore(from)) {
            throw InvalidWeightEntryException("to must be on or after from.")
        }
        if (from.plusDays(MAX_LIST_DAYS.toLong()).isBefore(to)) {
            throw InvalidWeightEntryException("Weight entry range cannot exceed $MAX_LIST_DAYS days.")
        }
    }

    private fun validateCanonicalRange(weightKg: BigDecimal) {
        if (weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) {
            throw fieldValidation("weight", "OUT_OF_RANGE", "weight must be between 20 kg and 500 kg.")
        }
    }

    private fun userZoneId(userId: UUID): ZoneId {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        return ZoneId.of(timezone)
    }

    private fun userToday(userId: UUID): LocalDate {
        return timeProvider.today(userZoneId(userId))
    }

    companion object {
        private const val MAX_LIST_DAYS = 366
        private val MIN_WEIGHT_KG = BigDecimal("20.000")
        private val MAX_WEIGHT_KG = BigDecimal("500.000")
    }
}

private fun SaveWeightEntryBatchItemCommand.validateRequiredFields(index: Int): List<WeightEntryBatchDiagnostic> {
    val diagnostics = mutableListOf<WeightEntryBatchDiagnostic>()
    if (recordedDate == null) {
        diagnostics += toDiagnostic(index, "recordedDate", "REQUIRED", "recordedDate is required.")
    }
    if (weight == null) {
        diagnostics += toDiagnostic(index, "weight", "REQUIRED", "weight is required.")
    } else if (weight <= BigDecimal.ZERO) {
        diagnostics += toDiagnostic(index, "weight", "INVALID_WEIGHT_ENTRY", "Weight must be greater than zero.")
    }
    if (unit == null) {
        diagnostics += toDiagnostic(index, "unit", "REQUIRED", "unit is required.")
    }
    if (notes != null && notes.length > 2000) {
        diagnostics += toDiagnostic(index, "notes", "INVALID_WEIGHT_ENTRY", "notes must be 2000 characters or fewer.")
    }
    return diagnostics
}

private fun SaveWeightEntryBatchItemCommand.hasRequiredFields(): Boolean {
    return recordedDate != null && weight != null && unit != null
}

private fun SaveWeightEntryBatchItemCommand.toSaveCommand(): SaveWeightEntryCommand {
    return SaveWeightEntryCommand(
        recordedDate = requireNotNull(recordedDate),
        weight = requireNotNull(weight),
        unit = requireNotNull(unit),
        source = source ?: WeightEntrySource.MANUAL,
        notes = notes,
        recordedAt = recordedAt,
    )
}

private fun SaveWeightEntryBatchItemCommand.toDiagnostic(
    index: Int,
    field: String,
    code: String,
    message: String,
): WeightEntryBatchDiagnostic {
    return WeightEntryBatchDiagnostic(
        index = index,
        clientEntryId = clientEntryId,
        field = field,
        code = code,
        message = message,
    )
}

private fun fieldValidation(
    field: String,
    code: String,
    message: String,
): FieldValidationException {
    return FieldValidationException(
        message = message,
        fieldErrors = listOf(
            ApiErrorResponse.FieldError(
                field = field,
                errorMessage = message,
                code = code,
            )
        ),
    )
}

private fun InvalidWeightEntryException.toDiagnosticField(): String {
    return when {
        message.contains("recordedDate") -> "recordedDate"
        message.contains("notes") -> "notes"
        else -> "weight"
    }
}

private fun WeightEntry.toReadModel(): WeightEntryReadModel {
    return WeightEntryReadModel(
        id = requireNotNull(id),
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
