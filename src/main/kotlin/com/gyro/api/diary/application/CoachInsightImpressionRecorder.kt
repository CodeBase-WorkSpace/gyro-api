package com.gyro.api.diary.application

import com.gyro.api.diary.infrastructure.CoachInsightImpressionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

enum class CoachInsightImpressionRecordOutcome { INSERTED, ALREADY_RECORDED, FAILED }

@Service
class CoachInsightImpressionRecorder(
    private val writer: CoachInsightImpressionWriter,
    private val metrics: CoachInsightImpressionMetrics,
) {
    fun recordSafely(
        userId: UUID,
        keys: Collection<String>,
        shownOn: LocalDate,
    ): CoachInsightImpressionRecordOutcome {
        if (keys.isEmpty()) return CoachInsightImpressionRecordOutcome.ALREADY_RECORDED
        return try {
            if (writer.record(userId, keys, shownOn) > 0) {
                CoachInsightImpressionRecordOutcome.INSERTED
            } else {
                CoachInsightImpressionRecordOutcome.ALREADY_RECORDED
            }
        } catch (exception: Exception) {
            metrics.writeFailed()
            logger.warn(
                "Coach insight impressions could not be recorded for user {}.",
                userId,
                exception,
            )
            CoachInsightImpressionRecordOutcome.FAILED
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CoachInsightImpressionRecorder::class.java)
    }
}

@Service
class CoachInsightImpressionWriter(
    private val repository: CoachInsightImpressionRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        userId: UUID,
        keys: Collection<String>,
        shownOn: LocalDate,
    ): Int = repository.record(userId, keys, shownOn)
}
