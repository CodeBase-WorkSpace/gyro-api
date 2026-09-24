package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class GoalPreviewControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `preview returns calculated goal without mutating user data`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.formula.name").value("MIFFLIN_ST_JEOR"))
            .andExpect(jsonPath("$.maintenanceCalories").value(2581.00))
            .andExpect(jsonPath("$.targetCalories").value(1921.00))
            .andExpect(jsonPath("$.activityFactor").value(1.450))
            .andExpect(jsonPath("$.dailyEnergyDelta").value(-660.00))
            .andExpect(jsonPath("$.weeklyWeightChangeKg").value(-0.600))
            .andExpect(jsonPath("$.timeline.estimatedWeeksMax").value(15))
            .andExpect(jsonPath("$.macros.proteinGrams").value(144.000))
            .andExpect(jsonPath("$.warnings.length()").value(0))

        assertEquals(0, countRows("nutrition_plans", userId))
        assertEquals(0, countRows("plan_schedules", userId))
        assertEquals(1, countRows("user_profiles", userId))
        assertEquals(
            null,
            jdbcTemplate.queryForObject(
                "select goal_type from user_profiles where user_id = ?",
                String::class.java,
                userId,
            ),
        )
    }

    @Test
    fun `free preview reports available historical evidence without revealing calibrated numbers`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-locked-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val yesterday = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(1)
        seedObservedEvidence(userId, yesterday)

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.observedCalibration.status").value("LOCKED"))
            .andExpect(jsonPath("$.observedCalibration.windowDays").value(14))
            .andExpect(jsonPath("$.observedCalibration.recommendation").doesNotExist())
    }

    @Test
    fun `preview validates required fields before calculation`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"heightCm":180}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'sex')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'birthDate')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'currentWeightKg')]").exists())
    }

    @Test
    fun `preview returns field error codes for invalid measurement ranges`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-range-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sex": "MALE",
                      "birthDate": "1996-06-25",
                      "heightCm": 40,
                      "currentWeightKg": 0,
                      "targetWeightKg": 600,
                      "dailyMovementLevel": "LIGHT",
                      "workoutFrequency": "THREE_TO_FOUR_DAYS",
                      "goalType": "LOSE_WEIGHT",
                      "speed": "BALANCED"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'heightCm' && @.code == 'DECIMAL_MIN')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'currentWeightKg' && @.code == 'DECIMAL_MIN')]").exists())
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'targetWeightKg' && @.code == 'DECIMAL_MAX')]").exists())
    }

    @Test
    fun `preview uses authenticated user's profile timezone for calculation date`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-timezone-${System.nanoTime()}@example.com")
        seedProfile(userId, "Pacific/Pago_Pago")
        val expectedDate = LocalDate.now(ZoneId.of("Pacific/Pago_Pago")).toString()

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.calculationDate").value(expectedDate))
    }

    @Test
    fun `preview returns warning codes and blocking flags for unsafe but valid inputs`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preview-warning-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/goals/preview")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sex": "FEMALE",
                      "birthDate": "2011-06-25",
                      "heightCm": 180,
                      "currentWeightKg": 52,
                      "targetWeightKg": 50,
                      "dailyMovementLevel": "SEDENTARY",
                      "workoutFrequency": "ZERO_DAYS",
                      "goalType": "LOSE_WEIGHT",
                      "speed": "AGGRESSIVE"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.warnings[?(@.code == 'UNDER_18' && @.blocking == true)]").exists())
            .andExpect(jsonPath("$.warnings[?(@.code == 'LOW_CURRENT_BMI' && @.blocking == true)]").exists())
            .andExpect(jsonPath("$.warnings[?(@.code == 'LOW_TARGET_BMI' && @.blocking == true)]").exists())
            .andExpect(jsonPath("$.warnings[?(@.code == 'AGGRESSIVE_WEIGHT_LOSS' && @.blocking == false)]").exists())
    }

    private fun validBody(): String {
        return """
            {
              "sex": "MALE",
              "birthDate": "1996-06-25",
              "heightCm": 180,
              "currentWeightKg": 80,
              "targetWeightKg": 72,
              "dailyMovementLevel": "LIGHT",
              "workoutFrequency": "THREE_TO_FOUR_DAYS",
              "goalType": "LOSE_WEIGHT",
              "speed": "BALANCED"
            }
        """.trimIndent()
    }

    private fun seedUser(
        id: UUID,
        email: String,
    ) {
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
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
        )
    }

    private fun seedProfile(
        userId: UUID,
        timezone: String,
    ) {
        jdbcTemplate.update(
            """
            insert into user_profiles (user_id, timezone, locale, created_at, updated_at)
            values (?, ?, 'fa-IR', now(), now())
            """.trimIndent(),
            userId,
            timezone,
        )
    }

    private fun seedObservedEvidence(userId: UUID, windowEnd: LocalDate) {
        repeat(10) { offset ->
            val date = windowEnd.minusDays(offset.toLong())
            val diaryDayId = UUID.randomUUID()
            jdbcTemplate.update(
                "insert into diary_days (id, user_id, diary_date, timezone) values (?, ?, ?, 'Asia/Tehran')",
                diaryDayId,
                userId,
                date,
            )
            jdbcTemplate.update(
                """
                insert into diary_entries (
                    diary_day_id, user_id, diary_date, meal_type, source_type,
                    display_name_snapshot, serving_quantity_snapshot,
                    serving_unit_code_snapshot, serving_unit_name_snapshot, calories_snapshot
                ) values (?, ?, ?, 'DINNER', 'MANUAL', 'test', 1, 'serving', 'serving', 2800)
                """.trimIndent(),
                diaryDayId,
                userId,
                date,
            )
        }
        listOf(0L, 4L, 8L, 12L).forEach { offset ->
            val date = windowEnd.minusDays(offset)
            jdbcTemplate.update(
                """
                insert into weight_entries (
                    user_id, recorded_date, weight_kg, display_weight, display_unit, source
                ) values (?, ?, 80, 80, 'KG', 'MANUAL')
                """.trimIndent(),
                userId,
                date,
            )
        }
    }

    private fun countRows(
        tableName: String,
        userId: UUID,
    ): Int {
        return jdbcTemplate.queryForObject(
            "select count(*) from $tableName where user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
