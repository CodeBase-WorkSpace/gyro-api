package com.gyro.api.notification.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.gyro.api.common.request.TrustedClientIpResolver
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.TelegramAccountLinkingService
import com.gyro.api.notification.application.TelegramLinkConsumption
import com.gyro.api.notification.application.TelegramLinkRateLimitService
import com.gyro.api.notification.application.TelegramLinkState
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.delivery.TelegramDirectMessageSender
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/notifications/telegram")
@ConditionalOnProperty(
    prefix = "app.notification",
    name = ["telegram-enabled", "telegram-linking-enabled"],
    havingValue = "true",
)
class TelegramLinkingController(
    private val linking: TelegramAccountLinkingService,
    private val rateLimits: TelegramLinkRateLimitService,
    private val notifications: NotificationService,
    private val time: TimeProvider,
    private val metrics: NotificationMetrics,
    private val clientIps: TrustedClientIpResolver,
) {
    @PostMapping("/link")
    fun createLink(@AuthenticationPrincipal userId: String): TelegramLinkResponse {
        val recipient = UUID.fromString(userId)
        rateLimits.checkLink(recipient)
        val link = try {
            linking.createLink()
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram linking is unavailable.")
        }
        return TelegramLinkResponse(link.url)
    }

    @PostMapping("/link/confirm")
    fun confirmLink(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: TelegramLinkConfirmRequest,
        servletRequest: HttpServletRequest,
    ): TelegramLinkConfirmResponse {
        val recipient = UUID.fromString(userId)
        rateLimits.checkConfirm(recipient, clientIps.resolve(servletRequest))
        val result = linking.consume(recipient, request.code)
        metrics.telegramLinkConfirmation(result.outcome.name)
        log.info("event=telegram_link_confirmation userId={} outcome={}", recipient, result.outcome)
        when (result.outcome) {
            TelegramLinkConsumption.INVALID -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Telegram link code is invalid or expired.")
            TelegramLinkConsumption.COLLISION -> throw ResponseStatusException(HttpStatus.CONFLICT, "This Telegram account is linked to another Gyro account.")
            TelegramLinkConsumption.LINKED -> Unit
        }
        val now = time.now()
        notifications.create(
            notificationRequest(
                recipient,
                NotificationType.TELEGRAM_LINK_CONFIRMATION,
                now,
                "telegram-link-confirmation:$recipient:${now.epochSecond / 60}",
                "TELEGRAM_LINK_CONFIRMATION",
            ),
        )
        return TelegramLinkConfirmResponse(TelegramLinkState.LINKED)
    }

    @GetMapping("/status")
    fun status(@AuthenticationPrincipal userId: String): TelegramStatusResponse {
        val status = linking.status(UUID.fromString(userId))
        return TelegramStatusResponse(status.enabled, status.state, status.linkedAt, status.pendingUntil)
    }

    @DeleteMapping("/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(@AuthenticationPrincipal userId: String) {
        linking.unlink(UUID.fromString(userId))
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun test(@AuthenticationPrincipal userId: String): TelegramTestResponse {
        val recipient = UUID.fromString(userId)
        rateLimits.checkTest(recipient)
        if (!linking.hasActiveEndpoint(recipient)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Link Telegram before sending a test message.")
        }
        val now = time.now()
        val idempotencyKey = "telegram-test:$recipient:${now.epochSecond / 60}"
        val outcome = notifications.createWithOutcome(
            notificationRequest(recipient, NotificationType.TELEGRAM_TEST, now, idempotencyKey, "TELEGRAM_TEST"),
        )
        return TelegramTestResponse(outcome.intentId, outcome.created)
    }

    private companion object {
        val log = LoggerFactory.getLogger(TelegramLinkingController::class.java)
    }
}

@RestController
@RequestMapping("\${app.api.base-path}/integrations/telegram")
@ConditionalOnProperty(
    prefix = "app.notification",
    name = ["telegram-enabled", "telegram-linking-enabled"],
    havingValue = "true",
)
class TelegramWebhookController(
    private val linking: TelegramAccountLinkingService,
    private val telegram: TelegramDirectMessageSender,
) {
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun webhook(
        @RequestBody update: TelegramWebhookUpdate,
    ) {
        val message = update.message ?: return
        if (message.chat.type != "private" || message.from?.id != message.chat.id) return
        if (!START_COMMAND.matches(message.text.orEmpty())) return
        // Record only updates that can mutate linking state. Other bot traffic is deliberately
        // ignored so the durable deduplication table cannot grow from arbitrary chat messages.
        val code = linking.createCodeForWebhook(update.updateId, message.from.id, message.chat.id) ?: return
        val delivery = telegram.send(
            message.chat.id.toString(),
            "کد اتصال تلگرام به جیرو:\n\n${code.code}\n\nاین کد مدت کوتاهی معتبر است. آن را فقط در حساب جیروی خود وارد کنید.",
        )
        if (delivery.result.outcome !in setOf(AdapterOutcome.SUCCESS, AdapterOutcome.UNKNOWN_AFTER_SEND)) {
            linking.discardWebhookUpdate(update.updateId)
        }
    }

    private companion object {
        val START_COMMAND = Regex("^/start(?: link)?$")
    }
}

private fun notificationRequest(
    recipient: UUID,
    type: NotificationType,
    now: Instant,
    idempotencyKey: String,
    sourceType: String,
) = NotificationRequest(
    recipientUserId = recipient,
    type = type,
    templateData = emptyMap(),
    occurredAt = now,
    scheduledAt = now,
    expiresAt = now.plus(Duration.ofMinutes(10)),
    idempotencyKey = idempotencyKey,
    requestId = idempotencyKey.take(128),
    sourceType = sourceType,
    sourceReference = "user:$recipient",
)

data class TelegramLinkResponse(val url: String)
data class TelegramLinkConfirmRequest(
    @field:Pattern(regexp = "^[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjklmnpqrstuvwxyz2-9]{4}-?[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjklmnpqrstuvwxyz2-9]{4}$")
    val code: String,
)
data class TelegramLinkConfirmResponse(val state: TelegramLinkState)
data class TelegramStatusResponse(
    val enabled: Boolean,
    val state: TelegramLinkState,
    val linkedAt: Instant?,
    val pendingUntil: Instant?,
)
data class TelegramTestResponse(val intentId: UUID, val created: Boolean)
data class TelegramWebhookUpdate(
    @JsonProperty("update_id") val updateId: Long,
    val message: TelegramWebhookMessage? = null,
)
data class TelegramWebhookMessage(
    @JsonProperty("message_id") val messageId: Long,
    val from: TelegramWebhookUser? = null,
    val chat: TelegramWebhookChat,
    val text: String? = null,
)
data class TelegramWebhookUser(val id: Long)
data class TelegramWebhookChat(val id: Long, val type: String)
