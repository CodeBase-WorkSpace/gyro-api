package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.notification.application.FoodReminderMealType
import com.gyro.api.notification.application.FoodReminderScheduleState
import com.gyro.api.notification.application.FoodReminderScheduleType
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "notification_schedules")
class NotificationScheduleEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "schedule_type", nullable = false) val scheduleType: FoodReminderScheduleType,
    @Enumerated(EnumType.STRING) @Column(name = "meal_type") val mealType: FoodReminderMealType?,
    @Column(name = "local_time", nullable = false) var localTime: LocalTime,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "days_of_week", nullable = false) var daysOfWeek: IntArray,
    @Column(nullable = false) var enabled: Boolean,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: FoodReminderScheduleState,
    @Column(name = "timezone_source", nullable = false) var timezoneSource: String = "PROFILE",
    @Column(name = "next_evaluation_at") var nextEvaluationAt: Instant? = null,
    @Column(name = "last_evaluation_at") var lastEvaluationAt: Instant? = null,
    @Column(name = "claim_owner") var claimOwner: String? = null,
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "claim_expires_at") var claimExpiresAt: Instant? = null,
    @Column(name = "claim_token") var claimToken: UUID? = null,
    @Column(name = "processing_failure_count", nullable = false) var processingFailureCount: Int = 0,
    @Column(name = "last_processing_failure_at") var lastProcessingFailureAt: Instant? = null,
    @Column(name = "last_processing_failure_class") var lastProcessingFailureClass: String? = null,
    @Column(name = "consented_at", nullable = false) var consentedAt: Instant,
    @Column(name = "actor_user_id", nullable = false) var actorUserId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

interface NotificationScheduleRepository : org.springframework.data.jpa.repository.JpaRepository<NotificationScheduleEntity, UUID> {
    fun findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId: UUID): List<NotificationScheduleEntity>
    fun findByUserIdAndScheduleTypeAndMealType(userId: UUID, scheduleType: FoodReminderScheduleType, mealType: FoodReminderMealType?): NotificationScheduleEntity?
    fun findAllByEnabledTrue(): List<NotificationScheduleEntity>
}
