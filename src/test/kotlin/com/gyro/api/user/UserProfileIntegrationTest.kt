package com.gyro.api.user

import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
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
class UserProfileIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val userProfileRepository: UserProfileRepository,
) {

    @Test
    fun `patch profile updates and persists current user profile`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "profile-${System.nanoTime()}@example.com")

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "displayName": "Gyro Tester",
                      "timezone": "Europe/Berlin",
                      "locale": "de-DE"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.displayName").value("Gyro Tester"))
            .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
            .andExpect(jsonPath("$.locale").value("de-DE"))

        val persistedProfile = jdbcTemplate.queryForMap(
            """
            select display_name, timezone, locale
            from user_profiles
            where user_id = ?
            """.trimIndent(),
            userId,
        )

        assertEquals("Gyro Tester", persistedProfile["display_name"])
        assertEquals("Europe/Berlin", persistedProfile["timezone"])
        assertEquals("de-DE", persistedProfile["locale"])
    }

    @Test
    fun `patch profile rejects unauthenticated requests`() {
        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Anonymous"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `patch profile rejects invalid timezone and locale`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "profile-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "timezone": "Not/AZone",
                      "locale": "not_a_locale"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `me response includes configured default profile preferences`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "profile-default-${System.nanoTime()}@example.com")

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").doesNotExist())
            .andExpect(jsonPath("$.timezone").value("America/New_York"))
            .andExpect(jsonPath("$.locale").value("en-US"))
            .andExpect(jsonPath("$.onboardingWelcomeSeenAt").doesNotExist())

        val profileId = jdbcTemplate.queryForObject(
            "select id from user_profiles where user_id = ?",
            UUID::class.java,
            userId,
        )
        assertNotNull(profileId)

        val persistedProfile = jdbcTemplate.queryForMap(
            """
            select sex, birth_date, height_cm, current_weight_kg, target_weight_kg,
                   daily_movement_level, workout_frequency, goal_type
            from user_profiles
            where user_id = ?
            """.trimIndent(),
            userId,
        )
        assertNull(persistedProfile["sex"])
        assertNull(persistedProfile["birth_date"])
        assertNull(persistedProfile["height_cm"])
        assertNull(persistedProfile["current_weight_kg"])
        assertNull(persistedProfile["target_weight_kg"])
        assertNull(persistedProfile["daily_movement_level"])
        assertNull(persistedProfile["workout_frequency"])
        assertNull(persistedProfile["goal_type"])
    }

    @Test
    fun `onboarding welcome is acknowledged once and returned by me`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "onboarding-welcome-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/users/me/onboarding/welcome-seen")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isNoContent)

        val firstSeenAt = onboardingWelcomeSeenAt(userId)

        mockMvc.perform(
            post("/api/v1/users/me/onboarding/welcome-seen")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.onboardingWelcomeSeenAt").isNotEmpty)

        assertNotNull(firstSeenAt)
        assertEquals(firstSeenAt, onboardingWelcomeSeenAt(userId))
    }

    @Test
    fun `calculator rerun prompt acknowledgement is account wide and idempotent`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "calculator-rerun-prompt-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/users/me/coach/calculator-rerun-prompt/acknowledge")
                .with(authentication(testAuthentication(userId)))
        ).andExpect(status().isNoContent)

        val firstAcknowledgedAt = calculatorRerunPromptAcknowledgedAt(userId)

        mockMvc.perform(
            post("/api/v1/users/me/coach/calculator-rerun-prompt/acknowledge")
                .with(authentication(testAuthentication(userId)))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.calculatorRerunPromptAcknowledgedAt").isNotEmpty)

        assertNotNull(firstAcknowledgedAt)
        assertEquals(firstAcknowledgedAt, calculatorRerunPromptAcknowledgedAt(userId))
    }

    @Test
    fun `profile persists valid calculator planning fields through entity mapping`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "profile-calculator-${System.nanoTime()}@example.com")

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)

        val profile = requireNotNull(userProfileRepository.findByUser_Id(userId))
        profile.sex = GoalCalculatorSex.FEMALE
        profile.birthDate = LocalDate.of(1990, 4, 12)
        profile.heightCm = BigDecimal("172.50")
        profile.currentWeightKg = BigDecimal("78.250")
        profile.targetWeightKg = BigDecimal("72.000")
        profile.dailyMovementLevel = DailyMovementLevel.MODERATE
        profile.workoutFrequency = WorkoutFrequency.THREE_TO_FOUR_DAYS
        profile.goalType = GoalType.LOSE_WEIGHT
        userProfileRepository.saveAndFlush(profile)

        val persistedProfile = jdbcTemplate.queryForMap(
            """
            select sex, birth_date, height_cm, current_weight_kg, target_weight_kg,
                   daily_movement_level, workout_frequency, goal_type
            from user_profiles
            where user_id = ?
            """.trimIndent(),
            userId,
        )

        assertEquals("FEMALE", persistedProfile["sex"])
        assertEquals(LocalDate.of(1990, 4, 12), (persistedProfile["birth_date"] as java.sql.Date).toLocalDate())
        assertEquals("172.50", (persistedProfile["height_cm"] as BigDecimal).toPlainString())
        assertEquals("78.250", (persistedProfile["current_weight_kg"] as BigDecimal).toPlainString())
        assertEquals("72.000", (persistedProfile["target_weight_kg"] as BigDecimal).toPlainString())
        assertEquals("MODERATE", persistedProfile["daily_movement_level"])
        assertEquals("THREE_TO_FOUR_DAYS", persistedProfile["workout_frequency"])
        assertEquals("LOSE_WEIGHT", persistedProfile["goal_type"])
    }

    @Test
    fun `profile calculator schema rejects invalid enum and non-positive values`() {
        val invalidSexUserId = UUID.randomUUID()
        seedUser(invalidSexUserId, "profile-invalid-sex-${System.nanoTime()}@example.com")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into user_profiles (
                    user_id,
                    timezone,
                    locale,
                    sex,
                    created_at,
                    updated_at
                )
                values (?, 'Asia/Tehran', 'fa-IR', 'UNKNOWN', now(), now())
                """.trimIndent(),
                invalidSexUserId,
            )
        }

        val invalidHeightUserId = UUID.randomUUID()
        seedUser(invalidHeightUserId, "profile-invalid-height-${System.nanoTime()}@example.com")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into user_profiles (
                    user_id,
                    timezone,
                    locale,
                    height_cm,
                    current_weight_kg,
                    created_at,
                    updated_at
                )
                values (?, 'Asia/Tehran', 'fa-IR', 0, -1, now(), now())
                """.trimIndent(),
                invalidHeightUserId,
            )
        }
    }

    @Test
    fun `nutrition goal calculator snapshot schema rejects invalid ranges and warning arrays`() {
        val invalidRangeUserId = UUID.randomUUID()
        seedUser(invalidRangeUserId, "goal-invalid-range-${System.nanoTime()}@example.com")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into nutrition_plans (
                    user_id,
                    start_date,
                    timezone,
                    calories,
                    protein,
                    carbs,
                    fat,
                    maintenance_calories,
                    target_calories,
                    activity_factor
                )
                values (?, '2026-06-25', 'Asia/Tehran', 2200, 140, 220, 70, 50000, 50000, 9)
                """.trimIndent(),
                invalidRangeUserId,
            )
        }

        val invalidWarningsUserId = UUID.randomUUID()
        seedUser(invalidWarningsUserId, "goal-invalid-warnings-${System.nanoTime()}@example.com")

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into nutrition_plans (
                    user_id,
                    start_date,
                    timezone,
                    calories,
                    protein,
                    carbs,
                    fat,
                    safety_warning_codes
                )
                values (?, '2026-06-25', 'Asia/Tehran', 2200, 140, 220, 70, array['LOW_CALORIE', ''])
                """.trimIndent(),
                invalidWarningsUserId,
            )
        }
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
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
        )
    }

    private fun onboardingWelcomeSeenAt(userId: UUID) = jdbcTemplate.queryForObject(
        "select onboarding_welcome_seen_at from users where id = ?",
        java.time.Instant::class.java,
        userId,
    )

    private fun calculatorRerunPromptAcknowledgedAt(userId: UUID) = jdbcTemplate.queryForObject(
        "select calculator_rerun_prompt_acknowledged_at from users where id = ?",
        java.time.Instant::class.java,
        userId,
    )

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
