package com.gyro.api.notification.application

import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class FoodReminderScheduleType { MEAL_REMINDER, INCOMPLETE_DAY_REMINDER, WEIGHT_REMINDER }
enum class FoodReminderMealType { BREAKFAST, LUNCH, DINNER }
enum class FoodReminderScheduleState { ACTIVE, PAUSED, CHANNEL_UNAVAILABLE, INVALID_TIMEZONE, PROCESSING_FAILED }

data class FoodReminderSchedule(
    val type: FoodReminderScheduleType,
    val mealType: FoodReminderMealType?,
    val localTime: LocalTime,
    val daysOfWeek: Set<Int>,
    val enabled: Boolean,
    val state: FoodReminderScheduleState,
)

@Service
class FoodReminderScheduleService(
    private val schedules: NotificationScheduleRepository,
    private val profiles: UserProfileRepository,
    private val subscriptions: PushSubscriptionService,
    private val preferences: NotificationPreferenceService,
    private val time: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun get(userId: UUID): List<FoodReminderSchedule> = schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).map { it.toModel() }

    @Transactional
    fun save(userId: UUID, schedule: FoodReminderSchedule): FoodReminderSchedule {
        validate(schedule)
        val profile = profiles.findByUser_Id(userId) ?: throw ResourceNotFoundException("User profile")
        val now = time.now()
        val existing = schedules.findByUserIdAndScheduleTypeAndMealType(userId, schedule.type, schedule.mealType)
        val state = when { !schedule.enabled -> FoodReminderScheduleState.PAUSED; subscriptions.hasActiveSubscription(userId) -> FoodReminderScheduleState.ACTIVE; else -> FoodReminderScheduleState.CHANNEL_UNAVAILABLE }
        val entity = existing ?: NotificationScheduleEntity(userId = userId, scheduleType = schedule.type, mealType = schedule.mealType, localTime = schedule.localTime, daysOfWeek = schedule.daysOfWeek.toIntArray(), enabled = schedule.enabled, state = state, consentedAt = now, actorUserId = userId)
        entity.localTime = schedule.localTime
        entity.daysOfWeek = schedule.daysOfWeek.toIntArray()
        entity.enabled = schedule.enabled
        entity.state = state
        entity.timezoneSource = "PROFILE"
        entity.consentedAt = now
        entity.actorUserId = userId
        entity.updatedAt = now
        entity.processingFailureCount = 0
        entity.lastProcessingFailureAt = null
        entity.lastProcessingFailureClass = null
        entity.nextEvaluationAt = nextOccurrence(now, ZoneId.of(profile.timezone), entity.localTime, schedule.daysOfWeek)
        val saved = schedules.save(entity)
        if (schedule.type == FoodReminderScheduleType.WEIGHT_REMINDER) {
            preferences.setCategoryPreference(userId, NotificationCategory.OPTIONAL_WEIGHT_LOGGING, schedule.enabled)
        }
        return saved.toModel()
    }

    private fun validate(schedule: FoodReminderSchedule) {
        val fieldErrors = buildList {
            if (schedule.daysOfWeek.isEmpty()) {
                add(ApiErrorResponse.FieldError("daysOfWeek", "At least one day must be selected.", "NOT_EMPTY"))
            } else if (schedule.daysOfWeek.any { it !in 1..7 }) {
                add(ApiErrorResponse.FieldError("daysOfWeek", "Days of week must use ISO values 1 through 7.", "OUT_OF_RANGE"))
            }
            if (schedule.type == FoodReminderScheduleType.MEAL_REMINDER && schedule.mealType == null) {
                add(ApiErrorResponse.FieldError("mealType", "Meal reminders require a meal type.", "REQUIRED"))
            }
            if (schedule.type != FoodReminderScheduleType.MEAL_REMINDER && schedule.mealType != null) {
                add(ApiErrorResponse.FieldError("mealType", "Only meal reminders may include a meal type.", "NOT_ALLOWED"))
            }
        }
        if (fieldErrors.isNotEmpty()) {
            throw FieldValidationException(fieldErrors = fieldErrors)
        }
    }

    private fun NotificationScheduleEntity.toModel() = FoodReminderSchedule(scheduleType, mealType, localTime, daysOfWeek.toSet(), enabled, state)

    private fun nextOccurrence(now: java.time.Instant, zone: ZoneId, localTime: LocalTime, days: Set<Int>): java.time.Instant {
        val localNow = now.atZone(zone)
        return (0..7).asSequence().map { localNow.toLocalDate().plusDays(it.toLong()) }
            .first { it.dayOfWeek.value in days && (it != localNow.toLocalDate() || localNow.toLocalTime().isBefore(localTime)) }
            .atTime(localTime).atZone(zone).toInstant()
    }
}
