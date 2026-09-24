package com.gyro.api.notification.web

import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.notification.application.AdminAnnouncementService
import com.gyro.api.notification.application.AdminNotificationTestService
import com.gyro.api.notification.application.MandatoryReceiptRecoveryService
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.security.core.annotation.AuthenticationPrincipal
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
class AdminNotificationController(
    private val intents: NotificationIntentRepository,
    private val deliveries: NotificationDeliveryRepository,
    private val receiptRecovery: MandatoryReceiptRecoveryService,
    private val announcements: AdminAnnouncementService,
    private val notificationTests: AdminNotificationTestService,
) {
    @GetMapping("/runtime")
    fun runtime() = notificationTests.runtime()

    @PostMapping("/telegram/webhook")
    fun ensureTelegramWebhook() = notificationTests.ensureTelegramWebhook()

    @PostMapping("/user-tests")
    fun sendUserTest(
        @RequestBody @jakarta.validation.Valid request: AdminUserNotificationTestRequest,
    ) = notificationTests.send(request.userId, request.webPush, request.telegram, request.requestId)

    @GetMapping
    fun list(
        @RequestParam(required = false) type: NotificationType?,
        @RequestParam(required = false) status: NotificationIntentStatus?,
        @RequestParam(required = false) reason: NotificationReason?,
        @RequestParam(required = false) channel: NotificationChannel?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) size: Int,
    ): PageResponse<AdminNotificationSummaryResponse> {
        val result = intents.findAll(
            specification(type, status, reason, channel),
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        )
        val deliveriesByIntent = deliveries.findAllByIntentIdIn(result.content.map { it.id })
            .groupBy { it.intentId }
        val items = result.content.map { intent ->
                val matchingDeliveries = deliveriesByIntent[intent.id].orEmpty()
                AdminNotificationSummaryResponse(
                    intentId = intent.id,
                    type = intent.type,
                    category = intent.category,
                    status = intent.status,
                    reason = intent.reason,
                    createdAt = intent.createdAt,
                    deliveries = matchingDeliveries.map { AdminNotificationDeliveryResponse(it.id, it.channel, it.status, it.reason, it.dueAt) },
                )
            }
        return PageResponse(items, result.number, result.size, result.totalElements, result.totalPages)
    }

    @PostMapping("/announcements")
    fun sendAnnouncement(
        @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid request: AdminAnnouncementRequest,
        @AuthenticationPrincipal operatorId: String,
    ): AdminAnnouncementResponse {
        val result = announcements.send(
            operatorId = UUID.fromString(operatorId),
            announcementId = request.announcementId,
            title = request.title,
            body = request.body,
            targets = com.gyro.api.notification.application.AdminAnnouncementTargets(
                webPush = request.targets?.webPush ?: true,
                telegramChannel = request.targets?.telegramChannel ?: false,
            ),
            recipientUserId = request.recipientUserId,
        )
        return AdminAnnouncementResponse(
            announcementId = result.announcementId,
            recipientsTargeted = result.recipientsTargeted,
            intentsCreated = result.intentsCreated,
            intentsExisting = result.intentsExisting,
            failures = result.failures,
            telegramQueued = result.telegramQueued,
            telegramAlreadyQueued = result.telegramAlreadyQueued,
        )
    }

    @PostMapping("/{intentId}/deliveries/{deliveryId}/manual-retry")
    fun retryFailedPaymentReceipt(
        @PathVariable intentId: UUID,
        @PathVariable deliveryId: UUID,
        @AuthenticationPrincipal operatorId: String,
    ): ManualReceiptRetryResponse = ManualReceiptRetryResponse(
        receiptRecovery.retryFailedPaymentReceipt(intentId, deliveryId, UUID.fromString(operatorId)),
    )

    private fun specification(
        type: NotificationType?,
        status: NotificationIntentStatus?,
        reason: NotificationReason?,
        channel: NotificationChannel?,
    ): Specification<com.gyro.api.notification.infrastructure.persistence.NotificationIntentEntity> = Specification { root, query, builder ->
        val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
        type?.let { predicates += builder.equal(root.get<NotificationType>("type"), it) }
        status?.let { predicates += builder.equal(root.get<NotificationIntentStatus>("status"), it) }
        reason?.let { predicates += builder.equal(root.get<NotificationReason>("reason"), it) }
        channel?.let {
            val delivery = query.subquery(Long::class.java)
            val deliveryRoot = delivery.from(com.gyro.api.notification.infrastructure.persistence.NotificationDeliveryEntity::class.java)
            delivery.select(builder.literal(1L))
                .where(
                    builder.equal(deliveryRoot.get<java.util.UUID>("intentId"), root.get<java.util.UUID>("id")),
                    builder.equal(deliveryRoot.get<NotificationChannel>("channel"), it),
                )
            predicates += builder.exists(delivery)
        }
        builder.and(*predicates.toTypedArray())
    }
}

data class AdminNotificationSummaryResponse(
    val intentId: java.util.UUID,
    val type: NotificationType,
    val category: NotificationCategory,
    val status: NotificationIntentStatus,
    val reason: NotificationReason?,
    val createdAt: java.time.Instant,
    val deliveries: List<AdminNotificationDeliveryResponse>,
)

data class AdminNotificationDeliveryResponse(
    val deliveryId: java.util.UUID,
    val channel: NotificationChannel,
    val status: NotificationDeliveryStatus,
    val reason: NotificationReason?,
    val dueAt: java.time.Instant,
)

data class ManualReceiptRetryResponse(
    val retryIntentId: UUID,
)

data class AdminAnnouncementTargetsRequest(
    val webPush: Boolean = true,
    val telegramChannel: Boolean = false,
)

data class AdminAnnouncementRequest(
    val announcementId: UUID,
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(max = 80)
    val title: String,
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(max = 500)
    val body: String,
    val targets: AdminAnnouncementTargetsRequest? = null,
    val recipientUserId: UUID? = null,
)

data class AdminAnnouncementResponse(
    val announcementId: UUID,
    val recipientsTargeted: Int,
    val intentsCreated: Int,
    val intentsExisting: Int,
    val failures: Int,
    val telegramQueued: Boolean,
    val telegramAlreadyQueued: Boolean,
)

data class AdminUserNotificationTestRequest(
    val userId: UUID,
    val requestId: UUID,
    val webPush: Boolean = true,
    val telegram: Boolean = false,
)
