package com.gyro.api.notification

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.auth.infrastructure.AccountAuditEventRepository
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.NotificationPreferenceService
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.FoodReminderEvaluator
import com.gyro.api.notification.application.FoodReminderScheduleProcessor
import com.gyro.api.notification.application.PushSubscriptionCommand
import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.application.FoodReminderMealType
import com.gyro.api.notification.application.FoodReminderScheduleState
import com.gyro.api.notification.application.FoodReminderScheduleType
import com.gyro.api.notification.application.NotificationEndpointEligibilityService
import com.gyro.api.notification.application.NotificationEndpointHealthCleanupService
import com.gyro.api.notification.application.delivery.NotificationDeliveryWorker
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationEndpointHealthRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleClaim
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationPushSubscriptionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class, NotificationAdapterTestConfiguration::class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "app.security.verification-code-pepper=test-pepper",
    "app.rate-limit.enabled=false",
    "app.billing.lifecycle.jobs-enabled=false",
    "app.notification.jobs-enabled=false",
    "app.notification.worker-identity=notification-eligibility-test",
    "app.notification.web-push-allowed-endpoint-hosts=push.example.test",
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
])
class NotificationEligibilityIntegrationTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val intents: NotificationIntentRepository,
    @Autowired private val deliveries: NotificationDeliveryRepository,
    @Autowired private val worker: NotificationDeliveryWorker,
    @Autowired private val preferences: NotificationPreferenceService,
    @Autowired private val endpointEligibility: NotificationEndpointEligibilityService,
    @Autowired private val endpointHealth: NotificationEndpointHealthRepository,
    @Autowired private val cleanup: NotificationEndpointHealthCleanupService,
    @Autowired private val adapter: ControlledNotificationAdapter,
    @Autowired private val time: TimeProvider,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val foodReminderEvaluator: FoodReminderEvaluator,
    @Autowired private val schedules: NotificationScheduleRepository,
    @Autowired private val pushSubscriptions: PushSubscriptionService,
    @Autowired private val pushSubscriptionRepository: NotificationPushSubscriptionRepository,
    @Autowired private val foodReminderProcessor: FoodReminderScheduleProcessor,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val auditEvents: AccountAuditEventRepository,
) {
    private val users = mutableSetOf<UUID>()

    @AfterEach
    fun cleanup() {
        adapter.reset()
        users.forEach { userId -> jdbc.update("delete from account_audit_events where target_user_id = ? or actor_user_id = ?", userId, userId) }
        users.forEach { userId -> jdbc.update("delete from notification_intents where user_id = ?", userId) }
        users.forEach { userId -> jdbc.update("delete from users where id = ?", userId) }
    }

    @Test
    fun `optional notifications require an explicit enabled preference`() {
        val userId = createUser()
        val deliveryId = createDelivery(userId, "account:email")

        assertEquals(1, worker.processBatch())

        val delivery = deliveries.findById(deliveryId).orElseThrow()
        assertEquals(NotificationDeliveryStatus.SUPPRESSED, delivery.status)
        assertEquals(NotificationReason.PREFERENCE_DISABLED, delivery.reason)
        assertEquals(0, adapter.providerDeliveries.get())
    }

    @Test
    fun `announcements deliver by default and are suppressed after opt-out`() {
        val userId = createUser()
        val localNow = time.now().atZone(ZoneId.of("Asia/Tehran")).toLocalTime()
        val quietStart = localNow.plusHours(1)
        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(quietStart, quietStart.plusMinutes(1), emptyMap()),
        )
        val defaultOn = createDelivery(userId, "account:email", category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS)

        assertEquals(1, worker.processBatch())
        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(defaultOn).orElseThrow().status)
        assertEquals(1, adapter.providerDeliveries.get())

        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_ANNOUNCEMENTS to false)),
        )
        val optedOut = createDelivery(userId, "account:email", category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS)

        assertEquals(1, worker.processBatch())
        val suppressed = deliveries.findById(optedOut).orElseThrow()
        assertEquals(NotificationDeliveryStatus.SUPPRESSED, suppressed.status)
        assertEquals(NotificationReason.PREFERENCE_DISABLED, suppressed.reason)
        assertEquals(1, adapter.providerDeliveries.get())
    }

    @Test
    fun `invalid food reminder timezone is disabled and cannot become due again`() {
        val userId = createUser()
        jdbc.update("update user_profiles set timezone = 'not/a-timezone' where user_id = ?", userId)
        val now = time.now()
        val schedule = schedules.saveAndFlush(
            NotificationScheduleEntity(
                userId = userId,
                scheduleType = FoodReminderScheduleType.MEAL_REMINDER,
                mealType = FoodReminderMealType.LUNCH,
                localTime = LocalTime.NOON,
                daysOfWeek = intArrayOf(1, 2, 3, 4, 5, 6, 7),
                enabled = true,
                state = FoodReminderScheduleState.ACTIVE,
                timezoneSource = "PROFILE",
                consentedAt = now,
                actorUserId = userId,
                nextEvaluationAt = now.minusSeconds(1),
            ),
        )

        assertEquals(1, foodReminderEvaluator.evaluateDueSchedules())

        val persisted = schedules.findById(schedule.id).orElseThrow()
        assertFalse(persisted.enabled)
        assertEquals(FoodReminderScheduleState.INVALID_TIMEZONE, persisted.state)
        assertEquals(null, persisted.nextEvaluationAt)
    }

    @Test
    fun `unlogged consented meal creates one idempotent food reminder`() {
        val userId = createUser()
        val now = time.now()
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to true, NotificationCategory.OPTIONAL_WEIGHT_LOGGING to false)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/$userId", "p256dh", "auth"))
        val schedule = schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH,
            localTime = now.atZone(ZoneId.of("Asia/Tehran")).toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(now.atZone(ZoneId.of("Asia/Tehran")).dayOfWeek.value),
            enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1),
        ))

        foodReminderEvaluator.evaluateDueSchedules()
        schedule.nextEvaluationAt = now.minusSeconds(1)
        schedules.saveAndFlush(schedule)
        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(1, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'FOOD_MEAL_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `Push subscription lifecycle persists one reactivated endpoint and updates schedule availability`() {
        val userId = createUser()
        val now = time.now()
        val endpoint = "https://push.example.test/lifecycle-$userId"
        schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH,
            localTime = LocalTime.NOON, daysOfWeek = intArrayOf(1), enabled = true, state = FoodReminderScheduleState.CHANNEL_UNAVAILABLE,
            consentedAt = now, actorUserId = userId, nextEvaluationAt = now,
        ))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(endpoint, "p256dh", "auth"))
        val row = pushSubscriptionRepository.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId).single()
        assertEquals(FoodReminderScheduleState.ACTIVE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)
        pushSubscriptions.revoke(userId, row.endpointFingerprint)
        assertEquals(FoodReminderScheduleState.CHANNEL_UNAVAILABLE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(endpoint, "p256dh-new", "auth-new"))

        assertEquals(1, jdbc.queryForObject("select count(*) from notification_push_subscriptions where user_id = ?", Int::class.java, userId))
        assertEquals(0, jdbc.queryForObject("select count(*) from notification_push_subscriptions where user_id = ? and revoked_at is not null", Int::class.java, userId))
        assertEquals(FoodReminderScheduleState.ACTIVE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)
    }

    @Test
    fun `disabling one Push device preserves other devices and only the final revoke pauses schedules`() {
        val userId = createUser()
        val now = time.now()
        val firstEndpoint = "https://push.example.test/device-a-$userId"
        val secondEndpoint = "https://push.example.test/device-b-$userId"
        schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH,
            localTime = LocalTime.NOON, daysOfWeek = intArrayOf(1), enabled = true, state = FoodReminderScheduleState.ACTIVE,
            consentedAt = now, actorUserId = userId, nextEvaluationAt = now,
        ))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(firstEndpoint, "p256dh-a", "auth-a"))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(secondEndpoint, "p256dh-b", "auth-b"))

        pushSubscriptions.revokeEndpoint(userId, firstEndpoint)

        assertEquals(1, pushSubscriptions.status(userId).activeSubscriptionCount)
        assertTrue(pushSubscriptions.hasActiveSubscription(userId, secondEndpoint))
        assertEquals(FoodReminderScheduleState.ACTIVE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)

        pushSubscriptions.revokeEndpoint(userId, secondEndpoint)

        assertEquals(0, pushSubscriptions.status(userId).activeSubscriptionCount)
        assertEquals(FoodReminderScheduleState.CHANNEL_UNAVAILABLE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)

        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(firstEndpoint, "p256dh-a", "auth-a"))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand(secondEndpoint, "p256dh-b", "auth-b"))
        pushSubscriptions.revokeAll(userId)

        assertEquals(0, pushSubscriptions.status(userId).activeSubscriptionCount)
        assertEquals(FoodReminderScheduleState.CHANNEL_UNAVAILABLE, schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).single().state)
    }

    @Test
    fun `concurrent Push subscriptions never exceed the configured per-user cap`() {
        val userId = createUser()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(12)
        try {
            val futures = (1..20).map { device ->
                executor.submit {
                    start.await()
                    pushSubscriptions.subscribe(
                        userId,
                        PushSubscriptionCommand("https://push.example.test/concurrent-$userId-$device", "p256dh-$device", "auth-$device"),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertEquals(10, pushSubscriptions.status(userId).activeSubscriptionCount)
        assertEquals(10, pushSubscriptionRepository.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId).size)
    }

    @Test
    fun `logged meal suppresses only its matching meal reminder`() {
        val userId = createUser()
        val now = time.now()
        val tehran = now.atZone(ZoneId.of("Asia/Tehran"))
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/diary-$userId", "p256dh", "auth"))
        insertDiaryEntry(userId, tehran.toLocalDate().toString(), "LUNCH")
        schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH,
            localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = true,
            state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1),
        ))

        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(0, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'FOOD_MEAL_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `three consecutive schedule failures back off then terminalize and manual edit resets state`() {
        val userId = createUser()
        val now = time.now()
        var schedule = schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH,
            localTime = LocalTime.NOON, daysOfWeek = intArrayOf(1), enabled = true, state = FoodReminderScheduleState.ACTIVE,
            consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1),
        ))
        repeat(3) { attempt ->
            val token = UUID.randomUUID()
            schedule.claimOwner = "failure-test"
            schedule.claimedAt = now
            schedule.claimExpiresAt = now.plusSeconds(60)
            schedule.claimToken = token
            schedules.saveAndFlush(schedule)
            foodReminderProcessor.recordFailure(NotificationScheduleClaim(schedule.id, "failure-test", token), now, IllegalStateException("test"))
            val persisted = schedules.findById(schedule.id).orElseThrow()
            assertEquals(attempt + 1, persisted.processingFailureCount)
            if (attempt < 2) {
                assertEquals(now.plus(Duration.ofMinutes(5)), persisted.nextEvaluationAt)
                assertEquals(null, persisted.claimToken)
            }
            schedule = persisted
        }
        val terminal = schedules.findById(schedule.id).orElseThrow()
        assertFalse(terminal.enabled)
        assertEquals(FoodReminderScheduleState.PROCESSING_FAILED, terminal.state)
        assertEquals(null, terminal.nextEvaluationAt)

        terminal.enabled = true
        terminal.state = FoodReminderScheduleState.ACTIVE
        terminal.processingFailureCount = 0
        terminal.lastProcessingFailureAt = null
        terminal.lastProcessingFailureClass = null
        schedules.saveAndFlush(terminal)
        assertEquals(0, schedules.findById(schedule.id).orElseThrow().processingFailureCount)
    }

    @Test
    fun `missing food consent disabled schedule and excluded weekday create no reminder`() {
        val userId = createUser()
        val now = time.now()
        val tehran = now.atZone(ZoneId.of("Asia/Tehran"))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/guards-$userId", "p256dh", "auth"))
        listOf(
            NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.BREAKFAST, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1)),
            NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = false, state = FoodReminderScheduleState.PAUSED, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1)),
            NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.DINNER, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(if (tehran.dayOfWeek.value == 7) 1 else tehran.dayOfWeek.value + 1), enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1)),
        ).forEach { schedules.saveAndFlush(it) }

        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(0, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type like 'FOOD_%'", Int::class.java, userId))
    }

    @Test
    fun `incomplete day reminder is suppressed after any diary entry`() {
        val userId = createUser()
        val now = time.now()
        val tehran = now.atZone(ZoneId.of("Asia/Tehran"))
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/incomplete-$userId", "p256dh", "auth"))
        insertDiaryEntry(userId, tehran.toLocalDate().toString(), "BREAKFAST")
        schedules.saveAndFlush(NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.INCOMPLETE_DAY_REMINDER, mealType = null, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1)))

        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(0, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'FOOD_INCOMPLETE_DAY_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `concurrent evaluators claim one due schedule once`() {
        val userId = createUser()
        val now = time.now()
        val tehran = now.atZone(ZoneId.of("Asia/Tehran"))
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/concurrent-$userId", "p256dh", "auth"))
        schedules.saveAndFlush(NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Int> { foodReminderEvaluator.evaluateDueSchedules() }
            val second = pool.submit<Int> { foodReminderEvaluator.evaluateDueSchedules() }
            first.get(15, TimeUnit.SECONDS)
            second.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'FOOD_MEAL_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `expired schedule claim is recovered and reminder is evaluated`() {
        val userId = createUser()
        val now = time.now()
        val tehran = now.atZone(ZoneId.of("Asia/Tehran"))
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/recover-$userId", "p256dh", "auth"))
        val schedule = schedules.saveAndFlush(NotificationScheduleEntity(userId = userId, scheduleType = FoodReminderScheduleType.MEAL_REMINDER, mealType = FoodReminderMealType.LUNCH, localTime = tehran.toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(tehran.dayOfWeek.value), enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1), claimOwner = "crashed-worker", claimedAt = now.minusSeconds(120), claimExpiresAt = now.minusSeconds(60), claimToken = UUID.randomUUID()))

        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(1, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'FOOD_MEAL_REMINDER'", Int::class.java, userId))
        assertEquals(null, schedules.findById(schedule.id).orElseThrow().claimToken)
    }

    @Test
    fun `invalid account endpoint is terminalized before adapter invocation`() {
        val userId = createUser()
        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(
                quietHoursStart = LocalTime.MIDNIGHT,
                quietHoursEnd = LocalTime.of(0, 1),
                categories = mapOf(NotificationCategory.OPTIONAL_BILLING to true),
            ),
        )
        preferences.markInvalid(
            NotificationEndpointCandidate(userId, NotificationChannel.EMAIL, NotificationEndpointSource.ACCOUNT_EMAIL, "notification-$userId@example.com"),
            NotificationEndpointInvalidReason.MAILBOX_INVALID,
        )
        val deliveryId = createDelivery(userId, "account:email")

        assertEquals(1, worker.processBatch())

        val delivery = deliveries.findById(deliveryId).orElseThrow()
        assertEquals(NotificationDeliveryStatus.PERMANENT_FAILURE, delivery.status)
        assertEquals(NotificationReason.ENDPOINT_INVALID, delivery.reason)
        assertEquals(0, adapter.providerDeliveries.get())
    }

    @Test
    fun `optional deliveries are deferred until the end of quiet hours`() {
        val userId = createUser()
        val localNow = time.now().atZone(ZoneId.of("Asia/Tehran")).toLocalTime()
        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(
                quietHoursStart = localNow.minusMinutes(1),
                quietHoursEnd = localNow.plusMinutes(1),
                categories = mapOf(NotificationCategory.OPTIONAL_BILLING to true),
            ),
        )
        val deliveryId = createDelivery(userId, "account:email")

        assertEquals(1, worker.processBatch())

        val delivery = deliveries.findById(deliveryId).orElseThrow()
        assertEquals(NotificationDeliveryStatus.PENDING, delivery.status)
        assertFalse(delivery.dueAt.isBefore(time.now()))
        assertEquals(0, adapter.providerDeliveries.get())
    }

    @Test
    fun `quiet hours defer transactional push without suppressing it`() {
        val userId = createUser()
        val localNow = time.now().atZone(ZoneId.of("Asia/Tehran")).toLocalTime()
        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(
                quietHoursStart = localNow.minusMinutes(1),
                quietHoursEnd = localNow.plusMinutes(1),
                categories = emptyMap(),
            ),
        )
        val deliveryId = createDelivery(
            userId = userId,
            endpointReference = "push:active",
            channel = NotificationChannel.PUSH,
            category = NotificationCategory.MANDATORY_TRANSACTIONAL,
        )

        assertEquals(1, worker.processBatch())

        val delivery = deliveries.findById(deliveryId).orElseThrow()
        assertEquals(NotificationDeliveryStatus.PENDING, delivery.status)
        assertFalse(delivery.dueAt.isBefore(time.now()))
        assertEquals(null, delivery.reason)
        assertEquals(0, adapter.providerDeliveries.get())
    }

    @Test
    fun `minimum gap defers a coaching push after a reminder push`() {
        val userId = createUser()
        preferences.update(
            userId,
            UpdateNotificationPreferencesCommand(
                quietHoursStart = LocalTime.MIDNIGHT,
                quietHoursEnd = LocalTime.of(0, 1),
                categories = mapOf(
                    NotificationCategory.OPTIONAL_WEIGHT_LOGGING to true,
                    NotificationCategory.OPTIONAL_ANNOUNCEMENTS to true,
                ),
            ),
        )
        createDelivery(
            userId = userId,
            endpointReference = "push:active",
            channel = NotificationChannel.PUSH,
            category = NotificationCategory.OPTIONAL_WEIGHT_LOGGING,
        )
        val reminderStartedAt = time.now()
        assertEquals(1, worker.processBatch())
        assertEquals(1, adapter.providerDeliveries.get())

        val coachingDeliveryId = createDelivery(
            userId = userId,
            endpointReference = "push:active",
            channel = NotificationChannel.PUSH,
            category = NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
        )
        assertEquals(1, worker.processBatch())

        val coachingDelivery = deliveries.findById(coachingDeliveryId).orElseThrow()
        assertEquals(NotificationDeliveryStatus.PENDING, coachingDelivery.status)
        assertFalse(coachingDelivery.dueAt.isBefore(reminderStartedAt.plus(Duration.ofMinutes(5))))
        assertEquals(1, adapter.providerDeliveries.get())
    }

    @Test
    fun `reminders do not consume the announcement budget and excess coaching is dropped`() {
        val userId = createUser()
        val now = time.now()
        fun create(type: NotificationType, key: String, expiry: Duration): UUID =
            notificationService.create(
                NotificationRequest(
                    recipientUserId = userId,
                    type = type,
                    templateData = if (type == NotificationType.COACH_DATA_NUDGE) {
                        mapOf("body" to TemplateVariableValue.Text("ثبت امروز به بررسی برنامه کمک می‌کند."))
                    } else {
                        emptyMap()
                    },
                    occurredAt = now,
                    scheduledAt = now,
                    expiresAt = now.plus(expiry),
                    idempotencyKey = "$key:$userId",
                    requestId = key,
                    sourceType = "INTEGRATION_TEST",
                    sourceReference = userId.toString(),
                ),
            )

        val reminderId = create(NotificationType.WEIGHT_REMINDER, "weight-reminder", Duration.ofMinutes(60))
        val firstCoachId = create(
            NotificationType.COACH_DATA_NUDGE,
            "first-coaching-push",
            Duration.ofHours(1),
        )
        val secondCoachId = create(
            NotificationType.RECALIBRATION_SUGGESTION,
            "second-coaching-push",
            Duration.ofHours(1),
        )

        assertEquals(NotificationIntentStatus.ROUTED, intents.findById(reminderId).orElseThrow().status)
        assertEquals(NotificationIntentStatus.ROUTED, intents.findById(firstCoachId).orElseThrow().status)
        val dropped = intents.findById(secondCoachId).orElseThrow()
        assertEquals(NotificationIntentStatus.SUPPRESSED, dropped.status)
        assertEquals(NotificationReason.BUDGET_LIMIT, dropped.reason)
        assertTrue(deliveries.findAllByIntentId(secondCoachId).isEmpty())
    }

    @Test
    fun `expired endpoint diagnostics are cleared without restoring endpoint health`() {
        val userId = createUser()
        val candidate = NotificationEndpointCandidate(
            userId, NotificationChannel.EMAIL, NotificationEndpointSource.ACCOUNT_EMAIL, "notification-$userId@example.com",
        )
        preferences.markInvalid(candidate, NotificationEndpointInvalidReason.MAILBOX_INVALID)
        val endpoint = endpointHealth.findAll().single { it.userId == userId }
        endpoint.diagnosticDeleteAt = time.now().minusSeconds(1)
        endpointHealth.saveAndFlush(endpoint)

        assertEquals(1, cleanup.clearExpiredDiagnostics())

        val sanitized = endpointHealth.findById(endpoint.id).orElseThrow()
        assertEquals(NotificationEndpointHealthState.INVALID, sanitized.healthState)
        assertEquals(null, sanitized.invalidReason)
        assertEquals(null, sanitized.lastFailureAt)
        assertEquals(null, sanitized.diagnosticDeleteAt)
    }

    @Test
    fun `external email and SMS endpoint references fail closed unless explicitly supported`() {
        val userId = createUser()

        assertFalse(endpointEligibility.isEligible(userId, NotificationChannel.EMAIL, "account:emial", "test-controlled"))
        assertFalse(endpointEligibility.isEligible(userId, NotificationChannel.SMS, "account:email", "test-controlled"))
        assertFalse(endpointEligibility.isEligible(userId, NotificationChannel.EMAIL, "external:email", "test-controlled"))
        assertFalse(endpointEligibility.isEligible(userId, NotificationChannel.EMAIL, "internal:notification-core-probe", "test-controlled"))
        assertEquals(true, endpointEligibility.isEligible(userId, NotificationChannel.EMAIL, "internal:notification-core-probe", "log-only"))
    }

    @Test
    fun `admin notification filters are authorized and paginated in the database`() {
        val nonAdminId = createUser()
        createDelivery(nonAdminId, "account:email")
        val smsUserId = createUser()
        createDelivery(smsUserId, "account:phone", NotificationChannel.SMS)

        mockMvc.get("/api/v1/admin/notifications").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/admin/notifications") {
            with(authentication(authToken(nonAdminId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/admin/notifications") {
            with(authentication(authToken(UUID.randomUUID(), "ROLE_ADMIN")))
            param("channel", "SMS")
            param("size", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(1) }
            jsonPath("$.totalPages") { value(1) }
            jsonPath("$.items[0].deliveries[0].channel") { value("SMS") }
        }
    }

    @Test
    fun `manual receipt retry is admin only idempotent and audited`() {
        val userId = createUser()
        val now = time.now()
        val failedIntentId = notificationService.create(
            NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.PAYMENT_VERIFIED,
                templateData = mapOf(
                    "amount" to TemplateVariableValue.Number(BigDecimal("125000")),
                    "currency" to TemplateVariableValue.Text("IRR"),
                ),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofHours(24)),
                idempotencyKey = "failed-payment-receipt:$userId",
                requestId = "payment-receipt-$userId",
                sourceType = "INTEGRATION_TEST",
                sourceReference = userId.toString(),
            ),
        )
        val failedDelivery = deliveries.findAllByIntentId(failedIntentId).single()
        failedDelivery.status = NotificationDeliveryStatus.PERMANENT_FAILURE
        failedDelivery.reason = NotificationReason.PROVIDER_PERMANENT
        deliveries.saveAndFlush(failedDelivery)
        val operatorId = createUser()

        mockMvc.post("/api/v1/admin/notifications/$failedIntentId/deliveries/${failedDelivery.id}/manual-retry") {
            with(authentication(authToken(operatorId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }

        val response = mockMvc.post("/api/v1/admin/notifications/$failedIntentId/deliveries/${failedDelivery.id}/manual-retry") {
            with(authentication(authToken(operatorId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.retryIntentId") { exists() }
        }.andReturn().response.contentAsString
        val retryIntentId = kotlin.text.Regex("\\\"retryIntentId\\\":\\\"([^\\\"]+)\\\"")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?.let(UUID::fromString)
            ?: error("Manual retry response did not contain retryIntentId")

        mockMvc.post("/api/v1/admin/notifications/$failedIntentId/deliveries/${failedDelivery.id}/manual-retry") {
            with(authentication(authToken(operatorId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.retryIntentId") { value(retryIntentId.toString()) }
        }

        val recovery = intents.findById(retryIntentId).orElseThrow()
        assertEquals(NotificationType.PAYMENT_VERIFIED, recovery.type)
        assertEquals("ADMIN_MANUAL_RECEIPT_RETRY", recovery.sourceType)
        assertEquals("delivery:${failedDelivery.id};operator:$operatorId", recovery.sourceReference)
        assertEquals(NotificationDeliveryStatus.PERMANENT_FAILURE, deliveries.findById(failedDelivery.id).orElseThrow().status)
        assertEquals(1, intents.findAllByUserId(userId).count { it.sourceType == "ADMIN_MANUAL_RECEIPT_RETRY" })
        val auditEntries = auditEvents.findAll().filter {
            it.eventType == AccountAuditEventType.ADMIN_PAYMENT_RECEIPT_RETRY_REQUESTED && it.targetUserId == userId
        }
        assertEquals(2, auditEntries.size)
        assertTrue(auditEntries.all { it.actorUserId == operatorId })
        assertTrue(auditEntries.all { it.reason == NotificationReason.PROVIDER_PERMANENT.name })
        assertTrue(auditEntries.all { it.metadata["recoveryIntentId"] == retryIntentId.toString() })
    }

    @Test
    fun `due weight reminder creates one idempotent notification`() {
        val userId = createUser()
        val now = time.now()
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to false, NotificationCategory.OPTIONAL_WEIGHT_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/weight-$userId", "p256dh", "auth"))
        val schedule = schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.WEIGHT_REMINDER, mealType = null,
            localTime = now.atZone(ZoneId.of("Asia/Tehran")).toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(now.atZone(ZoneId.of("Asia/Tehran")).dayOfWeek.value),
            enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1),
        ))

        foodReminderEvaluator.evaluateDueSchedules()
        schedule.nextEvaluationAt = now.minusSeconds(1)
        schedules.saveAndFlush(schedule)
        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(1, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'WEIGHT_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `weight reminder is suppressed when weight is already logged today`() {
        val userId = createUser()
        val now = time.now()
        preferences.update(userId, UpdateNotificationPreferencesCommand(LocalTime.MIDNIGHT, LocalTime.of(0, 1), mapOf(NotificationCategory.OPTIONAL_FOOD_LOGGING to false, NotificationCategory.OPTIONAL_WEIGHT_LOGGING to true)))
        pushSubscriptions.subscribe(userId, PushSubscriptionCommand("https://push.example.test/weight-logged-$userId", "p256dh", "auth"))
        val today = now.atZone(ZoneId.of("Asia/Tehran")).toLocalDate().toString()
        jdbc.update(
            "insert into weight_entries (id, user_id, recorded_date, recorded_at, weight_kg, display_weight, display_unit, source, created_at, updated_at) values (?, ?, ?::date, ?, 80, 80, 'KG', 'MANUAL', now(), now())",
            UUID.randomUUID(), userId, today, java.sql.Timestamp.from(now),
        )
        schedules.saveAndFlush(NotificationScheduleEntity(
            userId = userId, scheduleType = FoodReminderScheduleType.WEIGHT_REMINDER, mealType = null,
            localTime = now.atZone(ZoneId.of("Asia/Tehran")).toLocalTime().minusMinutes(1), daysOfWeek = intArrayOf(now.atZone(ZoneId.of("Asia/Tehran")).dayOfWeek.value),
            enabled = true, state = FoodReminderScheduleState.ACTIVE, consentedAt = now, actorUserId = userId, nextEvaluationAt = now.minusSeconds(1),
        ))

        foodReminderEvaluator.evaluateDueSchedules()

        assertEquals(0, jdbc.queryForObject("select count(*) from notification_intents where user_id = ? and notification_type = 'WEIGHT_REMINDER'", Int::class.java, userId))
    }

    @Test
    fun `weight schedule opt in persists independent consent`() {
        val userId = createUser()

        mockMvc.put("/api/v1/notifications/schedules/WEIGHT_REMINDER") {
            with(authentication(authToken(userId, "ROLE_USER")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"mealType":null,"localTime":"08:00","daysOfWeek":[1,2,3,4,5,6,7],"enabled":true}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.type") { value("WEIGHT_REMINDER") }
            jsonPath("$.enabled") { value(true) }
        }

        val categories = preferences.get(userId).categories.associate { it.category to it.enabled }
        assertEquals(true, categories[NotificationCategory.OPTIONAL_WEIGHT_LOGGING])
        assertEquals(false, categories[NotificationCategory.OPTIONAL_FOOD_LOGGING])
    }

    @Test
    fun `schedule API maps invalid shapes to validation errors`() {
        val userId = createUser()
        val invalidRequests = listOf(
            Triple("WEIGHT_REMINDER", """{"mealType":"DINNER","localTime":"08:00","daysOfWeek":[1],"enabled":true}""", "mealType"),
            Triple("WEIGHT_REMINDER", """{"mealType":null,"localTime":"08:00","daysOfWeek":[],"enabled":true}""", "daysOfWeek"),
            Triple("WEIGHT_REMINDER", """{"mealType":null,"localTime":"08:00","daysOfWeek":[0,8],"enabled":true}""", "daysOfWeek"),
            Triple("MEAL_REMINDER", """{"mealType":null,"localTime":"20:00","daysOfWeek":[1],"enabled":true}""", "mealType"),
        )

        invalidRequests.forEach { (type, body, field) ->
            mockMvc.put("/api/v1/notifications/schedules/$type") {
                with(authentication(authToken(userId, "ROLE_USER")))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_ERROR") }
                jsonPath("$.fieldErrors[?(@.field == '$field')]") { exists() }
            }
        }
    }

    private fun createUser(): UUID = UUID.randomUUID().also { userId ->
        users += userId
        jdbc.update(
            """
            insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at)
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "notification-$userId@example.com",
        )
        jdbc.update("insert into user_profiles (user_id, timezone, locale, created_at, updated_at) values (?, 'Asia/Tehran', 'fa', now(), now())", userId)
    }

    private fun insertDiaryEntry(userId: UUID, date: String, mealType: String) {
        val dayId = UUID.randomUUID()
        jdbc.update("insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at) values (?, ?, ?::date, 'Asia/Tehran', now(), now())", dayId, userId, date)
        jdbc.update(
            """insert into diary_entries (id, diary_day_id, user_id, diary_date, meal_type, source_type, source_metadata, display_name_snapshot, serving_quantity_snapshot, serving_unit_code_snapshot, serving_unit_name_snapshot, calories_snapshot, protein_snapshot, carbs_snapshot, fat_snapshot, fiber_snapshot, sugar_snapshot, sodium_snapshot, sort_order, created_at, updated_at)
               values (gen_random_uuid(), ?, ?, ?::date, ?, 'MANUAL', '{}'::jsonb, 'test', 1, 'g', 'گرم', 1, 0, 0, 0, 0, 0, 0, 0, now(), now())""".trimIndent(),
            dayId, userId, date, mealType,
        )
    }

    private fun createDelivery(
        userId: UUID,
        endpointReference: String,
        channel: NotificationChannel = NotificationChannel.EMAIL,
        category: NotificationCategory = NotificationCategory.OPTIONAL_BILLING,
    ): UUID {
        val now = time.now()
        val intent = intents.saveAndFlush(
            NotificationIntentEntity(
                userId = userId,
                type = NotificationType.CORE_PROBE,
                category = category,
                risk = NotificationRisk.LOW,
                routeStrategy = NotificationRouteStrategy.EXPLICIT_CHANNELS,
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofMinutes(10)),
                idempotencyKey = "eligibility:$userId:$endpointReference:${UUID.randomUUID()}",
                sourceType = "INTEGRATION_TEST",
                sourceReference = userId.toString(),
                requestId = "test-$userId",
                templateData = "{}",
                status = NotificationIntentStatus.ROUTED,
            ),
        )
        return requireNotNull(jdbc.queryForObject(
            """
            insert into notification_deliveries (id, intent_id, channel, endpoint_reference, provider_request_id, adapter_key, template_key, template_version, template_locale, rendered_plain_body, due_at, expires_at, status, attempt_count, created_at, updated_at)
            values (gen_random_uuid(), ?, ?, ?, ?, 'test-controlled', 'notification-core-probe', 1, 'en', 'test', ?, ?, 'PENDING', 0, now(), now())
            returning id
            """.trimIndent(),
            UUID::class.java,
            intent.id,
            channel.name,
            endpointReference,
            "provider-$userId-${UUID.randomUUID()}",
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now.plus(Duration.ofMinutes(10))),
        ))
    }

    private fun authToken(id: UUID, role: String) = UsernamePasswordAuthenticationToken(
        id.toString(), null, listOf(SimpleGrantedAuthority(role)),
    )
}
