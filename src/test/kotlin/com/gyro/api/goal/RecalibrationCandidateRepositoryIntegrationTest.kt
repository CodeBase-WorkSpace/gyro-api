package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.goal.infrastructure.RecalibrationCandidateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-recalibration.jobs-enabled=false",
    ],
)
@Transactional
class RecalibrationCandidateRepositoryIntegrationTest(
    @Autowired private val candidateRepository: RecalibrationCandidateRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `keyset pages reach every candidate beyond the configured batch size`() {
        val lowerBound = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0")
        val users = (1..5).map { offset ->
            UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff$offset")
        }
        val advancedPlanId = requireNotNull(
            jdbcTemplate.queryForObject(
                "select id from subscription_plans where code = 'ADVANCED'",
                Long::class.java,
            ),
        )
        users.forEach(::seedCandidate)
        users.forEach { userId ->
            jdbcTemplate.update(
                """
                insert into manual_grants (user_id, plan_id, expires_at, reason, granted_by)
                values (?, ?, now() + interval '1 day', 'CUSTOM', ?)
                """.trimIndent(),
                userId,
                advancedPlanId,
                userId,
            )
        }

        val seen = mutableListOf<UUID>()
        var cursor: UUID? = lowerBound
        while (true) {
            val page = candidateRepository.findEligiblePage(cursor, batchSize = 2)
            assertTrue(page.size <= 2)
            if (page.isEmpty()) break
            seen += page
            cursor = page.last()
        }

        assertEquals(users, seen)
    }

    private fun seedCandidate(userId: UUID) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "candidate-$userId@example.com",
        )
        jdbcTemplate.update(
            """
            insert into nutrition_plans (
                user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source
            )
            values (?, ?, 'Asia/Tehran', 1800, 140, 200, 60, -500, 'FORMULA_WIZARD')
            """.trimIndent(),
            userId,
            LocalDate.now().minusDays(1),
        )
    }
}
