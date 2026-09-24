package com.gyro.api.goal.application.coach

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.diary.application.CoachInsightImpressionRecorder
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.CoachInsightImpressionRecordOutcome
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.user.application.UserTimezoneResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CoachInsightImpressionServiceTest {
    private val userId = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Tehran")
    private val today = LocalDate.parse("2026-07-23")
    private val recorder = Mockito.mock(CoachInsightImpressionRecorder::class.java)
    private val timezoneResolver = Mockito.mock(UserTimezoneResolver::class.java)
    private val timeProvider = Mockito.mock(TimeProvider::class.java)
    private val cache = Mockito.mock(NutritionCoachStateCache::class.java)
    private val metrics = Mockito.mock(CoachInsightImpressionMetrics::class.java)
    private val issuedImpressionRegistry = Mockito.mock(CoachIssuedImpressionRegistry::class.java)
    private val rateLimits = Mockito.mock(CoachInsightImpressionRateLimitService::class.java)

    @Test
    fun `a stale but issued rendered fingerprint is recorded without recomputing Coach state`() {
        val fingerprint = "OBS|CA|2026-06-01|DOWN|MODERATE"
        Mockito.`when`(issuedImpressionRegistry.verify(userId, fingerprint)).thenReturn(
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.ISSUED,
                DashboardInsightKind.CALORIE_ADHERENCE,
            ),
        )
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(timeProvider.today(zone)).thenReturn(today)
        Mockito.`when`(recorder.recordSafely(userId, listOf(fingerprint), today))
            .thenReturn(CoachInsightImpressionRecordOutcome.INSERTED)

        service().recordVisible(userId, fingerprint)

        Mockito.verify(recorder).recordSafely(userId, listOf(fingerprint), today)
        Mockito.verify(cache).invalidate(userId)
        Mockito.verify(metrics).impressionAccepted(DashboardInsightKind.CALORIE_ADHERENCE)
    }

    @Test
    fun `a well formed fingerprint that was never issued is rejected before persistence`() {
        val fingerprint = "OBS|CA|2026-07-22|DOWN|MODERATE"
        Mockito.`when`(issuedImpressionRegistry.verify(userId, fingerprint)).thenReturn(
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.NOT_ISSUED,
            ),
        )

        val exception = assertThrows<ResponseStatusException> {
            service().recordVisible(userId, fingerprint)
        }

        kotlin.test.assertEquals(409, exception.statusCode.value())
        Mockito.verifyNoInteractions(recorder, timezoneResolver, timeProvider, cache)
        Mockito.verify(metrics).impressionRejected(
            CoachInsightImpressionMetrics.REJECTION_NOT_ISSUED,
        )
    }

    @Test
    fun `a verified duplicate is a no-op and does not inflate accepted metrics`() {
        val fingerprint = "OBS|CA|2026-07-22|DOWN|MODERATE"
        Mockito.`when`(issuedImpressionRegistry.verify(userId, fingerprint)).thenReturn(
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.ISSUED,
                DashboardInsightKind.CALORIE_ADHERENCE,
            ),
        )
        Mockito.`when`(timezoneResolver.resolve(userId)).thenReturn(zone)
        Mockito.`when`(timeProvider.today(zone)).thenReturn(today)
        Mockito.`when`(recorder.recordSafely(userId, listOf(fingerprint), today)).thenReturn(
            CoachInsightImpressionRecordOutcome.ALREADY_RECORDED,
        )

        service().recordVisible(userId, fingerprint)

        Mockito.verify(metrics).impressionDuplicate()
        Mockito.verify(metrics, Mockito.never()).impressionAccepted(
            DashboardInsightKind.CALORIE_ADHERENCE,
        )
        Mockito.verifyNoInteractions(cache)
    }

    @Test
    fun `an issuance lookup failure fails closed`() {
        val fingerprint = "OBS|CA|2026-07-22|DOWN|MODERATE"
        Mockito.`when`(issuedImpressionRegistry.verify(userId, fingerprint)).thenReturn(
            CoachIssuedImpressionVerification(
                CoachIssuedImpressionVerificationOutcome.ERROR,
            ),
        )

        val exception = assertThrows<ResponseStatusException> {
            service().recordVisible(userId, fingerprint)
        }

        kotlin.test.assertEquals(503, exception.statusCode.value())
        Mockito.verifyNoInteractions(recorder, timezoneResolver, timeProvider, cache, metrics)
    }

    @Test
    fun `an unknown fingerprint is rejected before any impression work`() {
        assertThrows<ResponseStatusException> {
            service().recordVisible(userId, "OBS|CA|not-a-date|UP|LARGE")
        }

        Mockito.verifyNoInteractions(
            recorder,
            timezoneResolver,
            timeProvider,
            cache,
            metrics,
            issuedImpressionRegistry,
            rateLimits,
        )
    }

    private fun service() = CoachInsightImpressionService(
        recorder,
        timezoneResolver,
        timeProvider,
        cache,
        metrics,
        issuedImpressionRegistry,
        rateLimits,
    )
}
