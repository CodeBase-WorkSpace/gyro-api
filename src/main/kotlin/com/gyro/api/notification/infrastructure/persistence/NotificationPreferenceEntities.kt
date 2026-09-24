package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.notification.domain.*
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "notification_user_settings")
class NotificationUserSettingsEntity(
    @Id @Column(name = "user_id") val userId: UUID,
    @Column(name = "quiet_hours_start", nullable = false) var quietHoursStart: LocalTime,
    @Column(name = "quiet_hours_end", nullable = false) var quietHoursEnd: LocalTime,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_preferences")
class NotificationPreferenceEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val category: NotificationCategory,
    @Column(nullable = false) var enabled: Boolean,
    @Column(name = "consent_source", nullable = false) var consentSource: String,
    @Column(name = "policy_version", nullable = false) var policyVersion: String,
    @Column(name = "actor_user_id", nullable = false) var actorUserId: UUID,
    @Column(name = "consented_at", nullable = false) var consentedAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_endpoint_health")
class NotificationEndpointHealthEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val channel: NotificationChannel,
    @Enumerated(EnumType.STRING) @Column(name = "account_source", nullable = false) val accountSource: NotificationEndpointSource,
    @Column(name = "destination_fingerprint", nullable = false) val destinationFingerprint: String,
    @Enumerated(EnumType.STRING) @Column(name = "health_state", nullable = false) var healthState: NotificationEndpointHealthState = NotificationEndpointHealthState.HEALTHY,
    @Enumerated(EnumType.STRING) @Column(name = "invalid_reason") var invalidReason: NotificationEndpointInvalidReason? = null,
    @Column(name = "last_success_at") var lastSuccessAt: Instant? = null,
    @Column(name = "last_failure_at") var lastFailureAt: Instant? = null,
    @Column(name = "diagnostic_delete_at") var diagnosticDeleteAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
