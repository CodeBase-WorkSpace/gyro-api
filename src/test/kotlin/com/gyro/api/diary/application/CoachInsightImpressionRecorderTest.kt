package com.gyro.api.diary.application

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class CoachInsightImpressionRecorderTest {
    @Test
    fun `a failed impression write never breaks the coach response`() {
        val writer = Mockito.mock(CoachInsightImpressionWriter::class.java)
        val userId = UUID.randomUUID()
        val shownOn = LocalDate.parse("2026-07-23")
        Mockito.doThrow(IllegalStateException("database unavailable"))
            .`when`(writer)
            .record(userId, listOf("CALORIE_ADHERENCE"), shownOn)
        val meterRegistry = SimpleMeterRegistry()

        val outcome = CoachInsightImpressionRecorder(
            writer,
            CoachInsightImpressionMetrics(meterRegistry),
        ).recordSafely(
            userId = userId,
            keys = listOf("CALORIE_ADHERENCE"),
            shownOn = shownOn,
        )
        assertEquals(CoachInsightImpressionRecordOutcome.FAILED, outcome)
        assertEquals(
            1.0,
            meterRegistry.get(CoachInsightImpressionMetrics.FAILURE_METRIC)
                .tag("operation", CoachInsightImpressionMetrics.OPERATION_WRITE)
                .counter()
                .count(),
        )
    }

    @Test
    fun `insert and duplicate writes have distinct outcomes`() {
        val writer = Mockito.mock(CoachInsightImpressionWriter::class.java)
        val userId = UUID.randomUUID()
        val shownOn = LocalDate.parse("2026-07-23")
        val keys = listOf("CALORIE_ADHERENCE")
        val recorder = CoachInsightImpressionRecorder(
            writer,
            CoachInsightImpressionMetrics(SimpleMeterRegistry()),
        )
        Mockito.`when`(writer.record(userId, keys, shownOn)).thenReturn(1, 0)

        assertEquals(
            CoachInsightImpressionRecordOutcome.INSERTED,
            recorder.recordSafely(userId, keys, shownOn),
        )
        assertEquals(
            CoachInsightImpressionRecordOutcome.ALREADY_RECORDED,
            recorder.recordSafely(userId, keys, shownOn),
        )
    }
}
