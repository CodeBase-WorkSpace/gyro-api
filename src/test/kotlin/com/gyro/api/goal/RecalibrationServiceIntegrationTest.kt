package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.goal.application.recalibration.RecalibrationService
import com.gyro.api.goal.domain.RecalibrationDismissReason
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-recalibration.jobs-enabled=false",
    ],
)
class RecalibrationServiceIntegrationTest(
    @Autowired private val recalibrationService: RecalibrationService,
    @Autowired private val nutritionPlanRepository: NutritionPlanRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private lateinit var userId: UUID
    private lateinit var planId: UUID

    @BeforeEach
    fun setUp() {
        userId = UUID.randomUUID()
        seedUser(userId)
        planId = seedGoal(userId)
        // Two weeks of flat weight while eating at target: the engine will
        // suggest lowering by the 200 kcal clamp.
        val today = LocalDate.now()
        (1..14).forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            jdbcTemplate.update(
                "insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source) values (?, ?, 90, 90, 'KG', 'MANUAL')",
                userId, date,
            )
            seedDiaryDay(userId, date)
        }
    }

    @Test
    fun `a steadily losing user is told to eat more, through the whole pipeline`() {
        // The scenario the estimator change exists for, exercised end to end: real date
        // loading, real windowing, real persistence — not just the engine's arithmetic.
        //
        // Eating 1800 while losing 0.079 kg/day means maintenance is about 2408, so the
        // plan's -500 wants 1908. Endpoint-differencing an EMA read the rate as roughly
        // -0.044, concluded maintenance was 2139, and suggested cutting to 1639. This
        // asserts the direction, because getting it backwards is the actual harm.
        val fasterLoser = UUID.randomUUID()
        seedUser(fasterLoser)
        seedGoal(fasterLoser)
        val today = LocalDate.now()
        (1..14).forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            // daysAgo 14 is the oldest, so weight falls as daysAgo shrinks.
            val weight = BigDecimal.valueOf(90.0 - 0.079 * (14 - daysAgo))
            jdbcTemplate.update(
                "insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source) values (?, ?, ?, ?, 'KG', 'MANUAL')",
                fasterLoser, date, weight, weight,
            )
            seedDiaryDay(fasterLoser, date)
        }

        val suggestion = requireNotNull(recalibrationService.evaluateAndSuggest(fasterLoser))

        assertTrue(
            suggestion.suggestedCalories > suggestion.previousCalories,
            "expected an increase, got ${suggestion.previousCalories} -> ${suggestion.suggestedCalories}",
        )
        assertEquals(0, BigDecimal("1908.30").compareTo(suggestion.suggestedCalories))
        assertEquals("OLS", suggestion.basis["trendMethod"])
        assertEquals("HIGH", suggestion.basis["confidence"])
        assertEquals("-0.553", suggestion.basis["observedKgPerWeek"])
        assertEquals(today.minusDays(14).toString(), suggestion.basis["windowStart"])
        assertEquals(today.minusDays(1).toString(), suggestion.basis["intakeThrough"])
        assertEquals(today.toString(), suggestion.basis["weightThrough"])
        assertEquals("1800.00", suggestion.basis["averageHistoricalTargetCalories"])
    }

    @Test
    fun `suggestion is created once and accepting updates the plan in place`() {
        val suggestion = recalibrationService.evaluateAndSuggest(userId)
        assertNotNull(suggestion)
        assertEquals(0, BigDecimal("1600.00").compareTo(suggestion!!.suggestedCalories))
        assertEquals(RecalibrationSuggestionStatus.PENDING, suggestion.status)

        // Second run is a no-op while a suggestion is pending.
        assertNull(recalibrationService.evaluateAndSuggest(userId))

        val decision = recalibrationService.accept(userId, requireNotNull(suggestion.id))
        assertTrue(decision.applied)
        assertEquals(RecalibrationSuggestionStatus.ACCEPTED, decision.suggestion.status)

        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        assertEquals(0, BigDecimal("1600.00").compareTo(plan.calories))
    }

    @Test
    fun `accept marks the suggestion superseded when the plan changed underneath`() {
        val suggestion = requireNotNull(recalibrationService.evaluateAndSuggest(userId))

        jdbcTemplate.update("update nutrition_plans set calories = 2000 where id = ?", planId)

        val decision = recalibrationService.accept(userId, requireNotNull(suggestion.id))
        assertFalse(decision.applied)
        assertEquals(RecalibrationSuggestionStatus.SUPERSEDED, decision.suggestion.status)

        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        assertEquals(0, BigDecimal("2000").compareTo(plan.calories))
    }

    @Test
    fun `dismiss closes the suggestion without touching the plan`() {
        val suggestion = requireNotNull(recalibrationService.evaluateAndSuggest(userId))

        val dismissed = recalibrationService.dismiss(userId, requireNotNull(suggestion.id))
        assertEquals(RecalibrationSuggestionStatus.DISMISSED, dismissed.status)
        assertNull(dismissed.dismissReason)
        assertNull(recalibrationService.pendingFor(userId))

        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        assertEquals(0, BigDecimal("1800.00").compareTo(plan.calories))
    }

    @Test
    fun `dismiss persists an optional reason outside the engine basis`() {
        val suggestion = requireNotNull(recalibrationService.evaluateAndSuggest(userId))

        val dismissed = recalibrationService.dismiss(
            userId,
            requireNotNull(suggestion.id),
            RecalibrationDismissReason.DATA_IS_WRONG,
        )

        assertEquals(RecalibrationDismissReason.DATA_IS_WRONG, dismissed.dismissReason)
        assertEquals(
            "DATA_IS_WRONG",
            jdbcTemplate.queryForObject(
                "select dismiss_reason from plan_recalibration_suggestions where id = ?",
                String::class.java,
                suggestion.id,
            ),
        )
        assertFalse(dismissed.basis.containsKey("dismissReason"))
    }

    @Test
    fun `expired suggestion cannot be accepted through a stale client`() {
        val suggestion = requireNotNull(recalibrationService.evaluateAndSuggest(userId))
        jdbcTemplate.update(
            "update plan_recalibration_suggestions set expires_at = now() - interval '1 minute' where id = ?",
            suggestion.id,
        )

        assertThrows(ResourceNotFoundException::class.java) {
            recalibrationService.accept(userId, requireNotNull(suggestion.id))
        }

        val plan = nutritionPlanRepository.findById(planId).orElseThrow()
        assertEquals(0, BigDecimal("1800.00").compareTo(plan.calories))
    }

    @Test
    fun `concurrent accepts produce exactly one applied decision`() {
        val suggestionId = requireNotNull(recalibrationService.evaluateAndSuggest(userId)?.id)

        val results = runConcurrently(
            { if (recalibrationService.accept(userId, suggestionId).applied) "ACCEPTED" else "NOT_APPLIED" },
            { if (recalibrationService.accept(userId, suggestionId).applied) "ACCEPTED" else "NOT_APPLIED" },
        )

        assertEquals(1, results.count { it.getOrNull() == "ACCEPTED" })
        assertEquals(1, results.count { it.exceptionOrNull() is ResourceNotFoundException })
        assertEquals("ACCEPTED", suggestionStatus(suggestionId))
    }

    @Test
    fun `concurrent accept and dismiss leave status aligned with plan mutation`() {
        val suggestionId = requireNotNull(recalibrationService.evaluateAndSuggest(userId)?.id)

        val results = runConcurrently(
            {
                recalibrationService.accept(userId, suggestionId)
                "ACCEPTED"
            },
            {
                recalibrationService.dismiss(userId, suggestionId)
                "DISMISSED"
            },
        )

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is ResourceNotFoundException })
        val finalStatus = suggestionStatus(suggestionId)
        val finalCalories = nutritionPlanRepository.findById(planId).orElseThrow().calories
        when (finalStatus) {
            "ACCEPTED" -> assertEquals(0, BigDecimal("1600.00").compareTo(finalCalories))
            "DISMISSED" -> assertEquals(0, BigDecimal("1800.00").compareTo(finalCalories))
            else -> error("Unexpected final status: $finalStatus")
        }
    }

    @Test
    fun `concurrent expiration prevents acceptance and preserves the plan`() {
        val suggestionId = requireNotNull(recalibrationService.evaluateAndSuggest(userId)?.id)
        jdbcTemplate.update(
            "update plan_recalibration_suggestions set expires_at = now() - interval '1 minute' where id = ?",
            suggestionId,
        )

        val results = runConcurrently(
            {
                recalibrationService.accept(userId, suggestionId)
                "ACCEPTED"
            },
            {
                recalibrationService.expireStalePending()
                "EXPIRED"
            },
        )

        assertEquals(1, results.count { it.getOrNull() == "EXPIRED" })
        assertEquals(1, results.count { it.exceptionOrNull() is ResourceNotFoundException })
        assertEquals("EXPIRED", suggestionStatus(suggestionId))
        val finalCalories = nutritionPlanRepository.findById(planId).orElseThrow().calories
        assertEquals(0, BigDecimal("1800.00").compareTo(finalCalories))
    }

    @Test
    fun `evaluation expires stale pending row before creating its replacement`() {
        val staleId = requireNotNull(recalibrationService.evaluateAndSuggest(userId)?.id)
        jdbcTemplate.update(
            """
            update plan_recalibration_suggestions
            set expires_at = now() - interval '1 minute',
                created_at = now() - interval '15 days'
            where id = ?
            """.trimIndent(),
            staleId,
        )

        val replacement = recalibrationService.evaluateAndSuggest(userId)

        assertNotNull(replacement)
        assertEquals("EXPIRED", suggestionStatus(staleId))
        assertEquals(RecalibrationSuggestionStatus.PENDING, replacement?.status)
    }

    @Test
    fun `plans without a calculator delta are never recalibrated`() {
        jdbcTemplate.update("update nutrition_plans set daily_energy_delta = null where id = ?", planId)
        assertNull(recalibrationService.evaluateAndSuggest(userId))
    }

    private fun runConcurrently(vararg actions: () -> String): List<Result<String>> {
        val barrier = CyclicBarrier(actions.size)
        val executor = Executors.newFixedThreadPool(actions.size)
        return try {
            val futures = actions.map { action ->
                executor.submit<Result<String>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching(action)
                }
            }
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun suggestionStatus(suggestionId: UUID): String = requireNotNull(
        jdbcTemplate.queryForObject(
            "select status from plan_recalibration_suggestions where id = ?",
            String::class.java,
            suggestionId,
        ),
    )

    private fun seedUser(id: UUID) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "recal-${System.nanoTime()}@example.com",
        )
    }

    private fun seedGoal(userId: UUID): UUID {
        return jdbcTemplate.queryForObject(
            """
            insert into nutrition_plans (
                user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source
            )
            values (?, ?, 'Asia/Tehran', 1800, 140, 200, 60, -500, 'FORMULA_WIZARD')
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
            LocalDate.now().minusDays(30),
        ) ?: error("Expected inserted nutrition plan id")
    }

    private fun seedDiaryDay(userId: UUID, date: LocalDate) {
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at) values (?, ?, ?, 'Asia/Tehran', now(), now())",
            dayId, userId, date,
        )
        jdbcTemplate.update(
            """
            insert into diary_entries (
                id, diary_day_id, user_id, diary_date, meal_type, source_type,
                display_name_snapshot, serving_quantity_snapshot,
                serving_unit_code_snapshot, serving_unit_name_snapshot,
                calories_snapshot, protein_snapshot, carbs_snapshot, fat_snapshot
            )
            values (?, ?, ?, ?, 'LUNCH', 'MANUAL', 'test meal', 1, 'SERVING', 'serving', 1800, 100, 150, 50)
            """.trimIndent(),
            UUID.randomUUID(), dayId, userId, date,
        )
    }
}
