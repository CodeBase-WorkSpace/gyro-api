package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.goal.infrastructure.PlanRecalibrationSuggestionRepository
import com.gyro.api.goal.infrastructure.PlanTargetRegimeBoundaryRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class RecalibrationDataLoaderTest {
    @Test
    fun `active plan lookup uses the profile local date rather than Tehran`() {
        val userId = UUID.randomUUID()
        val plans = Mockito.mock(NutritionPlanRepository::class.java)
        val suggestions = Mockito.mock(PlanRecalibrationSuggestionRepository::class.java)
        val boundaries = Mockito.mock(PlanTargetRegimeBoundaryRepository::class.java)
        val dsl = Mockito.mock(DSLContext::class.java)
        val targets = Mockito.mock(ScheduleAwareDailyTargetLoader::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        val zone = ZoneId.of("America/Los_Angeles")
        Mockito.`when`(time.today(zone)).thenReturn(LocalDate.parse("2026-07-22"))
        Mockito.`when`(plans.findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, LocalDate.parse("2026-07-22"))).thenReturn(null)

        RecalibrationDataLoader(plans, targets, suggestions, boundaries, dsl, time).activePlanFor(userId, zone)

        Mockito.verify(plans).findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, LocalDate.parse("2026-07-22"))
    }

    @Test
    fun `producer lookup retains the historical Tehran active-plan date`() {
        val userId = UUID.randomUUID()
        val plans = Mockito.mock(NutritionPlanRepository::class.java)
        val suggestions = Mockito.mock(PlanRecalibrationSuggestionRepository::class.java)
        val boundaries = Mockito.mock(PlanTargetRegimeBoundaryRepository::class.java)
        val dsl = Mockito.mock(DSLContext::class.java)
        val targets = Mockito.mock(ScheduleAwareDailyTargetLoader::class.java)
        val time = Mockito.mock(TimeProvider::class.java)
        Mockito.`when`(time.now()).thenReturn(Instant.parse("2026-07-22T21:00:00Z"))
        Mockito.`when`(plans.findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, LocalDate.parse("2026-07-23"))).thenReturn(null)

        RecalibrationDataLoader(plans, targets, suggestions, boundaries, dsl, time).loadForProducer(userId)

        Mockito.verify(plans).findFirstByUserIdAndStartDateLessThanEqualOrderByStartDateDesc(userId, LocalDate.parse("2026-07-23"))
    }
}
