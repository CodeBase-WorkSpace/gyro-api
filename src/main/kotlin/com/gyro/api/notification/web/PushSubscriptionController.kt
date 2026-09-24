package com.gyro.api.notification.web

import com.gyro.api.notification.application.PushSubscriptionCommand
import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.application.PushSubscriptionRateLimitService
import com.gyro.api.notification.application.isAllowedWebPushEndpoint
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import com.gyro.api.common.time.TimeProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID
import java.util.Base64

@RestController
@RequestMapping("\${app.api.base-path}/notifications/push-subscriptions")
class PushSubscriptionController(
    private val subscriptions: PushSubscriptionService,
    private val properties: com.gyro.api.notification.config.NotificationProperties,
    private val notifications: NotificationService,
    private val time: TimeProvider,
    private val intents: NotificationIntentRepository,
    private val rateLimits: PushSubscriptionRateLimitService,
) {
    @GetMapping("/public-key") fun publicKey(): Map<String, String> {
        requireWebPushEnabled()
        return mapOf("publicKey" to requireNotNull(properties.webPushPublicKey?.takeIf(String::isNotBlank)))
    }

    @PostMapping fun subscribe(@AuthenticationPrincipal userId: String, @Valid @RequestBody request: PushSubscriptionRequest) {
        requireWebPushEnabled()
        val recipient = UUID.fromString(userId)
        rateLimits.checkSubscribe(recipient)
        requireAllowedEndpoint(request.endpoint)
        requireBrowserKey(request.p256dh, BrowserKeyType.P256DH)
        requireBrowserKey(request.auth, BrowserKeyType.AUTH)
        subscriptions.subscribe(recipient, PushSubscriptionCommand(request.endpoint, request.p256dh, request.auth))
    }

    @GetMapping("/status")
    fun status(@AuthenticationPrincipal userId: String): PushSubscriptionStatusResponse {
        requireWebPushEnabled()
        val result = subscriptions.status(UUID.fromString(userId))
        return PushSubscriptionStatusResponse(
            enabled = true,
            publicKeyAvailable = !properties.webPushPublicKey.isNullOrBlank(),
            hasActiveSubscription = result.hasActiveSubscription,
            activeSubscriptionCount = result.activeSubscriptionCount,
        )
    }

    @PostMapping("/status/current")
    fun currentStatus(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: CurrentPushSubscriptionStatusRequest,
    ): PushSubscriptionStatusResponse {
        requireWebPushEnabled()
        requireAllowedEndpoint(request.endpoint)
        val recipient = UUID.fromString(userId)
        val result = subscriptions.status(recipient)
        return PushSubscriptionStatusResponse(
            enabled = true,
            publicKeyAvailable = !properties.webPushPublicKey.isNullOrBlank(),
            hasActiveSubscription = subscriptions.hasActiveSubscription(recipient, request.endpoint),
            activeSubscriptionCount = result.activeSubscriptionCount,
        )
    }

    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribe(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: RevokePushSubscriptionRequest,
    ) {
        requireWebPushEnabled()
        requireAllowedEndpoint(request.endpoint)
        subscriptions.revokeEndpoint(UUID.fromString(userId), request.endpoint)
    }

    @DeleteMapping("/all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unsubscribeAll(@AuthenticationPrincipal userId: String) {
        requireWebPushEnabled()
        subscriptions.revokeAll(UUID.fromString(userId))
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun test(@AuthenticationPrincipal userId: String): PushTestResponse {
        requireWebPushEnabled()
        val recipient = UUID.fromString(userId)
        rateLimits.checkTest(recipient)
        if (!subscriptions.hasActiveSubscription(recipient)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Enable browser notifications before sending a test.")
        }
        val now = time.now()
        val bucketKey = "push-test:$recipient:${now.epochSecond / 60}"
        val existing = intents.findBySourceTypeAndIdempotencyKey("PUSH_TEST", bucketKey)
        val idempotencyKey = if (existing?.status in TERMINAL_FAILURE_STATUSES) {
            "$bucketKey:retry:1"
        } else {
            bucketKey
        }
        val outcome = notifications.createWithOutcome(
            NotificationRequest(
                recipientUserId = recipient,
                type = NotificationType.PUSH_TEST,
                templateData = emptyMap(),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(java.time.Duration.ofMinutes(10)),
                idempotencyKey = idempotencyKey,
                requestId = idempotencyKey,
                sourceType = "PUSH_TEST",
                sourceReference = "user:$recipient",
            ),
        )
        return PushTestResponse(outcome.intentId, outcome.created)
    }

    private fun requireWebPushEnabled() {
        if (!properties.webPushEnabled || properties.webPushPublicKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Web Push is unavailable.")
        }
    }

    private fun requireBrowserKey(value: String, type: BrowserKeyType) {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
        val valid = when (type) {
            BrowserKeyType.P256DH -> decoded?.size == 65 && decoded.firstOrNull() == UNCOMPRESSED_P256_PREFIX
            BrowserKeyType.AUTH -> decoded?.size == 16
        }
        if (!value.matches(BASE64_URL) || !valid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${type.parameterName} key is invalid.")
        }
    }

    private fun requireAllowedEndpoint(endpoint: String) {
        if (!isAllowedWebPushEndpoint(endpoint, properties.webPushAllowedEndpointHosts)) {
            log.warn("event=web_push_endpoint_rejected host={}", safeEndpointHost(endpoint))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Push endpoint is invalid.")
        }
    }

    private companion object {
        val BASE64_URL = Regex("^[A-Za-z0-9_-]+$")
        val TERMINAL_FAILURE_STATUSES = setOf(NotificationIntentStatus.FAILED, NotificationIntentStatus.UNDELIVERABLE)
        const val UNCOMPRESSED_P256_PREFIX: Byte = 0x04
        val log = LoggerFactory.getLogger(PushSubscriptionController::class.java)
    }

    private enum class BrowserKeyType(val parameterName: String) { P256DH("p256dh"), AUTH("auth") }
}

private fun safeEndpointHost(endpoint: String): String = runCatching { URI(endpoint).host }
    .getOrNull()
    ?.lowercase()
    ?.take(253)
    ?: "INVALID"
data class PushSubscriptionRequest(
    @field:NotBlank
    @field:Size(max = 2_048)
    @field:Pattern(regexp = HTTPS_ENDPOINT_PATTERN)
    val endpoint: String,
    @field:NotBlank
    @field:Size(min = 4, max = 512)
    @field:Pattern(regexp = BASE64_URL_PATTERN)
    val p256dh: String,
    @field:NotBlank
    @field:Size(min = 4, max = 512)
    @field:Pattern(regexp = BASE64_URL_PATTERN)
    val auth: String,
)
data class CurrentPushSubscriptionStatusRequest(
    @field:NotBlank
    @field:Size(max = 2_048)
    @field:Pattern(regexp = HTTPS_ENDPOINT_PATTERN)
    val endpoint: String,
)
data class RevokePushSubscriptionRequest(
    @field:NotBlank
    @field:Size(max = 2_048)
    @field:Pattern(regexp = HTTPS_ENDPOINT_PATTERN)
    val endpoint: String,
)
data class PushSubscriptionStatusResponse(
    val enabled: Boolean,
    val publicKeyAvailable: Boolean,
    val hasActiveSubscription: Boolean,
    val activeSubscriptionCount: Int,
)
data class PushTestResponse(val intentId: UUID, val created: Boolean)

private const val HTTPS_ENDPOINT_PATTERN = "^https://\\S+$"
private const val BASE64_URL_PATTERN = "^[A-Za-z0-9_-]+$"
