package com.gyro.api.user

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.diary.infrastructure.DiaryDayRecord
import com.gyro.api.diary.infrastructure.DiaryEntrySnapshot
import com.gyro.api.diary.infrastructure.DiaryRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Clock
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Import(
    TestcontainersConfiguration::class,
    UserActivityHeatmapIntegrationTest.FixedClockConfiguration::class,
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.user-preferences.default-timezone=America/New_York",
        "app.user-preferences.default-locale=en_US",
    ]
)
class UserActivityHeatmapIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val diaryRepository: DiaryRepository,
) {
    @Test
    fun `activity heatmap returns empty calendar range with profile metadata`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "activity-empty-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-06-01"))
            .andExpect(jsonPath("$.to").value("2026-06-03"))
            .andExpect(jsonPath("$.timezone").value("America/New_York"))
            .andExpect(jsonPath("$.locale").value("en-US"))
            .andExpect(jsonPath("$.totalLoggedDays").value(0))
            .andExpect(jsonPath("$.maxEntryCount").value(0))
            .andExpect(jsonPath("$.bucketThresholds[0].bucket").value(0))
            .andExpect(jsonPath("$.bucketThresholds[4].minEntryCount").value(4))
            .andExpect(jsonPath("$.bucketThresholds[4].maxEntryCount").doesNotExist())
            .andExpect(jsonPath("$.days.length()").value(3))
            .andExpect(jsonPath("$.days[0].date").value("2026-06-01"))
            .andExpect(jsonPath("$.days[0].entryCount").value(0))
            .andExpect(jsonPath("$.days[0].logged").value(false))
            .andExpect(jsonPath("$.days[0].intensity").value(0))
    }

    @Test
    fun `activity heatmap fills partial ranges and isolates ownership`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "activity-partial-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "activity-partial-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedEntries(userId, "2026-06-02", 2)
        seedEntries(userId, "2026-06-03", 0)
        seedEntries(userId, "2026-06-04", 1)
        seedEntries(otherUserId, "2026-06-02", 4)

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-05")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.locale").value("fa-IR"))
            .andExpect(jsonPath("$.totalLoggedDays").value(3))
            .andExpect(jsonPath("$.maxEntryCount").value(2))
            .andExpect(jsonPath("$.days.length()").value(5))
            .andExpect(jsonPath("$.days[0].entryCount").value(0))
            .andExpect(jsonPath("$.days[0].logged").value(false))
            .andExpect(jsonPath("$.days[0].intensity").value(0))
            .andExpect(jsonPath("$.days[1].date").value("2026-06-02"))
            .andExpect(jsonPath("$.days[1].entryCount").value(2))
            .andExpect(jsonPath("$.days[1].logged").value(true))
            .andExpect(jsonPath("$.days[1].intensity").value(2))
            .andExpect(jsonPath("$.days[2].date").value("2026-06-03"))
            .andExpect(jsonPath("$.days[2].entryCount").value(0))
            .andExpect(jsonPath("$.days[2].logged").value(true))
            .andExpect(jsonPath("$.days[2].intensity").value(0))
            .andExpect(jsonPath("$.days[3].date").value("2026-06-04"))
            .andExpect(jsonPath("$.days[3].entryCount").value(1))
            .andExpect(jsonPath("$.days[3].intensity").value(1))
    }

    @Test
    fun `activity heatmap maps entry counts to capped intensity buckets`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "activity-buckets-${System.nanoTime()}@example.com")
        seedEntries(userId, "2026-06-01", 1)
        seedEntries(userId, "2026-06-02", 2)
        seedEntries(userId, "2026-06-03", 3)
        seedEntries(userId, "2026-06-04", 5)

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-05")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalLoggedDays").value(4))
            .andExpect(jsonPath("$.maxEntryCount").value(5))
            .andExpect(jsonPath("$.days[0].intensity").value(1))
            .andExpect(jsonPath("$.days[1].intensity").value(2))
            .andExpect(jsonPath("$.days[2].intensity").value(3))
            .andExpect(jsonPath("$.days[3].entryCount").value(5))
            .andExpect(jsonPath("$.days[3].intensity").value(4))
            .andExpect(jsonPath("$.days[4].intensity").value(0))
    }

    @Test
    fun `activity heatmap finalizes consistency scores for completed no-goal days`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "activity-score-consistency-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedEntries(
            userId = userId,
            date = "2026-06-16",
            mealTypes = listOf("BREAKFAST", "LUNCH", "DINNER", "SNACK"),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-17")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(100))
            .andExpect(jsonPath("$.days[0].scoreMode").value("CONSISTENCY"))
            .andExpect(jsonPath("$.days[0].scoreBand").value("EXCELLENT"))
            .andExpect(jsonPath("$.days[0].finalizedAt").exists())
            .andExpect(jsonPath("$.days[1].score").value(0))
            .andExpect(jsonPath("$.days[1].scoreMode").value("CONSISTENCY"))

        mockMvc.perform(
            get("/api/v1/progress/daily-scores")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-17")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scoreCount").value(2))
            .andExpect(jsonPath("$.averageScore").value(50.00))
            .andExpect(jsonPath("$.formulaVersions['2026-07-23']").value(2))
            .andExpect(jsonPath("$.distribution.EXCELLENT").value(1))
            .andExpect(jsonPath("$.distribution.POOR").value(1))
            .andExpect(jsonPath("$.weeklyAverages[0].from").value("2026-06-13"))
            .andExpect(jsonPath("$.weeklyAverages[0].averageScore").value(50.00))
            .andExpect(jsonPath("$.monthlyAverages[0].from").value("2026-06-01"))
            .andExpect(jsonPath("$.monthlyAverages[0].averageScore").value(50.00))
            .andExpect(jsonPath("$.bestDays[0].formulaVersion").value("2026-07-23"))
    }

    @Test
    fun `activity heatmap finalizes goal adherence score and keeps it immutable`() {
        val userId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        seedUser(userId, "activity-score-goal-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedNutritionPlan(
            userId = userId,
            planId = planId,
            startDate = "2026-06-16",
            calories = BigDecimal("2000.00"),
            protein = BigDecimal("100.000"),
            carbs = BigDecimal("200.000"),
            fat = BigDecimal("70.000"),
        )
        seedEntries(
            userId = userId,
            date = "2026-06-16",
            mealTypes = listOf("BREAKFAST"),
            calories = BigDecimal("2000.00"),
            protein = BigDecimal("100.000"),
            carbs = BigDecimal("200.000"),
            fat = BigDecimal("70.000"),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(100))
            .andExpect(jsonPath("$.days[0].scoreMode").value("GOAL_ADHERENCE"))
            .andExpect(jsonPath("$.days[0].scoreBand").value("EXCELLENT"))

        jdbcTemplate.update(
            "update diary_entries set calories_snapshot = 0 where user_id = ? and diary_date = '2026-06-16'::date",
            userId,
        )
        jdbcTemplate.update(
            "delete from nutrition_plans where id = ?",
            planId,
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(100))
            .andExpect(jsonPath("$.days[0].scoreMode").value("GOAL_ADHERENCE"))
    }

    @Test
    fun `activity heatmap recomputes a finalized score after diary backfill`() {
        val userId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        seedUser(userId, "activity-score-backfill-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedNutritionPlan(
            userId = userId,
            planId = planId,
            startDate = "2026-06-16",
            calories = BigDecimal("2000.00"),
            protein = BigDecimal("100.000"),
            carbs = BigDecimal("200.000"),
            fat = BigDecimal("70.000"),
        )
        val seeded = seedEntries(
            userId = userId,
            date = "2026-06-16",
            mealTypes = listOf("BREAKFAST"),
            calories = BigDecimal("1000.00"),
            protein = BigDecimal("50.000"),
            carbs = BigDecimal("100.000"),
            fat = BigDecimal("35.000"),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(29))

        seedNutritionPlan(
            userId = userId,
            planId = UUID.randomUUID(),
            startDate = "2026-06-17",
            calories = BigDecimal("3000.00"),
            protein = BigDecimal("150.000"),
            carbs = BigDecimal("300.000"),
            fat = BigDecimal("105.000"),
        )
        diaryRepository.createEntry(
            userId = userId,
            day = DiaryDayRecord(seeded.dayId, LocalDate.parse("2026-06-16"), "Asia/Tehran"),
            snapshot = scoreEntrySnapshot(),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(100))
    }

    @Test
    fun `activity heatmap recomputes a finalized score after diary deletion`() {
        val userId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        seedUser(userId, "activity-score-delete-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedNutritionPlan(
            userId = userId,
            planId = planId,
            startDate = "2026-06-16",
            calories = BigDecimal("2000.00"),
            protein = BigDecimal("100.000"),
            carbs = BigDecimal("200.000"),
            fat = BigDecimal("70.000"),
        )
        val seeded = seedEntries(
            userId = userId,
            date = "2026-06-16",
            mealTypes = listOf("BREAKFAST", "LUNCH"),
            calories = BigDecimal("1000.00"),
            protein = BigDecimal("50.000"),
            carbs = BigDecimal("100.000"),
            fat = BigDecimal("35.000"),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(100))

        diaryRepository.deleteEntry(
            userId = userId,
            day = DiaryDayRecord(seeded.dayId, LocalDate.parse("2026-06-16"), "Asia/Tehran"),
            entryId = seeded.entryIds.first(),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-16")
                .param("to", "2026-06-16")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].score").value(29))
    }

    @Test
    fun `activity heatmap does not finalize today or dates before account creation`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "activity-score-today-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran", "fa-IR")
        seedEntries(
            userId = userId,
            date = "2026-06-20",
            mealTypes = listOf("BREAKFAST", "LUNCH", "DINNER"),
        )

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-14")
                .param("to", "2026-06-20")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days[0].date").value("2026-06-14"))
            .andExpect(jsonPath("$.days[0].score").doesNotExist())
            .andExpect(jsonPath("$.days[6].date").value("2026-06-20"))
            .andExpect(jsonPath("$.days[6].score").doesNotExist())
    }

    @Test
    fun `activity heatmap rejects invalid dates and excessive spans`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "activity-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-05")
                .param("to", "2026-06-01")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-01-01")
                .param("to", "2027-01-02")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/users/me/activity-heatmap")
                .with(authentication(testAuthentication(userId)))
                .param("from", "not-a-date")
                .param("to", "2026-06-01")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    private fun seedUser(id: UUID, email: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id,
                email,
                password_hash,
                role,
                email_verification_status,
                phone_verification_status,
                status,
                created_at,
                updated_at
            )
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', ?, ?)
            """.trimIndent(),
            id,
            email,
            Timestamp.from(USER_CREATED_INSTANT),
            Timestamp.from(USER_CREATED_INSTANT),
        )
    }

    private fun seedProfile(userId: UUID, timezone: String, locale: String) {
        jdbcTemplate.update(
            """
            insert into user_profiles (
                user_id,
                timezone,
                locale,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            timezone,
            locale,
            Timestamp.from(USER_CREATED_INSTANT),
            Timestamp.from(USER_CREATED_INSTANT),
        )
    }

    private fun seedEntries(userId: UUID, date: String, count: Int): SeededEntries {
        return seedEntries(
            userId = userId,
            date = date,
            mealTypes = List(count) { "SNACK" },
        )
    }

    private fun seedEntries(
        userId: UUID,
        date: String,
        mealTypes: List<String>,
        calories: BigDecimal = BigDecimal.ZERO,
        protein: BigDecimal = BigDecimal.ZERO,
        carbs: BigDecimal = BigDecimal.ZERO,
        fat: BigDecimal = BigDecimal.ZERO,
    ): SeededEntries {
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, ?::date, 'Asia/Tehran', ?, ?)
            """.trimIndent(),
            dayId,
            userId,
            date,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )

        val entryIds = mealTypes.mapIndexed { index, mealType ->
            val entryId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                insert into diary_entries (
                    id,
                    diary_day_id,
                    user_id,
                    diary_date,
                    meal_type,
                    source_type,
                    display_name_snapshot,
                    serving_quantity_snapshot,
                    serving_unit_code_snapshot,
                    serving_unit_name_snapshot,
                    calories_snapshot,
                    protein_snapshot,
                    carbs_snapshot,
                    fat_snapshot,
                    fiber_snapshot,
                    sugar_snapshot,
                    sodium_snapshot,
                    sort_order,
                    created_at,
                    updated_at
                )
                values (
                    ?, ?, ?, ?::date, ?, 'MANUAL', 'Heatmap Entry',
                    1.0000, 'SERVING', 'Serving',
                    ?, ?, ?, ?, 0.000, 0.000, 0.000,
                    ?, ?, ?
                )
                """.trimIndent(),
                entryId,
                dayId,
                userId,
                date,
                mealType,
                calories,
                protein,
                carbs,
                fat,
                index,
                Timestamp.from(FIXED_INSTANT),
                Timestamp.from(FIXED_INSTANT),
            )
            entryId
        }
        return SeededEntries(dayId = dayId, entryIds = entryIds)
    }

    private fun scoreEntrySnapshot() = DiaryEntrySnapshot(
        mealType = "LUNCH",
        sourceType = "MANUAL",
        sourceFoodId = null,
        sourceMealId = null,
        displayName = "Backfilled Entry",
        servingQuantity = BigDecimal.ONE,
        servingUnitId = null,
        servingUnitCode = "SERVING",
        servingUnitName = "Serving",
        calories = BigDecimal("1000.00"),
        protein = BigDecimal("50.000"),
        carbs = BigDecimal("100.000"),
        fat = BigDecimal("35.000"),
        fiber = BigDecimal.ZERO,
        sugar = BigDecimal.ZERO,
        sodium = BigDecimal.ZERO,
    )

    private fun seedNutritionPlan(
        userId: UUID,
        planId: UUID,
        startDate: String,
        calories: BigDecimal,
        protein: BigDecimal,
        carbs: BigDecimal,
        fat: BigDecimal,
    ) {
        jdbcTemplate.update(
            """
            insert into nutrition_plans (
                id,
                user_id,
                start_date,
                timezone,
                calories,
                protein,
                carbs,
                fat,
                calculator_goal_type,
                created_at,
                updated_at
            )
            values (?, ?, ?::date, 'Asia/Tehran', ?, ?, ?, ?, 'MAINTAIN_WEIGHT', ?, ?)
            """.trimIndent(),
            planId,
            userId,
            startDate,
            calories,
            protein,
            carbs,
            fat,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-06-20T08:00:00Z")
        val USER_CREATED_INSTANT: Instant = Instant.parse("2026-06-15T12:00:00Z")
    }

    private data class SeededEntries(
        val dayId: UUID,
        val entryIds: List<UUID>,
    )

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        @Primary
        fun fixedClock(): Clock {
            return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
        }
    }
}
