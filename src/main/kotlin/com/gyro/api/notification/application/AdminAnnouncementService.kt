package com.gyro.api.notification.application

import com.gyro.api.common.error.AdminAnnouncementAudienceTooLargeException
import com.gyro.api.common.error.AdminAnnouncementContentConflictException
import com.gyro.api.common.error.InvalidAdminAnnouncementException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.auth.domain.UserStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.TemplateVariableValue
import com.gyro.api.notification.infrastructure.persistence.AdminAnnouncementEntity
import com.gyro.api.notification.infrastructure.persistence.AdminAnnouncementRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class AdminAnnouncementTargets(
    val webPush: Boolean = true,
    val telegramChannel: Boolean = false,
)

data class AdminAnnouncementResult(
    val announcementId: UUID,
    val recipientsTargeted: Int,
    val intentsCreated: Int,
    val intentsExisting: Int,
    val failures: Int,
    val telegramQueued: Boolean,
    val telegramAlreadyQueued: Boolean,
)

@Service
class AdminAnnouncementService(
    private val announcements: AdminAnnouncementRepository,
    private val pushSubscriptions: PushSubscriptionService,
    private val notifications: NotificationService,
    private val users: UserRepository,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    fun send(
        operatorId: UUID,
        announcementId: UUID,
        title: String,
        body: String,
        targets: AdminAnnouncementTargets = AdminAnnouncementTargets(),
        recipientUserId: UUID? = null,
    ): AdminAnnouncementResult {
        if (title.isBlank() || title.length > MAX_TITLE_LENGTH) {
            throw InvalidAdminAnnouncementException(
                "Announcement title must be between 1 and $MAX_TITLE_LENGTH characters.",
            )
        }
        if (body.isBlank() || body.length > MAX_BODY_LENGTH) {
            throw InvalidAdminAnnouncementException(
                "Announcement body must be between 1 and $MAX_BODY_LENGTH characters.",
            )
        }
        if (!targets.webPush && !targets.telegramChannel) {
            throw InvalidAdminAnnouncementException("Announcement must have at least one target.")
        }
        if (targets.telegramChannel && !properties.telegramEnabled) {
            throw InvalidAdminAnnouncementException("Telegram notifications are disabled.")
        }
        if (recipientUserId != null && (!targets.webPush || targets.telegramChannel)) {
            throw InvalidAdminAnnouncementException(
                "A user-targeted announcement can only use personal Web Push.",
            )
        }
        // The announcement aggregate binds the id to immutable content and targets before any
        // fan-out. Existing ids are validated before mutable recipient state so an accepted
        // targeted announcement remains safely retryable after the account changes state.
        val announcement = announcements.findById(announcementId).orElse(null)?.let {
            validateMatches(it, contentHash(title, body), targets, recipientUserId)
        } ?: run {
            validateRecipient(recipientUserId)
            persistOrValidateNew(operatorId, announcementId, title, body, targets, recipientUserId)
        }
        val now = time.now()
        val templateData = mapOf(
            "title" to TemplateVariableValue.Text(announcement.title),
            "body" to TemplateVariableValue.Text(announcement.body),
        )

        val targetedRecipient = announcement.recipientUserId
        val userIds = when {
            !announcement.targetWebPush -> emptyList()
            targetedRecipient != null && pushSubscriptions.hasActiveSubscription(targetedRecipient) ->
                listOf(targetedRecipient)
            targetedRecipient != null -> emptyList()
            else -> pushSubscriptions.userIdsWithActiveSubscription()
        }
        if (userIds.size > MAX_RECIPIENTS) {
            throw AdminAnnouncementAudienceTooLargeException(userIds.size, MAX_RECIPIENTS)
        }
        var created = 0
        var existing = 0
        var failures = 0
        userIds.forEach { userId ->
            runCatching {
                notifications.createWithOutcome(
                    NotificationRequest(
                        recipientUserId = userId,
                        type = NotificationType.ADMIN_ANNOUNCEMENT,
                        templateData = templateData,
                        occurredAt = now,
                        scheduledAt = now,
                        expiresAt = now.plus(EXPIRY),
                        idempotencyKey = "admin-announcement:${announcement.id}:$userId",
                        requestId = announcement.id.toString(),
                        sourceType = SOURCE_TYPE,
                        sourceReference = "operator:${announcement.createdBy}",
                    ),
                )
            }.onSuccess { outcome -> if (outcome.created) created++ else existing++ }.onFailure { failures++ }
        }

        var telegramQueued = false
        var telegramAlreadyQueued = false
        if (announcement.targetTelegramChannel) {
            // Intents require a user FK; the acting operator stands in for the channel recipient.
            runCatching {
                notifications.createWithOutcome(
                    NotificationRequest(
                        recipientUserId = operatorId,
                        type = NotificationType.TELEGRAM_CHANNEL_POST,
                        templateData = templateData,
                        occurredAt = now,
                        scheduledAt = now,
                        expiresAt = now.plus(EXPIRY),
                        idempotencyKey = "telegram-channel-post:${announcement.id}",
                        requestId = announcement.id.toString(),
                        sourceType = SOURCE_TYPE,
                        sourceReference = "operator:${announcement.createdBy}",
                    ),
                )
            }.onSuccess { outcome ->
                if (outcome.created) telegramQueued = true else telegramAlreadyQueued = true
            }.onFailure { failures++ }
        }
        // Content is intentionally absent from logs; only counts and identifiers are recorded.
        log.info(
            "event=admin_announcement_fanout announcementId={} operatorId={} targeted={} created={} existing={} telegramQueued={} telegramAlreadyQueued={} failures={}",
            announcement.id,
            operatorId,
            userIds.size,
            created,
            existing,
            telegramQueued,
            telegramAlreadyQueued,
            failures,
        )
        return AdminAnnouncementResult(announcement.id, userIds.size, created, existing, failures, telegramQueued, telegramAlreadyQueued)
    }

    private fun validateRecipient(recipientUserId: UUID?) {
        if (recipientUserId == null) return
        val recipient = users.findById(recipientUserId).orElseThrow {
            ResourceNotFoundException("Announcement recipient")
        }
        if (recipient.status != UserStatus.ACTIVE) {
            throw InvalidAdminAnnouncementException("Announcement recipient must be an active user.")
        }
    }

    private fun persistOrValidateNew(
        operatorId: UUID,
        announcementId: UUID,
        title: String,
        body: String,
        targets: AdminAnnouncementTargets,
        recipientUserId: UUID?,
    ): AdminAnnouncementEntity {
        val hash = contentHash(title, body)
        return runCatching {
            announcements.saveAndFlush(
                AdminAnnouncementEntity(
                    id = announcementId,
                    title = title,
                    body = body,
                    contentHash = hash,
                    targetWebPush = targets.webPush,
                    targetTelegramChannel = targets.telegramChannel,
                    recipientUserId = recipientUserId,
                    createdBy = operatorId,
                    createdAt = time.now(),
                    updatedAt = time.now(),
                ),
            )
        }.getOrElse { failure ->
            // A concurrent submit of the same id won the insert; validate against its record.
            if (failure is DataIntegrityViolationException) {
                validateMatches(announcements.findById(announcementId).orElseThrow(), hash, targets, recipientUserId)
            } else {
                throw failure
            }
        }
    }

    private fun validateMatches(
        existing: AdminAnnouncementEntity,
        hash: String,
        targets: AdminAnnouncementTargets,
        recipientUserId: UUID?,
    ): AdminAnnouncementEntity {
        if (existing.contentHash != hash) {
            throw AdminAnnouncementContentConflictException(existing.id, "content")
        }
        if (existing.targetWebPush != targets.webPush || existing.targetTelegramChannel != targets.telegramChannel) {
            throw AdminAnnouncementContentConflictException(existing.id, "targets")
        }
        if (existing.recipientUserId != recipientUserId) {
            throw AdminAnnouncementContentConflictException(existing.id, "recipient")
        }
        return existing
    }

    companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_BODY_LENGTH = 500

        // Synchronous fan-out bound: beyond this the HTTP request cannot safely complete the loop
        // and an asynchronous, cursor-based fan-out job must be built instead.
        const val MAX_RECIPIENTS = 5_000

        private const val SOURCE_TYPE = "ADMIN_ANNOUNCEMENT"
        private val EXPIRY = Duration.ofHours(24)
        private val log = LoggerFactory.getLogger(AdminAnnouncementService::class.java)

        fun contentHash(title: String, body: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$title\n$body".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
