package com.gyro.api.notification

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.notification.application.CoachDataNudgeOutcome
import com.gyro.api.notification.application.CoachDataReadinessNotificationService
import com.gyro.api.notification.application.NotificationPreferenceService
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.PushSubscriptionCommand
import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationReason
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.notification.domain.UpdateNotificationPreferencesCommand
import com.gyro.api.notification.infrastructure.persistence.CoachDataNudgeRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Import(
    TestcontainersConfiguration::class,
    CoachDataReadinessNotificationIntegrationTest.MutableClockConfiguration::class,
)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.notification.jobs-enabled=false",
        "app.notification.web-push-allowed-endpoint-hosts=push.example.test",
    ],
)
@Transactional
class CoachDataReadinessNotificationIntegrationTest(
    @Autowired private val coach: CoachDataReadinessNotificationService,
    @Autowired private val notifications: NotificationService,
    @Autowired private val preferences: NotificationPreferenceService,
    @Autowired private val pushSubscriptions: PushSubscriptionService,
    @Autowired private val coachRepository: CoachDataNudgeRepository,
    @Autowired private val intents: NotificationIntentRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableTestClock,
) {
    @Test
    fun `budget suppressed Coach nudge retries next day and only routed intent consumes the evidence period`() {
        clock.set(INITIAL_INSTANT)
        val userId = UUID.randomUUID()
        val localToday = INITIAL_INSTANT.atZone(TEHRAN).toLocalDate()
        val planStart = localToday.minusDays(7)
        val planId = seedEligibleUser(userId, planStart)
        val evidencePeriod = "$planId:$planStart"

        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(
                quietHoursStart = LocalTime.MIDNIGHT,
                quietHoursEnd = LocalTime.of(0, 1),
                categories = mapOf(NotificationCategory.OPTIONAL_ANNOUNCEMENTS to true),
            ),
        )
        pushSubscriptions.subscribe(
            userId,
            PushSubscriptionCommand("https://push.example.test/$userId", "p256dh", "auth"),
        )
        occupyDailyAnnouncementBudget(userId)

        assertEquals(CoachDataNudgeOutcome.BUDGET_SUPPRESSED, coach.evaluate(userId))
        assertFalse(coachRepository.hasNudgeForEvidencePeriod(userId, evidencePeriod))
        val suppressed = periodIntents(userId, evidencePeriod).single()
        assertEquals(NotificationIntentStatus.SUPPRESSED, suppressed.status)
        assertEquals(NotificationReason.BUDGET_LIMIT, suppressed.reason)
        assertEquals(CoachDataNudgeOutcome.BUDGET_SUPPRESSED, coach.evaluate(userId))
        assertEquals(1, periodIntents(userId, evidencePeriod).size)

        clock.advance(Duration.ofDays(1))

        assertEquals(CoachDataNudgeOutcome.CREATED, coach.evaluate(userId))
        assertTrue(coachRepository.hasNudgeForEvidencePeriod(userId, evidencePeriod))
        val attempts = periodIntents(userId, evidencePeriod)
        assertEquals(2, attempts.size)
        assertEquals(1, attempts.count { it.status == NotificationIntentStatus.ROUTED })
        assertEquals(1, attempts.count { it.status == NotificationIntentStatus.SUPPRESSED })
    }

    private fun seedEligibleUser(userId: UUID, planStart: LocalDate): UUID {
        jdbc.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            ) values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "coach-budget-$userId@example.com",
        )
        jdbc.update(
            "insert into user_profiles (user_id, timezone, locale, created_at, updated_at) values (?, 'Asia/Tehran', 'fa', now(), now())",
            userId,
        )
        val planId = requireNotNull(
            jdbc.queryForObject(
                """
                insert into nutrition_plans (
                    user_id, start_date, timezone, calories, protein, carbs, fat,
                    daily_energy_delta, daily_energy_delta_source, created_at, updated_at
                ) values (?, ?, 'Asia/Tehran', 1500, 100, 150, 55, -500, 'FORMULA_WIZARD', now(), now())
                returning id
                """.trimIndent(),
                UUID::class.java,
                userId,
                planStart,
            ),
        )
        val advancedPlanId = requireNotNull(
            jdbc.queryForObject("select id from subscription_plans where code = 'ADVANCED'", Long::class.java),
        )
        jdbc.update(
            """
            insert into manual_grants (user_id, plan_id, expires_at, reason, granted_by)
            values (?, ?, ?::timestamptz + interval '30 days', 'CUSTOM', ?)
            """.trimIndent(),
            userId,
            advancedPlanId,
            INITIAL_INSTANT.toString(),
            userId,
        )
        repeat(3) { offset -> seedDiaryEntry(userId, planStart.plusDays(offset.toLong())) }
        listOf(planStart, planStart.plusDays(3), planStart.plusDays(7)).forEach { date ->
            jdbc.update(
                """
                insert into weight_entries (
                    id, user_id, recorded_date, recorded_at, weight_kg, display_weight,
                    display_unit, source, created_at, updated_at
                ) values (?, ?, ?, ?::timestamptz, 80, 80, 'KG', 'MANUAL', now(), now())
                """.trimIndent(),
                UUID.randomUUID(),
                userId,
                date,
                date.atStartOfDay(TEHRAN).toInstant().toString(),
            )
        }
        return planId
    }

    private fun seedDiaryEntry(userId: UUID, date: LocalDate) {
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
                source_metadata, display_name_snapshot, serving_quantity_snapshot,
                serving_unit_code_snapshot, serving_unit_name_snapshot, calories_snapshot,
                protein_snapshot, carbs_snapshot, fat_snapshot, fiber_snapshot,
                sugar_snapshot, sodium_snapshot, sort_order, created_at, updated_at
            ) values (?, ?, ?, ?, 'LUNCH', 'MANUAL', '{}'::jsonb, 'test', 1,
                'SERVING', 'serving', ?, 0, 0, 0, 0, 0, 0, 0, now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            dayId,
            userId,
            date,
            BigDecimal("1500"),
        )
    }

    private fun occupyDailyAnnouncementBudget(userId: UUID) {
        val now = clock.instant()
        val result = notifications.createWithOutcome(
            NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.COACH_DATA_NUDGE,
                templateData = mapOf("body" to TemplateVariableValue.Text("یک پیام آزمایشی عمومی")),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofHours(12)),
                idempotencyKey = "budget-occupant:$userId",
                requestId = "budget-occupant-$userId",
                sourceType = "INTEGRATION_TEST",
                sourceReference = "budget-occupant:$userId",
            ),
        )
        assertEquals(NotificationIntentStatus.ROUTED, result.status)
    }

    private fun periodIntents(userId: UUID, evidencePeriod: String) =
        intents.findAllByUserId(userId).filter {
            it.type == NotificationType.COACH_DATA_NUDGE && it.sourceReference == evidencePeriod
        }

    @TestConfiguration
    class MutableClockConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableTestClock = MutableTestClock(INITIAL_INSTANT)
    }

    class MutableTestClock(initialInstant: Instant) : Clock() {
        private val current = AtomicReference(initialInstant)

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current.get(), zone)

        override fun instant(): Instant = current.get()

        fun set(instant: Instant) {
            current.set(instant)
        }

        fun advance(duration: Duration) {
            current.updateAndGet { it.plus(duration) }
        }
    }

    private companion object {
        val INITIAL_INSTANT: Instant = Instant.parse("2026-07-31T14:30:00Z")
        val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")
    }
}
