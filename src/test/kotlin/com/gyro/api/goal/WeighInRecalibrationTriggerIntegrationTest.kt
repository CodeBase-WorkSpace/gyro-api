package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.weight.application.SaveWeightEntryCommand
import com.gyro.api.weight.application.WeightEntryService
import com.gyro.api.weight.domain.WeightUnit
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mockito's any() returns null, which Kotlin rejects for a non-null parameter. */
private fun <T> anyNotificationRequest(): T = Mockito.any()

/**
 * The listener is deliberately not `fallbackExecution`, so publishing the event directly
 * would not fire it. Every case here goes through [WeightEntryService], which is the only
 * way to get a real committed transaction and therefore a real AFTER_COMMIT callback.
 */
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        // The sweep would otherwise race these assertions and create the same rows.
        "app.goal-recalibration.jobs-enabled=false",
        "app.goal-recalibration.trigger-on-weigh-in-enabled=true",
    ],
)
class WeighInRecalibrationTriggerIntegrationTest(
    @Autowired private val weightEntryService: WeightEntryService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @MockitoSpyBean
    private lateinit var notificationService: NotificationService

    @Test
    fun `a notification failure does not take the suggestion with it`() {
        // The suggestion commits inside evaluateAndSuggest (REQUIRES_NEW) before the push
        // is attempted. If the two shared a transaction, an exception here would mark it
        // rollback-only and the commit would silently discard a valid suggestion — the
        // caller sees no error, and the cron cannot repair it either.
        val userId = seedEntitledUserOneWeighInShort()
        Mockito.doThrow(IllegalStateException("notification backend down"))
            .`when`(notificationService).create(anyNotificationRequest())

        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now(), BigDecimal("89.000"), WeightUnit.KG),
        )

        assertEquals(1, suggestionCount(userId), "the suggestion must survive a failed push")
        assertEquals(0, notificationCount(userId))
        assertEquals(
            3,
            jdbcTemplate.queryForObject(
                "select count(*) from weight_entries where user_id = ?", Int::class.java, userId,
            ),
            "the weigh-in itself must be untouched",
        )
    }

    @Test
    fun `the weigh-in that crosses the gate produces a suggestion immediately`() {
        val userId = seedEntitledUserOneWeighInShort()

        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(
                recordedDate = LocalDate.now(),
                weight = BigDecimal("89.000"),
                unit = WeightUnit.KG,
            ),
        )

        // Also the regression test for the window bound: under the previous
        // `recorded_date < today`, today's weigh-in was invisible to the evaluation it
        // triggered, and this would find nothing.
        assertEquals(1, suggestionCount(userId))
        assertEquals(1, notificationCount(userId))
    }

    @Test
    fun `a batch import evaluates once, not once per entry`() {
        val userId = seedEntitledUserOneWeighInShort()

        // WeightEntryService publishes a single event after the loop. If that ever moves
        // inside it, this catches the fan-out even though the unique index would mask it.
        weightEntryService.saveEntriesBatch(
            userId,
            batchOf(
                LocalDate.now() to "89.000",
                LocalDate.now().minusDays(1) to "89.100",
                LocalDate.now().minusDays(2) to "89.200",
            ),
        )

        assertEquals(1, suggestionCount(userId))
        assertEquals(1, notificationCount(userId))
    }

    @Test
    fun `a user without the entitlement gets nothing`() {
        val userId = seedEntitledUserOneWeighInShort(entitled = false)

        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now(), BigDecimal("89.000"), WeightUnit.KG),
        )

        // Creating one anyway would burn the 7-day interval for a user who cannot act on it.
        assertEquals(0, suggestionCount(userId))
    }

    @Test
    fun `a weigh-in inside the minimum interval does not produce a second suggestion`() {
        val userId = seedEntitledUserOneWeighInShort()
        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now(), BigDecimal("89.000"), WeightUnit.KG),
        )
        assertEquals(1, suggestionCount(userId))

        jdbcTemplate.update(
            "update plan_recalibration_suggestions set status = 'DISMISSED', decided_at = now() where user_id = ?",
            userId,
        )
        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now().minusDays(1), BigDecimal("89.050"), WeightUnit.KG),
        )

        assertEquals(1, suggestionCount(userId))
    }

    @Test
    fun `the weigh-in still persists when the trigger cannot run`() {
        // No active plan, so the evaluation bails. The write must be unaffected: an
        // AFTER_COMMIT failure cannot roll it back, and surfacing one would report a
        // failure for a row that is already committed.
        val userId = UUID.randomUUID()
        seedUser(userId)

        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now(), BigDecimal("77.000"), WeightUnit.KG),
        )

        val stored = jdbcTemplate.queryForObject(
            "select count(*) from weight_entries where user_id = ?", Int::class.java, userId,
        )
        assertEquals(1, stored)
        assertEquals(0, suggestionCount(userId))
    }

    private fun batchOf(vararg entries: Pair<LocalDate, String>) =
        com.gyro.api.weight.application.SaveWeightEntriesBatchCommand(
            entries = entries.mapIndexed { index, (date, weight) ->
                com.gyro.api.weight.application.SaveWeightEntryBatchItemCommand(
                    clientEntryId = "batch-$index",
                    recordedDate = date,
                    weight = BigDecimal(weight),
                    unit = WeightUnit.KG,
                    source = com.gyro.api.weight.domain.WeightEntrySource.IMPORT,
                    notes = null,
                    recordedAt = null,
                )
            },
        )

    /**
     * Two weeks of diary plus four weigh-in days spanning 12 — everything the LOW tier
     * needs except that the span and count only clear once today's weigh-in lands.
     */
    private fun seedEntitledUserOneWeighInShort(entitled: Boolean = true): UUID {
        val userId = UUID.randomUUID()
        seedUser(userId)
        jdbcTemplate.update(
            """
            insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat, daily_energy_delta, daily_energy_delta_source)
            values (?, ?, 'Asia/Tehran', 1800, 140, 200, 60, -500, 'FORMULA_WIZARD')
            """.trimIndent(),
            userId, LocalDate.now().minusDays(30),
        )
        if (entitled) {
            jdbcTemplate.update(
                """
                insert into manual_grants (user_id, plan_id, expires_at, reason, granted_by)
                values (?, (select id from subscription_plans where code = 'ADVANCED'), now() + interval '30 days', 'CUSTOM', ?)
                """.trimIndent(),
                userId, userId,
            )
        }
        val today = LocalDate.now()
        (1..14).forEach { daysAgo -> seedDiaryDay(userId, today.minusDays(daysAgo.toLong())) }
        // Two prior weigh-in days: with today's, three distinct days spanning 12.
        listOf(12L, 6L).forEach { daysAgo ->
            jdbcTemplate.update(
                "insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source) values (?, ?, 90, 90, 'KG', 'MANUAL')",
                userId, today.minusDays(daysAgo),
            )
        }
        return userId
    }

    private fun suggestionCount(userId: UUID) = jdbcTemplate.queryForObject(
        "select count(*) from plan_recalibration_suggestions where user_id = ?", Int::class.java, userId,
    )

    private fun notificationCount(userId: UUID) = jdbcTemplate.queryForObject(
        "select count(*) from notification_intents where user_id = ? and source_type = 'RECALIBRATION_SUGGESTION'",
        Int::class.java, userId,
    )

    private fun seedUser(id: UUID) {
        jdbcTemplate.update(
            """
            insert into users (id, email, password_hash, role, email_verification_status,
                               phone_verification_status, status, created_at, updated_at)
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id, "weighin-trigger-${System.nanoTime()}@example.com",
        )
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

    @Test
    fun `the sweep and the listener cannot disagree about entitlement or notification`() {
        // Both paths go through RecalibrationTrigger, so this asserts the shared shape
        // rather than duplicating the job's own coverage.
        val userId = seedEntitledUserOneWeighInShort()
        weightEntryService.saveEntry(
            userId,
            SaveWeightEntryCommand(LocalDate.now(), BigDecimal("89.000"), WeightUnit.KG),
        )

        val notified = jdbcTemplate.queryForObject(
            "select idempotency_key from notification_intents where user_id = ? and source_type = 'RECALIBRATION_SUGGESTION'",
            String::class.java, userId,
        )
        val suggestionId = jdbcTemplate.queryForObject(
            "select id from plan_recalibration_suggestions where user_id = ?", UUID::class.java, userId,
        )
        assertTrue(notified == "recalibration:$suggestionId", "expected key derived from the suggestion id, got $notified")
    }
}
