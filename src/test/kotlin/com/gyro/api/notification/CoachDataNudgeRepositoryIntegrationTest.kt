package com.gyro.api.notification

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.notification.infrastructure.persistence.CoachDataNudgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.notification.jobs-enabled=false",
    ],
)
@Transactional
class CoachDataNudgeRepositoryIntegrationTest(
    @Autowired private val repository: CoachDataNudgeRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `candidate page contains only entitled users whose active plan is on readiness days seven through thirteen`() {
        val evaluatedAt = Instant.parse("2026-07-31T14:30:00Z")
        val localDate = LocalDate.parse("2026-07-31")
        val eligible = user("ffffffff-ffff-ffff-ffff-fffffffffff1")
        val invalidTimezoneFallback = user("ffffffff-ffff-ffff-ffff-fffffffffff2")
        val tooYoung = user("ffffffff-ffff-ffff-ffff-fffffffffff3")
        val tooMature = user("ffffffff-ffff-ffff-ffff-fffffffffff4")
        val replacedPlan = user("ffffffff-ffff-ffff-ffff-fffffffffff5")
        val wrongLocalHour = user("ffffffff-ffff-ffff-ffff-fffffffffff6")

        seedCandidate(eligible, "Asia/Tehran", localDate.minusDays(7))
        seedCandidate(invalidTimezoneFallback, "not/a-timezone", localDate.minusDays(13))
        seedCandidate(tooYoung, "Asia/Tehran", localDate.minusDays(6))
        seedCandidate(tooMature, "Asia/Tehran", localDate.minusDays(14))
        seedCandidate(replacedPlan, "Asia/Tehran", localDate.minusDays(7))
        seedPlan(replacedPlan, localDate.minusDays(2), dailyEnergyDelta = null)
        seedCandidate(wrongLocalHour, "America/New_York", localDate.minusDays(7))

        val result = repository.findEligiblePageAtLocalHour(
            afterUserId = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0"),
            batchSize = 20,
            localHour = 18,
            evaluatedAt = evaluatedAt,
        )

        assertEquals(listOf(eligible, invalidTimezoneFallback), result)
    }

    private fun seedCandidate(userId: UUID, timezone: String, startDate: LocalDate) {
        jdbc.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            ) values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "coach-candidate-$userId@example.com",
        )
        jdbc.update(
            "insert into user_profiles (user_id, timezone, locale, created_at, updated_at) values (?, ?, 'fa', now(), now())",
            userId,
            timezone,
        )
        seedPlan(userId, startDate, dailyEnergyDelta = -500)
        val advancedPlanId = requireNotNull(
            jdbc.queryForObject("select id from subscription_plans where code = 'ADVANCED'", Long::class.java),
        )
        jdbc.update(
            """
            insert into manual_grants (user_id, plan_id, expires_at, reason, granted_by)
            values (?, ?, ?::timestamptz + interval '1 day', 'CUSTOM', ?)
            """.trimIndent(),
            userId,
            advancedPlanId,
            "2026-07-31T14:30:00Z",
            userId,
        )
    }

    private fun seedPlan(userId: UUID, startDate: LocalDate, dailyEnergyDelta: Int?) {
        jdbc.update(
            """
            insert into nutrition_plans (
                user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source
            ) values (?, ?, 'Asia/Tehran', 1800, 140, 200, 60, ?, ?)
            """.trimIndent(),
            userId,
            startDate,
            dailyEnergyDelta,
            dailyEnergyDelta?.let { "FORMULA_WIZARD" },
        )
    }

    private fun user(value: String): UUID = UUID.fromString(value)
}
