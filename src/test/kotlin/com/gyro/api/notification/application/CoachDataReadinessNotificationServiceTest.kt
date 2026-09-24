package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.recalibration.RecalibrationData
import com.gyro.api.goal.application.recalibration.RecalibrationDataLoader
import com.gyro.api.goal.application.recalibration.RecalibrationWeightPoint
import com.gyro.api.goal.domain.NutritionPlanEntity
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationDeliveryEligibility
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationReason
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.CoachDataNudgeRepository
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.user.application.UserTimezoneResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class CoachDataReadinessNotificationServiceTest {
    private val capturedRequests = mutableListOf<NotificationRequest>()
    private var notificationOutcome = routedOutcome()
    private val dataLoader = Mockito.mock(RecalibrationDataLoader::class.java)
    private val entitlements = Mockito.mock(EntitlementGateService::class.java)
    private val timezones = Mockito.mock(UserTimezoneResolver::class.java)
    private val preferences = Mockito.mock(NotificationPreferenceService::class.java)
    private val pushSubscriptions = Mockito.mock(PushSubscriptionService::class.java)
    private val repository = Mockito.mock(CoachDataNudgeRepository::class.java)
    private val notifications = Mockito.mock(NotificationService::class.java) { invocation ->
        if (invocation.method.name == "createWithOutcome") {
            capturedRequests += invocation.arguments.first() as NotificationRequest
            notificationOutcome
        } else {
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val metrics = Mockito.mock(NotificationMetrics::class.java)
    private val time = Mockito.mock(TimeProvider::class.java)
    private val properties = NotificationProperties()
    private val service = CoachDataReadinessNotificationService(
        dataLoader,
        entitlements,
        timezones,
        preferences,
        pushSubscriptions,
        repository,
        notifications,
        metrics,
        properties,
        time,
    )
    private val userId = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Tehran")
    private val now = Instant.parse("2026-07-31T14:30:00Z")
    private val today = LocalDate.parse("2026-07-31")
    private val plan = plan(startDate = today.minusDays(7))

    @BeforeEach
    fun setUp() {
        capturedRequests.clear()
        notificationOutcome = routedOutcome()
        Mockito.`when`(entitlements.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        Mockito.`when`(time.now()).thenReturn(now)
        Mockito.`when`(timezones.resolve(userId)).thenReturn(zone)
        Mockito.`when`(dataLoader.activePlanFor(userId, zone)).thenReturn(plan)
        Mockito.`when`(
            preferences.deliveryEligibility(userId, NotificationCategory.OPTIONAL_ANNOUNCEMENTS),
        ).thenReturn(
            NotificationDeliveryEligibility(true, zone.id, LocalTime.of(22, 0), LocalTime.of(8, 0)),
        )
        Mockito.`when`(pushSubscriptions.hasActiveSubscription(userId)).thenReturn(true)
        Mockito.`when`(dataLoader.loggedDays(userId, today, today.plusDays(1))).thenReturn(0)
        Mockito.`when`(repository.hasNudgeForEvidencePeriod(userId, "${plan.id}:${plan.startDate}"))
            .thenReturn(false)
        Mockito.`when`(
            repository.hasRecentAcceptedReminder(
                userId,
                setOf(NotificationType.FOOD_MEAL_REMINDER, NotificationType.FOOD_INCOMPLETE_DAY_REMINDER),
                now.minus(properties.coachDataNudgeReminderLookback),
                now,
            ),
        ).thenReturn(false)
        Mockito.`when`(
            repository.hasRecentAcceptedReminder(
                userId,
                setOf(NotificationType.WEIGHT_REMINDER),
                now.minus(properties.coachDataNudgeReminderLookback),
                now,
            ),
        ).thenReturn(false)
    }

    @Test
    fun `one binding food action creates one public push intent`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))

        assertEquals(CoachDataNudgeOutcome.CREATED, service.evaluate(userId))

        val request = capturedRequests.single()
        assertEquals(NotificationType.COACH_DATA_NUDGE, request.type)
        assertEquals(setOf("body"), request.templateData.keys)
        assertEquals(
            "برای اینکه بررسی برنامه‌ات عقب نیفتد، ثبت غذاهای امروز کمک می‌کند.",
            (request.templateData.getValue("body") as TemplateVariableValue.Text).value,
        )
        assertEquals("${plan.id}:${plan.startDate}", request.sourceReference)
        assertEquals("coach-data:$userId:${plan.startDate}:$today:INSUFFICIENT_FOOD_EVIDENCE", request.idempotencyKey)
    }

    @Test
    fun `budget suppression is reported separately and remains retryable`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        notificationOutcome = NotificationCreateOutcome(
            intentId = UUID.randomUUID(),
            created = true,
            status = NotificationIntentStatus.SUPPRESSED,
            reason = NotificationReason.BUDGET_LIMIT,
        )

        assertEquals(CoachDataNudgeOutcome.BUDGET_SUPPRESSED, service.evaluate(userId))

        Mockito.verify(repository).hasNudgeForEvidencePeriod(userId, "${plan.id}:${plan.startDate}")
    }

    @Test
    fun `unexpected routing loss is not reported as a created nudge`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        notificationOutcome = NotificationCreateOutcome(
            intentId = UUID.randomUUID(),
            created = true,
            status = NotificationIntentStatus.UNDELIVERABLE,
            reason = NotificationReason.NO_VERIFIED_ENDPOINT,
        )

        assertEquals(CoachDataNudgeOutcome.UNDELIVERABLE, service.evaluate(userId))
    }

    @Test
    fun `repeated evaluation creates at most one nudge for the evidence period`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        Mockito.`when`(repository.hasNudgeForEvidencePeriod(userId, "${plan.id}:${plan.startDate}"))
            .thenReturn(false, true)

        assertEquals(CoachDataNudgeOutcome.CREATED, service.evaluate(userId))
        assertEquals(CoachDataNudgeOutcome.EVIDENCE_PERIOD_ALREADY_NOTIFIED, service.evaluate(userId))
        assertEquals(1, capturedRequests.size)
    }

    @Test
    fun `recent food reminder suppresses the nudge`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        Mockito.`when`(
            repository.hasRecentAcceptedReminder(
                userId,
                setOf(NotificationType.FOOD_MEAL_REMINDER, NotificationType.FOOD_INCOMPLETE_DAY_REMINDER),
                now.minus(properties.coachDataNudgeReminderLookback),
                now,
            ),
        ).thenReturn(true)

        assertEquals(CoachDataNudgeOutcome.RECENT_MATCHING_REMINDER, service.evaluate(userId))
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `combined evidence gaps create only the selected same day weight action`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(1)))

        assertEquals(CoachDataNudgeOutcome.CREATED, service.evaluate(userId))

        val request = capturedRequests.single()
        assertEquals(
            "برای کامل‌تر شدن روند وزنت، وزن امروزت را ثبت کن.",
            (request.templateData.getValue("body") as TemplateVariableValue.Text).value,
        )
    }

    @Test
    fun `disabled optional announcements preference suppresses the nudge`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        Mockito.`when`(
            preferences.deliveryEligibility(userId, NotificationCategory.OPTIONAL_ANNOUNCEMENTS),
        ).thenReturn(
            NotificationDeliveryEligibility(false, zone.id, LocalTime.of(22, 0), LocalTime.of(8, 0)),
        )

        assertEquals(CoachDataNudgeOutcome.PREFERENCE_DISABLED, service.evaluate(userId))
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `missing push subscription does not consume the evidence period`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        Mockito.`when`(pushSubscriptions.hasActiveSubscription(userId)).thenReturn(false)

        assertEquals(CoachDataNudgeOutcome.PUSH_UNAVAILABLE, service.evaluate(userId))
        Mockito.verify(repository, Mockito.never()).lockUser(userId)
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `future weight day action does not create a push`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 4, weightDaysAgo = listOf(7, 0)))

        assertEquals(CoachDataNudgeOutcome.NO_ACTION_TODAY, service.evaluate(userId))
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `today food already logged cannot improve readiness again`() {
        Mockito.`when`(dataLoader.loadReadinessForPlan(userId, plan, zone))
            .thenReturn(data(loggedDays = 3, weightDaysAgo = listOf(7, 3, 0)))
        Mockito.`when`(dataLoader.loggedDays(userId, today, today.plusDays(1))).thenReturn(1)

        assertEquals(CoachDataNudgeOutcome.ACTION_ALREADY_COMPLETED, service.evaluate(userId))
        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `day fourteen is too late for a readiness nudge`() {
        val maturePlan = plan(startDate = today.minusDays(14))
        Mockito.`when`(dataLoader.activePlanFor(userId, zone)).thenReturn(maturePlan)

        assertEquals(CoachDataNudgeOutcome.OUTSIDE_READINESS_WINDOW, service.evaluate(userId))
        Mockito.verifyNoInteractions(notifications)
    }

    private fun data(loggedDays: Int, weightDaysAgo: List<Int>) = RecalibrationData(
        plan = plan,
        today = today,
        windowStart = plan.startDate,
        intakeThrough = today.minusDays(1),
        weightThrough = today,
        weights = weightDaysAgo.map { daysAgo ->
            RecalibrationWeightPoint(today.minusDays(daysAgo.toLong()), BigDecimal("80"))
        },
        loggedDays = loggedDays,
        averageLoggedCalories = BigDecimal("1500"),
        averageHistoricalTargetCalories = null,
    )

    private fun plan(startDate: LocalDate) = NutritionPlanEntity(
        id = UUID.randomUUID(),
        userId = userId,
        startDate = startDate,
        timezone = zone.id,
        calories = BigDecimal("1500"),
        protein = BigDecimal("100"),
        carbs = BigDecimal("150"),
        fat = BigDecimal("55"),
        dailyEnergyDelta = BigDecimal("-500"),
    )

    private companion object {
        fun routedOutcome() = NotificationCreateOutcome(
            intentId = UUID.randomUUID(),
            created = true,
            status = NotificationIntentStatus.ROUTED,
        )
    }
}
