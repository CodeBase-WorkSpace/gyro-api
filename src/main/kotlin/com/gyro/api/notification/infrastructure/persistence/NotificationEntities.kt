package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.notification.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notification_templates")
class NotificationTemplateEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "template_key", nullable = false) val templateKey: String,
    @Column(nullable = false) val version: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val channel: NotificationChannel,
    @Column(nullable = false) val locale: String,
    val subject: String? = null,
    @Column(name = "plain_body", nullable = false) val plainBody: String,
    @Column(name = "html_body") val htmlBody: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_variables", nullable = false, columnDefinition = "jsonb")
    val requiredVariables: String,
    @Column(name = "content_hash", nullable = false) val contentHash: String,
    @Column(name = "activated_at", nullable = false) val activatedAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_intents")
class NotificationIntentEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "notification_type", nullable = false) val type: NotificationType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val category: NotificationCategory,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val risk: NotificationRisk,
    @Enumerated(EnumType.STRING) @Column(name = "route_strategy", nullable = false) val routeStrategy: NotificationRouteStrategy,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
    @Column(name = "scheduled_at", nullable = false) val scheduledAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "idempotency_key", nullable = false) val idempotencyKey: String,
    @Column(name = "source_type", nullable = false) val sourceType: String,
    @Column(name = "source_reference", nullable = false) val sourceReference: String,
    @Column(name = "request_id", nullable = false) val requestId: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "template_data", columnDefinition = "jsonb")
    val templateData: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: NotificationIntentStatus = NotificationIntentStatus.PENDING,
    @Enumerated(EnumType.STRING) var reason: NotificationReason? = null,
    @Column(name = "terminal_at") var terminalAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_deliveries")
class NotificationDeliveryEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "intent_id", nullable = false) val intentId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val channel: NotificationChannel,
    @Column(name = "endpoint_reference", nullable = false) val endpointReference: String,
    @Column(name = "provider_request_id", nullable = false) val providerRequestId: String,
    @Column(name = "adapter_key", nullable = false) val adapterKey: String,
    @Column(name = "template_key", nullable = false) val templateKey: String,
    @Column(name = "template_version", nullable = false) val templateVersion: Int,
    @Column(name = "template_locale", nullable = false) val templateLocale: String,
    @Column(name = "rendered_subject") var renderedSubject: String? = null,
    @Column(name = "rendered_plain_body") var renderedPlainBody: String?,
    @Column(name = "rendered_html_body") var renderedHtmlBody: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: NotificationDeliveryStatus = NotificationDeliveryStatus.PENDING,
    @Enumerated(EnumType.STRING) var reason: NotificationReason? = null,
    @Column(name = "due_at", nullable = false) var dueAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "next_attempt_at") var nextAttemptAt: Instant? = null,
    @Column(name = "claim_owner") var claimOwner: String? = null,
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "claim_expires_at") var claimExpiresAt: Instant? = null,
    @Column(name = "claim_token") var claimToken: UUID? = null,
    @Column(name = "provider_reference_digest") var providerReferenceDigest: String? = null,
    @Column(name = "content_purge_at") var contentPurgeAt: Instant? = null,
    @Column(name = "content_purged_at") var contentPurgedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_attempts")
class NotificationAttemptEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "delivery_id", nullable = false) val deliveryId: UUID,
    @Column(name = "attempt_number", nullable = false) val attemptNumber: Int,
    @Column(name = "started_at", nullable = false) val startedAt: Instant,
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "duration_ms") var durationMs: Long? = null,
    @Enumerated(EnumType.STRING) var outcome: AdapterOutcome? = null,
    @Enumerated(EnumType.STRING) var classification: AdapterClassification? = null,
    @Column(name = "estimated_sms_cost") var estimatedSmsCost: BigDecimal? = null,
    @Column(name = "provider_reference_digest") var providerReferenceDigest: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
