package com.gyro.api.goal.application.coach

import com.gyro.api.diary.application.CalorieAdherenceInsight
import com.gyro.api.diary.application.DashboardInsightKind
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertSame

class NutritionCoachStateCacheAsideTest {
    private val userId = UUID.randomUUID()
    private val cache = Mockito.mock(NutritionCoachStateCache::class.java)
    private val resolver = Mockito.mock(NutritionCoachStateResolver::class.java)
    private val issuedImpressionRegistry = Mockito.mock(CoachIssuedImpressionRegistry::class.java)
    @Suppress("UNCHECKED_CAST")
    private val metrics = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MeterRegistry>
    private val service = NutritionCoachStateService(
        cache,
        resolver,
        metrics,
        issuedImpressionRegistry,
    )
    private val impressionId = "OBS|CA|2026-07-22|DOWN|MODERATE"
    private val state = NutritionCoachStateResult(
        mode = NutritionCoachMode.FULL,
        state = NutritionCoachState.ON_TRACK,
        asOf = Instant.parse("2026-07-23T10:00:00Z"),
        insights = listOf(
            CalorieAdherenceInsight(
                impressionId = impressionId,
                value = -10,
                averageIntakeCalories = 1800,
                averageTargetCalories = 2000,
                deltaPercent = BigDecimal("-10"),
                loggedDayCount = 5,
                periodStart = LocalDate.parse("2026-07-16"),
                periodEnd = LocalDate.parse("2026-07-22"),
            ),
        ),
    )

    @Test
    fun `cache hit serves state without database resolver work`() {
        Mockito.`when`(cache.get(userId)).thenReturn(
            NutritionCoachCacheLookup(state, NutritionCoachCacheOutcome.HIT, generation = 3)
        )

        assertSame(state, service.stateFor(userId))

        Mockito.verifyNoInteractions(resolver)
        Mockito.verify(cache).get(userId)
        Mockito.verifyNoMoreInteractions(cache)
        Mockito.verify(issuedImpressionRegistry).issue(
            userId,
            mapOf(impressionId to DashboardInsightKind.CALORIE_ADHERENCE),
        )
    }

    @Test
    fun `cache miss computes once and stores the complete state`() {
        Mockito.`when`(cache.get(userId)).thenReturn(
            NutritionCoachCacheLookup(null, NutritionCoachCacheOutcome.MISS, generation = 3)
        )
        Mockito.`when`(resolver.resolve(userId)).thenReturn(state)

        assertSame(state, service.stateFor(userId))

        Mockito.verify(resolver).resolve(userId)
        Mockito.verify(cache).put(userId, state, generation = 3)
        Mockito.verify(issuedImpressionRegistry).issue(
            userId,
            mapOf(impressionId to DashboardInsightKind.CALORIE_ADHERENCE),
        )
    }
}
