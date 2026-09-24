package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.DashboardInsightKind
import com.gyro.api.goal.application.coach.CoachIssuedImpressionRegistry
import com.gyro.api.goal.application.coach.CoachIssuedImpressionVerificationOutcome
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(properties = [
    "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "app.rate-limit.enabled=false",
    "app.nutrition-coach.enabled=true",
])
class NutritionCoachControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
    @Autowired private val issuedImpressionRegistry: CoachIssuedImpressionRegistry,
) {
    @Test
    fun `coach state requires authentication`() {
        mockMvc.perform(get("/api/v1/goals/coach/state"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `coach state read does not record impressions before the client displays them`() {
        val userId = UUID.randomUUID()
        seedAutomaticPlan(userId)

        mockMvc.perform(get("/api/v1/goals/coach/state").with(authentication(auth(userId))))
            .andExpect(status().isOk)

        assertEquals(0, impressionCount(userId))
    }

    @Test
    fun `visible impression endpoint is authenticated validated and idempotent`() {
        val userId = UUID.randomUUID()
        seedAutomaticPlan(userId)
        val returnedBeforeStateRead = stateObservationReturnedCount()
        val state = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn()
        val returnedAfterStateRead = stateObservationReturnedCount()
        val acceptedBefore = impressionAcceptedCount()
        val duplicateBefore = impressionDuplicateCount()
        assertTrue(returnedAfterStateRead > returnedBeforeStateRead)
        val impressionId = com.jayway.jsonpath.JsonPath.read<String>(
            state.response.contentAsString,
            "$.insights[0].impressionId",
        )
        val request = post("/api/v1/goals/coach/impressions")
            .with(authentication(auth(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"impressionId":"$impressionId"}""")

        mockMvc.perform(request).andExpect(status().isNoContent)
        mockMvc.perform(request).andExpect(status().isNoContent)

        assertEquals(1, impressionCount(userId))
        assertEquals(1.0, impressionAcceptedCount() - acceptedBefore)
        assertEquals(1.0, impressionDuplicateCount() - duplicateBefore)
        val rejectedBefore = impressionRejectedCount()
        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"impressionId":"OBS|CA|2026-07-22|DOWN|MODERATE"}""")
        ).andExpect(status().isConflict)
        assertEquals(1, impressionCount(userId))
        assertEquals(acceptedBefore + 1.0, impressionAcceptedCount())
        assertEquals(1.0, impressionRejectedCount() - rejectedBefore)
        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"impressionId":"OBS|CA|not-a-date|DOWN|MODERATE"}""")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"impressionId":"$impressionId"}""")
        ).andExpect(status().isUnauthorized)
        assertEquals(returnedAfterStateRead, stateObservationReturnedCount())
    }

    @Test
    fun `issued impressions are owner scoped`() {
        val issuerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedAutomaticPlan(issuerUserId)
        seedAutomaticPlan(otherUserId)
        val state = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(issuerUserId)))
        ).andExpect(status().isOk).andReturn()
        val impressionId = com.jayway.jsonpath.JsonPath.read<String>(
            state.response.contentAsString,
            "$.insights[0].impressionId",
        )

        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .with(authentication(auth(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"impressionId":"$impressionId"}""")
        ).andExpect(status().isConflict)

        assertEquals(0, impressionCount(otherUserId))
    }

    @Test
    fun `issued registry retains exactly the current and previous distinct states`() {
        val userId = UUID.randomUUID()
        val first = "OBS|CA|2026-07-20|DOWN|MODERATE"
        val second = "OBS|ST|2026-07-21|UP|MEDIUM"
        val third = "OBS|PC|2026-07-22|TARGET|STABLE"
        issuedImpressionRegistry.issue(
            userId,
            mapOf(first to DashboardInsightKind.CALORIE_ADHERENCE),
        )
        issuedImpressionRegistry.issue(
            userId,
            mapOf(second to DashboardInsightKind.SCORE_TREND),
        )

        assertEquals(
            CoachIssuedImpressionVerificationOutcome.ISSUED,
            issuedImpressionRegistry.verify(userId, first).outcome,
        )
        issuedImpressionRegistry.issue(
            userId,
            mapOf(second to DashboardInsightKind.SCORE_TREND),
        )
        assertEquals(
            CoachIssuedImpressionVerificationOutcome.ISSUED,
            issuedImpressionRegistry.verify(userId, first).outcome,
        )

        issuedImpressionRegistry.issue(
            userId,
            mapOf(third to DashboardInsightKind.PROTEIN_CONSISTENCY),
        )

        assertEquals(
            CoachIssuedImpressionVerificationOutcome.NOT_ISSUED,
            issuedImpressionRegistry.verify(userId, first).outcome,
        )
        assertEquals(
            CoachIssuedImpressionVerificationOutcome.ISSUED,
            issuedImpressionRegistry.verify(userId, second).outcome,
        )
        assertEquals(
            CoachIssuedImpressionVerificationOutcome.ISSUED,
            issuedImpressionRegistry.verify(userId, third).outcome,
        )
    }

    @Test
    fun `free pending recommendation is returned as locked without entitlement gate`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedPending(userId, planId)

        mockMvc.perform(get("/api/v1/goals/coach/state").with(authentication(auth(userId))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("FULL"))
            .andExpect(jsonPath("$.state").value("RECOMMENDATION_LOCKED"))
            .andExpect(jsonPath("$.recommendation").doesNotExist())
    }

    @Test
    fun `advanced pending recommendation is actionable`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedAdvancedSubscription(userId)
        seedPending(userId, planId)

        mockMvc.perform(get("/api/v1/goals/coach/state").with(authentication(auth(userId))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("FULL"))
            .andExpect(jsonPath("$.state").value("RECOMMENDATION"))
            .andExpect(jsonPath("$.recommendation.suggested.calories").value(1800))
    }

    @Test
    fun `advanced response serializes measured TDEE and exact impression is idempotent`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedAdvancedSubscription(userId)
        seedMeasuredTdeeEvidence(userId, planId, BigDecimal("2300"))

        val body = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val insights = com.jayway.jsonpath.JsonPath.read<List<Map<String, Any?>>>(body, "$.insights")
        val measured = insights.firstOrNull { it["kind"] == "MEASURED_TDEE" }
            ?: error("The Advanced response did not contain MEASURED_TDEE: $body")
        assertEquals("OBSERVED_ENERGY", measured["basis"])
        assertEquals("MEDIUM", measured["confidence"])
        assertEquals("OLS_7700_V1", measured["estimatorVersion"])
        assertEquals("V1", measured["displayPolicyVersion"])
        assertEquals(14, (measured["windowDays"] as Number).toInt())
        assertEquals(10, (measured["loggedDayCount"] as Number).toInt())
        assertEquals(5, (measured["weighInDayCount"] as Number).toInt())
        assertEquals(12, (measured["weightSpanDays"] as Number).toInt())
        assertEquals(2300, (measured["value"] as Number).toInt())
        val periodEnd = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(1)
        assertEquals("OBS|MT|V1|$periodEnd|MEDIUM|B2300", measured["impressionId"])

        // A second request must deserialize the sealed observation subtype from Redis,
        // not silently discard the cache entry and recompute it as a generic object.
        val hitsBefore = cacheCount("hit")
        val cachedBody = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val cachedMeasured = com.jayway.jsonpath.JsonPath
            .read<List<Map<String, Any?>>>(cachedBody, "$.insights")
            .firstOrNull { it["kind"] == "MEASURED_TDEE" }
            ?: error("The cached response lost its MEASURED_TDEE subtype: $cachedBody")
        assertTrue(cacheCount("hit") > hitsBefore)
        assertEquals("V1", cachedMeasured["displayPolicyVersion"])

        val impressionId = measured["impressionId"] as String
        val acceptedBefore = impressionAcceptedCount()
        val request = post("/api/v1/goals/coach/impressions")
            .with(authentication(auth(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"impressionId\":\"$impressionId\"}")
        mockMvc.perform(request).andExpect(status().isNoContent)
        mockMvc.perform(request).andExpect(status().isNoContent)

        assertEquals(1, impressionCount(userId))
        assertEquals(1.0, impressionAcceptedCount() - acceptedBefore)
    }

    @Test
    fun `free response redacts measured TDEE despite identical sufficient evidence`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedMeasuredTdeeEvidence(userId, planId, BigDecimal("2300"))

        val body = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertTrue(
            com.jayway.jsonpath.JsonPath.read<List<Map<String, Any?>>>(body, "$.insights")
                .none { it["kind"] == "MEASURED_TDEE" },
        )
        assertTrue(!body.contains("OBSERVED_ENERGY"))
        assertTrue(!body.contains("OLS_7700_V1"))
        assertTrue(!Regex("\\\"measuredTdee\\\"\\s*:\\s*\\d").containsMatchIn(body))
    }

    @Test
    fun `weekend gap is visible to free users and its exact impression is idempotent`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedWeekendGapEvidence(userId, planId)

        val weekendGap = weekendGapInsight(userId)

        assertEquals("TARGET_COMPARISON", weekendGap["basis"])
        assertEquals(70, (weekendGap["value"] as Number).toInt())
        assertEquals(70.0, (weekendGap["weekendTargetDeltaPercent"] as Number).toDouble())
        assertEquals(0.0, (weekendGap["weekdayTargetDeltaPercent"] as Number).toDouble())
        assertEquals(4, (weekendGap["weekendLoggedDayCount"] as Number).toInt())
        assertEquals(10, (weekendGap["weekdayLoggedDayCount"] as Number).toInt())
        assertEquals(14, (weekendGap["loggedDayCount"] as Number).toInt())
        assertEquals(14, (weekendGap["windowDays"] as Number).toInt())
        assertEquals(weekendGapPeriodEnd().minusDays(13).toString(), weekendGap["periodStart"])
        assertEquals(weekendGapPeriodEnd().toString(), weekendGap["periodEnd"])
        // `UP` renders as "improving", which this observation never claims.
        assertTrue(!weekendGap.containsKey("trend"), "weekend gap must not carry a generic trend")
        assertEquals(
            "OBS|WG|V1|${weekendGapPeriodEnd()}|HIGHER|VERY_LARGE",
            weekendGap["impressionId"],
        )

        val impressionId = weekendGap["impressionId"] as String
        val acceptedBefore = impressionAcceptedCount()
        val request = post("/api/v1/goals/coach/impressions")
            .with(authentication(auth(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"impressionId\":\"$impressionId\"}")
        mockMvc.perform(request).andExpect(status().isNoContent)
        mockMvc.perform(request).andExpect(status().isNoContent)

        assertEquals(1, impressionCount(userId))
        assertEquals(1.0, impressionAcceptedCount() - acceptedBefore)

        // Well formed, never issued: a different evidence date must not be recordable.
        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"impressionId\":\"OBS|WG|V1|${weekendGapPeriodEnd().minusDays(1)}|HIGHER|VERY_LARGE\"}"
                )
        ).andExpect(status().isConflict)
        assertEquals(1, impressionCount(userId))
    }

    @Test
    fun `trend explanation is visible to free users and its exact impression is idempotent`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedTrendExplanationEvidence(userId, planId)

        val periodEnd = trendExplanationPeriodEnd()
        val trend = trendExplanationInsight(userId)

        assertEquals("TARGET_COMPARISON", trend["basis"])
        assertEquals(-39, (trend["value"] as Number).toInt())
        assertEquals(-39.0, (trend["deltaPercent"] as Number).toDouble())
        assertEquals(1.4, (trend["weightTrendKgPerWeek"] as Number).toDouble())
        assertEquals(1220, (trend["averageIntakeCalories"] as Number).toInt())
        assertEquals(2000, (trend["averageTargetCalories"] as Number).toInt())
        assertEquals(14, (trend["loggedDayCount"] as Number).toInt())
        assertEquals(5, (trend["weighInDayCount"] as Number).toInt())
        assertEquals(12, (trend["weightSpanDays"] as Number).toInt())
        assertEquals(14, (trend["windowDays"] as Number).toInt())
        assertEquals("MEDIUM", trend["confidence"])
        assertEquals(periodEnd.minusDays(13).toString(), trend["periodStart"])
        assertEquals(periodEnd.toString(), trend["periodEnd"])
        // `UP` renders as "improving", which this observation never claims.
        assertTrue(!trend.containsKey("trend"), "trend explanation must not carry a generic trend")
        // No calculator or measured-TDEE value is exposed on this observation.
        assertTrue(!trend.containsKey("estimatorVersion"), "trend explanation must not expose a TDEE estimator")
        assertEquals("OBS|TX|V1|$periodEnd|W_LARGE|I_25_40", trend["impressionId"])

        val impressionId = trend["impressionId"] as String
        val acceptedBefore = impressionAcceptedCount()
        val request = post("/api/v1/goals/coach/impressions")
            .with(authentication(auth(userId)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"impressionId\":\"$impressionId\"}")
        mockMvc.perform(request).andExpect(status().isNoContent)
        mockMvc.perform(request).andExpect(status().isNoContent)

        assertEquals(1, impressionCount(userId))
        assertEquals(1.0, impressionAcceptedCount() - acceptedBefore)

        // Well formed, never issued: a different evidence date must not be recordable.
        mockMvc.perform(
            post("/api/v1/goals/coach/impressions")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"impressionId\":\"OBS|TX|V1|${periodEnd.minusDays(1)}|W_LARGE|I_25_40\"}"
                )
        ).andExpect(status().isConflict)
        assertEquals(1, impressionCount(userId))
    }

    @Test
    fun `weekend gap resolves the target active on each date and skips pre-plan records`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        val periodEnd = weekendGapPeriodEnd()
        val periodStart = periodEnd.minusDays(13)
        val secondPlanStart = periodStart.plusDays(8)
        // The window opens one day before the first plan, then the target changes
        // inside it. Intake is seeded as a multiple of each date's own target, so a
        // comparison against a single current target could not reproduce these deltas.
        jdbc.update(
            """update nutrition_plans set start_date = ?, calories = 2000, protein = 100,
               daily_energy_delta = 0, daily_energy_delta_source = 'FORMULA_WIZARD' where id = ?""",
            periodStart.plusDays(1),
            planId,
        )
        jdbc.update(
            """insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat,
                   daily_energy_delta, daily_energy_delta_source, created_at, updated_at)
               values (?, ?, 'Asia/Tehran', 2500, 100, 250, 85, 0, 'FORMULA_WIZARD', now(), now())""",
            userId,
            secondPlanStart,
        )
        seedDiaryDay(userId, periodStart, BigDecimal("3400"), BigDecimal("100"))
        (1L..13L).forEach { offset ->
            val date = periodStart.plusDays(offset)
            val target = if (date.isBefore(secondPlanStart)) BigDecimal("2000") else BigDecimal("2500")
            val intake = if (date.isWeekendGapWeekend()) target.multiply(BigDecimal("1.7")) else target
            seedDiaryDay(userId, date, intake, BigDecimal("100"))
        }

        val weekendGap = weekendGapInsight(userId)

        assertEquals(13, (weekendGap["loggedDayCount"] as Number).toInt())
        assertEquals(70.0, (weekendGap["weekendTargetDeltaPercent"] as Number).toDouble())
        assertEquals(0.0, (weekendGap["weekdayTargetDeltaPercent"] as Number).toDouble())
        assertEquals(70, (weekendGap["value"] as Number).toInt())
    }

    @Test
    fun `a diary change invalidates the coach cache and recomputes the weekend gap`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        seedWeekendGapEvidence(userId, planId)
        val periodEnd = weekendGapPeriodEnd()

        assertEquals(
            "OBS|WG|V1|$periodEnd|HIGHER|VERY_LARGE",
            weekendGapInsight(userId)["impressionId"],
        )

        // Remove the last entry of one weekend day and soften the rest, exactly as a
        // diary edit followed by the service's own dashboard-change event would.
        jdbc.update(
            "delete from diary_entries where user_id = ? and diary_date = ?",
            userId,
            weekendGapDates().first { it.isWeekendGapWeekend() },
        )
        jdbc.update(
            "update diary_entries set calories_snapshot = 2500 where user_id = ? and calories_snapshot = 3400",
            userId,
        )
        val missesBefore = cacheCount("miss")
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)

        val recomputed = weekendGapInsight(userId)

        assertTrue(cacheCount("miss") > missesBefore)
        assertEquals("OBS|WG|V1|$periodEnd|HIGHER|LARGE", recomputed["impressionId"])
        assertEquals(25, (recomputed["value"] as Number).toInt())
        assertEquals(3, (recomputed["weekendLoggedDayCount"] as Number).toInt())
    }

    @Test
    fun `coach state is served from Redis and generation invalidation forces recompute`() {
        val userId = UUID.randomUUID()
        seedAutomaticPlan(userId)
        val request = get("/api/v1/goals/coach/state").with(authentication(auth(userId)))

        mockMvc.perform(request).andExpect(status().isOk)
        val hitsBefore = cacheCount("hit")
        mockMvc.perform(request).andExpect(status().isOk)
        assertTrue(cacheCount("hit") > hitsBefore)

        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        val missesBefore = cacheCount("miss")
        mockMvc.perform(request).andExpect(status().isOk)
        assertTrue(cacheCount("miss") > missesBefore)
    }

    @Test
    fun `coach state finalizes historical scores outside its read-only transaction`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        val start = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(3)
        jdbc.update(
            "update user_profiles set created_at = now() - interval '3 days' where user_id = ?",
            userId,
        )
        jdbc.update(
            "update users set created_at = now() - interval '3 days' where id = ?",
            userId,
        )
        jdbc.update(
            "update nutrition_plans set start_date = ? where id = ?",
            start,
            planId,
        )

        mockMvc.perform(get("/api/v1/goals/coach/state").with(authentication(auth(userId))))
            .andExpect(status().isOk)

        assertEquals(
            3,
            jdbc.queryForObject(
                "select count(*) from daily_scores where user_id = ?",
                Int::class.java,
                userId,
            ),
        )
    }

    @Test
    fun `day seven readiness counts logged days regardless of calorie amount`() {
        val userId = UUID.randomUUID()
        val planId = seedAutomaticPlan(userId)
        val today = LocalDate.now(ZoneId.of("Asia/Tehran"))
        val start = today.minusDays(7)
        jdbc.update("update nutrition_plans set start_date = ? where id = ?", start, planId)
        repeat(3) { offset ->
            seedDiaryDay(userId, start.minusDays(offset.toLong() + 1), BigDecimal("2000"))
        }
        repeat(4) { offset -> seedDiaryDay(userId, start.plusDays(offset.toLong()), BigDecimal.ZERO) }

        mockMvc.perform(get("/api/v1/goals/coach/state").with(authentication(auth(userId))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("COLLECTING_DATA"))
            .andExpect(jsonPath("$.collecting.foodEvidenceDays").value(4))
            .andExpect(jsonPath("$.collecting.foodEvidenceDaysRequired").value(4))
            .andExpect(jsonPath("$.collecting.readinessReason").value("INSUFFICIENT_WEIGH_IN_DAYS"))
            .andExpect(jsonPath("$.collecting.weighedInToday").value(false))
            .andExpect(jsonPath("$.collecting.nextUsefulAction").value("LOG_WEIGHT_TODAY"))
    }

    private fun seedAutomaticPlan(userId: UUID): UUID {
        seedUser(userId)
        jdbc.update("insert into user_profiles (user_id, timezone, locale, created_at, updated_at) values (?, 'Asia/Tehran', 'fa-IR', now(), now())", userId)
        val start = LocalDate.now(ZoneId.of("Asia/Tehran"))
        return jdbc.queryForObject(
            """insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source, created_at, updated_at)
               values (?, ?, 'Asia/Tehran', 2000, 100, 200, 70, -500, 'FORMULA_WIZARD', now(), now()) returning id""",
            UUID::class.java, userId, start,
        )!!
    }

    private fun seedPending(userId: UUID, planId: UUID) {
        jdbc.update(
            """insert into plan_recalibration_suggestions
               (id, user_id, nutrition_plan_id, status, suggested_calories, suggested_protein, suggested_carbs, suggested_fat,
                previous_calories, previous_protein, previous_carbs, previous_fat, basis, created_at, expires_at)
               values (?, ?, ?, 'PENDING', 1800, 90, 180, 63, 2000, 100, 200, 70, cast('{}' as jsonb), now(), now() + interval '1 day')""",
            UUID.randomUUID(), userId, planId,
        )
    }

    private fun weekendGapPeriodEnd(): LocalDate =
        LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(1)

    private fun weekendGapDates(): List<LocalDate> =
        (0L..13L).map { weekendGapPeriodEnd().minusDays(13L - it) }

    private fun LocalDate.isWeekendGapWeekend(): Boolean =
        dayOfWeek == java.time.DayOfWeek.THURSDAY || dayOfWeek == java.time.DayOfWeek.FRIDAY

    /**
     * Fourteen completed days on a flat 2,000 kcal target where Thursday and Friday
     * are recorded 70 percentage points above their own target and every other day
     * is exactly on it. Protein meets the target so protein consistency stays quiet.
     */
    private fun seedWeekendGapEvidence(userId: UUID, planId: UUID) {
        jdbc.update(
            """update nutrition_plans set start_date = ?, calories = 2000, protein = 100,
               daily_energy_delta = 0, daily_energy_delta_source = 'FORMULA_WIZARD' where id = ?""",
            weekendGapDates().first(),
            planId,
        )
        weekendGapDates().forEach { date ->
            seedDiaryDay(
                userId = userId,
                date = date,
                calories = if (date.isWeekendGapWeekend()) BigDecimal("3400") else BigDecimal("2000"),
                protein = BigDecimal("100"),
            )
        }
    }

    private fun weekendGapInsight(userId: UUID): Map<String, Any?> {
        val body = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<List<Map<String, Any?>>>(body, "$.insights")
            .firstOrNull { it["kind"] == "WEEKEND_GAP" }
            ?: error("The coach response did not contain WEEKEND_GAP: $body")
    }

    private fun seedDiaryDay(
        userId: UUID,
        date: LocalDate,
        calories: BigDecimal,
        protein: BigDecimal = BigDecimal.ZERO,
    ) {
        val dayId = UUID.randomUUID()
        jdbc.update(
            "insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at) values (?, ?, ?, 'Asia/Tehran', now(), now())",
            dayId,
            userId,
            date,
        )
        jdbc.update(
            """
            insert into diary_entries (
                id, diary_day_id, user_id, diary_date, meal_type, source_type,
                display_name_snapshot, serving_quantity_snapshot,
                serving_unit_code_snapshot, serving_unit_name_snapshot,
                calories_snapshot, protein_snapshot, carbs_snapshot, fat_snapshot
            )
            values (?, ?, ?, ?, 'LUNCH', 'MANUAL', 'readiness fixture', 1, 'SERVING', 'serving', ?, ?, 0, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            dayId,
            userId,
            date,
            calories,
            protein,
        )
    }

    private fun trendExplanationPeriodEnd(): LocalDate =
        LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(1)

    /**
     * Fourteen completed days on a flat 2,000 kcal target where recorded intake is 39%
     * below target every day while a clean weight fit rises 1.4 kg/week. Protein meets
     * the target so protein consistency stays quiet. The strong divergence keeps the
     * observation inside the two-slot ranked pool.
     */
    private fun seedTrendExplanationEvidence(userId: UUID, planId: UUID) {
        val periodEnd = trendExplanationPeriodEnd()
        jdbc.update(
            """update nutrition_plans set start_date = ?, calories = 2000, protein = 100,
               daily_energy_delta = 0, daily_energy_delta_source = 'FORMULA_WIZARD' where id = ?""",
            periodEnd.minusDays(20),
            planId,
        )
        (0L..13L).forEach { offset ->
            seedDiaryDay(
                userId = userId,
                date = periodEnd.minusDays(offset),
                calories = BigDecimal("1220"),
                protein = BigDecimal("100"),
            )
        }
        // A clean linear rise of 0.200 kg/day, i.e. 1.400 kg/week (W_LARGE band), across a
        // twelve-day span, so the slope standard error is zero and the fit is plausible.
        val weights = mapOf(
            12L to "80.000",
            9L to "80.600",
            6L to "81.200",
            3L to "81.800",
            0L to "82.400",
        )
        weights.forEach { (daysAgo, weight) ->
            val date = periodEnd.minusDays(daysAgo)
            jdbc.update(
                """insert into weight_entries (
                   user_id, recorded_date, recorded_at, weight_kg, display_weight,
                   display_unit, source, created_at, updated_at
               ) values (?, ?, (?::date)::timestamp at time zone 'Asia/Tehran', ?::numeric, ?::numeric, 'KG', 'MANUAL', now(), now())""",
                userId,
                date,
                date,
                weight,
                weight,
            )
        }
    }

    private fun trendExplanationInsight(userId: UUID): Map<String, Any?> {
        val body = mockMvc.perform(
            get("/api/v1/goals/coach/state").with(authentication(auth(userId)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<List<Map<String, Any?>>>(body, "$.insights")
            .firstOrNull { it["kind"] == "TREND_EXPLANATION" }
            ?: error("The coach response did not contain TREND_EXPLANATION: $body")
    }

    private fun seedMeasuredTdeeEvidence(userId: UUID, planId: UUID, calories: BigDecimal) {
        val periodEnd = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(1)
        jdbc.update(
            "update nutrition_plans set start_date = ?, calories = ?, daily_energy_delta = 0, daily_energy_delta_source = 'FORMULA_WIZARD' where id = ?",
            periodEnd.minusDays(20),
            calories,
            planId,
        )
        repeat(10) { offset ->
            seedDiaryDay(userId, periodEnd.minusDays(offset.toLong()), calories)
        }
        listOf(12L, 9L, 6L, 3L, 0L).forEach { daysAgo ->
            val date = periodEnd.minusDays(daysAgo)
            jdbc.update(
                """insert into weight_entries (
                   user_id, recorded_date, recorded_at, weight_kg, display_weight,
                   display_unit, source, created_at, updated_at
               ) values (?, ?, (?::date)::timestamp at time zone 'Asia/Tehran', 80.000, 80.000, 'KG', 'MANUAL', now(), now())""",
                userId,
                date,
                date,
            )
        }
    }

    private fun seedUser(userId: UUID) {
        jdbc.update("""insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at)
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())""", userId, "coach-${System.nanoTime()}@example.com")
    }

    private fun seedAdvancedSubscription(userId: UUID) {
        val planId = jdbc.queryForObject("select id from subscription_plans where code = 'ADVANCED'", Long::class.java)!!
        jdbc.update("""insert into user_subscriptions (user_id, plan_id, status, period_start, period_end, cancel_at_period_end)
            values (?, ?, 'ACTIVE', now() - interval '1 day', now() + interval '30 days', false)""", userId, planId)
    }

    private fun auth(userId: UUID) = UsernamePasswordAuthenticationToken(userId.toString(), null, listOf(SimpleGrantedAuthority("ROLE_USER")))

    private fun cacheCount(outcome: String): Double =
        meterRegistry.find("gyro.goal.coach.cache")
            .tag("outcome", outcome)
            .counter()
            ?.count()
            ?: 0.0

    private fun stateObservationReturnedCount(): Double =
        meterRegistry.find("gyro.goal.coach.state.observations.returned")
            .counters()
            .sumOf { it.count() }

    private fun impressionAcceptedCount(): Double =
        meterRegistry.find(CoachInsightImpressionMetrics.IMPRESSION_ACCEPTED_METRIC)
            .counters()
            .sumOf { it.count() }

    private fun impressionRejectedCount(): Double =
        meterRegistry.find(CoachInsightImpressionMetrics.IMPRESSION_REJECTED_METRIC)
            .counters()
            .sumOf { it.count() }

    private fun impressionDuplicateCount(): Double =
        meterRegistry.find(CoachInsightImpressionMetrics.IMPRESSION_NOOP_METRIC)
            .counters()
            .sumOf { it.count() }

    private fun impressionCount(userId: UUID): Int =
        jdbc.queryForObject(
            "select count(*) from coach_insight_impressions where user_id = ?",
            Int::class.java,
            userId,
        )!!
}
