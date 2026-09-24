package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.coach.NutritionCoachNextUsefulAction
import com.gyro.api.goal.application.coach.NutritionCoachReadinessPolicy
import com.gyro.api.goal.application.recalibration.RecalibrationDataLoader
import com.gyro.api.goal.application.recalibration.RecalibrationWindowPolicy
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationReason
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.CoachDataNudgeRepository
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.user.application.UserTimezoneResolver
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class CoachDataNudgeOutcome {
    CREATED,
    DUPLICATE,
    BUDGET_SUPPRESSED,
    UNDELIVERABLE,
    FEATURE_UNAVAILABLE,
    OUTSIDE_READINESS_WINDOW,
    EVIDENCE_READY,
    NO_ACTION_TODAY,
    ACTION_ALREADY_COMPLETED,
    PREFERENCE_DISABLED,
    PUSH_UNAVAILABLE,
    EVIDENCE_PERIOD_ALREADY_NOTIFIED,
    RECENT_MATCHING_REMINDER,
    DATA_UNAVAILABLE,
}

@Service
class CoachDataReadinessNotificationService(
    private val dataLoader: RecalibrationDataLoader,
    private val entitlementGateService: EntitlementGateService,
    private val timezoneResolver: UserTimezoneResolver,
    private val preferences: NotificationPreferenceService,
    private val pushSubscriptions: PushSubscriptionService,
    private val repository: CoachDataNudgeRepository,
    private val notifications: NotificationService,
    private val metrics: NotificationMetrics,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    @Transactional
    fun evaluate(userId: UUID): CoachDataNudgeOutcome {
        if (!entitlementGateService.hasFeatureAccess(userId, RECALIBRATION_FEATURE)) {
            return outcome(CoachDataNudgeOutcome.FEATURE_UNAVAILABLE)
        }
        val now = time.now()
        val zone = timezoneResolver.resolve(userId)
        val today = now.atZone(zone).toLocalDate()
        val plan = dataLoader.activePlanFor(userId, zone)
            ?: return outcome(CoachDataNudgeOutcome.DATA_UNAVAILABLE)
        val completedPlanDays = ChronoUnit.DAYS.between(plan.startDate, today).coerceAtLeast(0).toInt()
        if (completedPlanDays !in NutritionCoachReadinessPolicy.READINESS_CHECK_DAY until RecalibrationWindowPolicy.FOURTEEN_DAYS.days) {
            return outcome(CoachDataNudgeOutcome.OUTSIDE_READINESS_WINDOW)
        }
        val data = dataLoader.loadReadinessForPlan(userId, plan, zone)
            ?: return outcome(CoachDataNudgeOutcome.DATA_UNAVAILABLE)
        val readiness = NutritionCoachReadinessPolicy.evaluate(data, completedPlanDays)
            ?: return outcome(CoachDataNudgeOutcome.EVIDENCE_READY)
        val action = readiness.nextUsefulAction
            .takeIf { it == NutritionCoachNextUsefulAction.LOG_FOOD || it == NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY }
            ?: return outcome(CoachDataNudgeOutcome.NO_ACTION_TODAY, readiness.readinessReason.name)

        if (action == NutritionCoachNextUsefulAction.LOG_FOOD && dataLoader.loggedDays(userId, today, today.plusDays(1)) > 0) {
            return outcome(CoachDataNudgeOutcome.ACTION_ALREADY_COMPLETED, readiness.readinessReason.name)
        }
        if (!preferences.deliveryEligibility(userId, NotificationCategory.OPTIONAL_ANNOUNCEMENTS).enabled) {
            return outcome(CoachDataNudgeOutcome.PREFERENCE_DISABLED, readiness.readinessReason.name)
        }
        if (!pushSubscriptions.hasActiveSubscription(userId)) {
            return outcome(CoachDataNudgeOutcome.PUSH_UNAVAILABLE, readiness.readinessReason.name)
        }

        // The row lock makes the one-nudge-per-period rule safe across scheduler replicas,
        // even if the binding readiness reason changes between concurrent evaluations.
        repository.lockUser(userId)
        val evidencePeriodReference = "${plan.id}:${plan.startDate}"
        if (repository.hasNudgeForEvidencePeriod(userId, evidencePeriodReference)) {
            return outcome(CoachDataNudgeOutcome.EVIDENCE_PERIOD_ALREADY_NOTIFIED, readiness.readinessReason.name)
        }
        val matchingReminderTypes = when (action) {
            NutritionCoachNextUsefulAction.LOG_FOOD -> FOOD_REMINDER_TYPES
            NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY -> WEIGHT_REMINDER_TYPES
            else -> error("Unsupported same-day Coach action $action")
        }
        if (repository.hasRecentAcceptedReminder(
                userId = userId,
                types = matchingReminderTypes,
                fromInclusive = now.minus(properties.coachDataNudgeReminderLookback),
                toInclusive = now,
            )
        ) {
            return outcome(CoachDataNudgeOutcome.RECENT_MATCHING_REMINDER, readiness.readinessReason.name)
        }

        val reason = readiness.readinessReason.name
        val result = notifications.createWithOutcome(
            NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.COACH_DATA_NUDGE,
                templateData = mapOf("body" to TemplateVariableValue.Text(copyFor(action))),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(NUDGE_TTL),
                // Preserve a suppressed attempt for audit, but allow a new attempt on a
                // later local day while the readiness window is still actionable.
                idempotencyKey = "coach-data:$userId:${plan.startDate}:$today:$reason",
                requestId = "coach-data-${plan.id}-$today",
                sourceType = SOURCE_TYPE,
                sourceReference = evidencePeriodReference,
            ),
        )
        return outcome(result.toCoachOutcome(), reason)
    }

    private fun NotificationCreateOutcome.toCoachOutcome(): CoachDataNudgeOutcome = when {
        status == NotificationIntentStatus.ROUTED && created -> CoachDataNudgeOutcome.CREATED
        status == NotificationIntentStatus.ROUTED -> CoachDataNudgeOutcome.DUPLICATE
        status == NotificationIntentStatus.SUPPRESSED && reason == NotificationReason.BUDGET_LIMIT ->
            CoachDataNudgeOutcome.BUDGET_SUPPRESSED
        status == NotificationIntentStatus.UNDELIVERABLE -> CoachDataNudgeOutcome.UNDELIVERABLE
        else -> CoachDataNudgeOutcome.UNDELIVERABLE
    }

    private fun outcome(result: CoachDataNudgeOutcome, reason: String = "NONE"): CoachDataNudgeOutcome {
        metrics.coachDataNudgeEvaluation(result.name, reason)
        return result
    }

    private fun copyFor(action: NutritionCoachNextUsefulAction): String = when (action) {
        NutritionCoachNextUsefulAction.LOG_FOOD ->
            "برای اینکه بررسی برنامه‌ات عقب نیفتد، ثبت غذاهای امروز کمک می‌کند."
        NutritionCoachNextUsefulAction.LOG_WEIGHT_TODAY ->
            "برای کامل‌تر شدن روند وزنت، وزن امروزت را ثبت کن."
        else -> error("No notification copy exists for $action")
    }

    private companion object {
        const val RECALIBRATION_FEATURE = "goal_recalibration"
        const val SOURCE_TYPE = "COACH_DATA_NUDGE"
        val NUDGE_TTL: Duration = Duration.ofHours(12)
        val FOOD_REMINDER_TYPES = setOf(
            NotificationType.FOOD_MEAL_REMINDER,
            NotificationType.FOOD_INCOMPLETE_DAY_REMINDER,
        )
        val WEIGHT_REMINDER_TYPES = setOf(NotificationType.WEIGHT_REMINDER)
    }
}

@Component
class CoachDataReadinessNotificationJob(
    private val repository: CoachDataNudgeRepository,
    private val service: CoachDataReadinessNotificationService,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.notification.coach-data-nudge-poll-delay:30m}")
    fun run() {
        if (!properties.jobsEnabled || !properties.coachDataNudgeEnabled) return
        val startedAt = time.now()
        var cursor: UUID? = null
        var candidates = 0
        var created = 0
        while (true) {
            val page = repository.findEligiblePageAtLocalHour(
                afterUserId = cursor,
                batchSize = properties.coachDataNudgeBatchSize,
                localHour = properties.coachDataNudgeLocalHour,
                evaluatedAt = startedAt,
            )
            if (page.isEmpty()) break
            page.forEach { userId ->
                runCatching { service.evaluate(userId) }
                    .onSuccess { if (it == CoachDataNudgeOutcome.CREATED) created += 1 }
                    .onFailure { failure ->
                        metrics.coachDataNudgeEvaluation("FAILED", failure::class.simpleName ?: "UNKNOWN")
                        log.warn(
                            "event=coach_data_nudge outcome=user_failure userId={} reason={}",
                            userId,
                            failure::class.simpleName,
                        )
                    }
            }
            candidates += page.size
            cursor = page.last()
        }
        metrics.coachDataNudgeJobCompleted(candidates, created, Duration.between(startedAt, time.now()))
        log.info("event=coach_data_nudge outcome=completed candidates={} created={}", candidates, created)
    }
}
