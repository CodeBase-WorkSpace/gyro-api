package com.gyro.api.weight.infrastructure

import com.gyro.api.weight.domain.WeightEntry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.*

interface WeightEntryRepository : JpaRepository<WeightEntry, UUID> {
    fun findByUserIdAndRecordedDate(
        userId: UUID,
        recordedDate: LocalDate,
    ): WeightEntry?

    fun findByUserIdAndRecordedDateIn(
        userId: UUID,
        recordedDates: Collection<LocalDate>,
    ): List<WeightEntry>

    fun findByUserIdAndRecordedDateBetween(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        pageable: Pageable,
    ): Page<WeightEntry>
}
