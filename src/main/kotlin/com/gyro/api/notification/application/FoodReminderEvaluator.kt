package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleClaim
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleQueueRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.ZoneId

@Service
class FoodReminderEvaluator(
    private val queue: NotificationScheduleQueueRepository,
    private val processor: FoodReminderScheduleProcessor,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    fun evaluateDueSchedules(): Int {
        val now = time.now()
        queue.recoverExpiredClaims(now)
        return queue.claimDue(now, "${properties.workerIdentity}:food-schedules", properties.batchSize, now.plus(properties.claimDuration)).count { claim ->
            val result = runCatching { processor.process(claim, now) }
            val failure = result.exceptionOrNull() ?: return@count true
            runCatching { processor.recordFailure(claim, now, failure) }
                .onFailure { recordingFailure ->
                    log.error("event=food_reminder_failure_recording_failed scheduleId={} originalException={} recordingException={}", claim.scheduleId, failure::class.simpleName, recordingFailure::class.simpleName)
                }
            log.warn("event=food_reminder_schedule_failed scheduleId={} exception={}", claim.scheduleId, failure::class.simpleName)
            false
        }
    }
    private companion object { private val log = LoggerFactory.getLogger(FoodReminderEvaluator::class.java) }
}

@Service
class FoodReminderScheduleProcessor(
    private val schedules: NotificationScheduleRepository,
    private val profiles: UserProfileRepository,
    private val subscriptions: PushSubscriptionService,
    private val preferences: NotificationPreferenceService,
    private val notifications: NotificationService,
    private val jdbc: JdbcTemplate,
    private val properties: NotificationProperties,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(claim: NotificationScheduleClaim, now: java.time.Instant) {
        val schedule = schedules.findById(claim.scheduleId).orElse(null) ?: return
        if (schedule.claimOwner != claim.owner || schedule.claimToken != claim.token) return
        try {
            val profile = profiles.findByUser_Id(schedule.userId)
            val zone = profile?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            if (zone == null) {
                schedule.enabled = false
                schedule.state = FoodReminderScheduleState.INVALID_TIMEZONE
                schedule.nextEvaluationAt = null
                return
            }
            val zonedNow = now.atZone(zone)
            val hasPush = subscriptions.hasActiveSubscription(schedule.userId)
            val consentCategory = if (schedule.scheduleType == FoodReminderScheduleType.WEIGHT_REMINDER) {
                NotificationCategory.OPTIONAL_WEIGHT_LOGGING
            } else {
                NotificationCategory.OPTIONAL_FOOD_LOGGING
            }
            val enabledByConsent = preferences.deliveryEligibility(schedule.userId, consentCategory).enabled
            if (!hasPush) schedule.state = FoodReminderScheduleState.CHANNEL_UNAVAILABLE
            else if (!enabledByConsent) schedule.state = FoodReminderScheduleState.PAUSED
            else if (zonedNow.dayOfWeek.value in schedule.daysOfWeek && !zonedNow.toLocalTime().isBefore(schedule.localTime) && !alreadyHandled(schedule.scheduleType, schedule.userId, zonedNow.toLocalDate().toString(), schedule.mealType?.name)) {
                val type = when (schedule.scheduleType) {
                    FoodReminderScheduleType.MEAL_REMINDER -> NotificationType.FOOD_MEAL_REMINDER
                    FoodReminderScheduleType.INCOMPLETE_DAY_REMINDER -> NotificationType.FOOD_INCOMPLETE_DAY_REMINDER
                    FoodReminderScheduleType.WEIGHT_REMINDER -> NotificationType.WEIGHT_REMINDER
                }
                val dedupeScope = if (schedule.scheduleType == FoodReminderScheduleType.WEIGHT_REMINDER) {
                    "weight-log:${schedule.userId}:${zonedNow.toLocalDate()}"
                } else {
                    "food-log:${schedule.userId}:${schedule.mealType ?: "day"}:${zonedNow.toLocalDate()}"
                }
                notifications.create(NotificationRequest(schedule.userId, type, emptyMap(), now, now, now.plus(Duration.ofMinutes(90)), dedupeScope, "food-schedule-${schedule.id}", "FOOD_REMINDER_SCHEDULE", schedule.id.toString()))
                schedule.state = FoodReminderScheduleState.ACTIVE
            }
            schedule.lastEvaluationAt = now
            schedule.nextEvaluationAt = nextOccurrence(now, zone, schedule.localTime, schedule.daysOfWeek.toSet())
            schedule.processingFailureCount = 0
            schedule.lastProcessingFailureAt = null
            schedule.lastProcessingFailureClass = null
        } finally {
            schedule.claimOwner = null; schedule.claimedAt = null; schedule.claimExpiresAt = null; schedule.claimToken = null; schedule.updatedAt = now
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(claim: NotificationScheduleClaim, now: java.time.Instant, failure: Throwable) {
        val schedule = schedules.findById(claim.scheduleId).orElse(null) ?: return
        if (schedule.claimOwner != claim.owner || schedule.claimToken != claim.token) return
        schedule.processingFailureCount += 1
        schedule.lastProcessingFailureAt = now
        schedule.lastProcessingFailureClass = failure::class.simpleName?.take(80)
        if (schedule.processingFailureCount >= properties.foodScheduleProcessingMaxAttempts) {
            schedule.enabled = false
            schedule.state = FoodReminderScheduleState.PROCESSING_FAILED
            schedule.nextEvaluationAt = null
        } else {
            schedule.nextEvaluationAt = now.plus(properties.foodScheduleProcessingBackoff)
        }
        schedule.claimOwner = null; schedule.claimedAt = null; schedule.claimExpiresAt = null; schedule.claimToken = null; schedule.updatedAt = now
    }
    private fun nextOccurrence(now: java.time.Instant, zone: ZoneId, localTime: java.time.LocalTime, days: Set<Int>) = (1..7).asSequence().map { now.atZone(zone).toLocalDate().plusDays(it.toLong()) }.first { it.dayOfWeek.value in days }.atTime(localTime).atZone(zone).toInstant()
    private fun alreadyHandled(scheduleType: FoodReminderScheduleType, userId: java.util.UUID, date: String, mealType: String?): Boolean =
        if (scheduleType == FoodReminderScheduleType.WEIGHT_REMINDER) weightLogged(userId, date) else alreadyLogged(userId, date, mealType)
    private fun weightLogged(userId: java.util.UUID, date: String): Boolean = jdbc.queryForObject("select exists(select 1 from weight_entries where user_id = ? and recorded_date = ?::date)", Boolean::class.java, userId, date) == true
    private fun alreadyLogged(userId: java.util.UUID, date: String, mealType: String?): Boolean = jdbc.queryForObject(if (mealType == null) "select exists(select 1 from diary_entries where user_id = ? and diary_date = ?::date)" else "select exists(select 1 from diary_entries where user_id = ? and diary_date = ?::date and meal_type = ?)", Boolean::class.java, *if (mealType == null) arrayOf<Any>(userId, date) else arrayOf<Any>(userId, date, mealType)) == true

}

@Component
class FoodReminderEvaluatorJob(private val evaluator: FoodReminderEvaluator, private val properties: NotificationProperties) {
    @Scheduled(fixedDelayString = "\${app.notification.food-reminder-poll-delay:1m}") fun run() { if (properties.jobsEnabled) evaluator.evaluateDueSchedules() }
}
