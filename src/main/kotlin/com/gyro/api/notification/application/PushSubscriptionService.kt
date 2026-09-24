package com.gyro.api.notification.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.persistence.NotificationPushSubscriptionEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationPushSubscriptionRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationScheduleRepository
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PushSubscriptionCommand(val endpoint: String, val p256dh: String, val auth: String)
data class ActivePushSubscription(val endpoint: String, val p256dh: String, val auth: String, val fingerprint: String)
data class PushSubscriptionStatus(val hasActiveSubscription: Boolean, val activeSubscriptionCount: Int)

@Service
class PushSubscriptionService(
    private val subscriptions: NotificationPushSubscriptionRepository,
    private val schedules: NotificationScheduleRepository,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
    private val metrics: NotificationMetrics,
) {
    @Transactional
    fun active(userId: UUID): ActivePushSubscription? = activeAll(userId).firstOrNull()

    @Transactional
    fun activeAll(userId: UUID): List<ActivePushSubscription> {
        val active = mutableListOf<ActivePushSubscription>()
        for ((index, subscription) in subscriptions.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId).withIndex()) {
            if (index >= properties.webPushMaxSubscriptionsPerUser) {
                log.warn("event=push_subscription_unusable userId={} reason=SUBSCRIPTION_LIMIT keyVersion={}", userId, subscription.keyVersion)
                metrics.pushSubscriptionUnusable("SUBSCRIPTION_LIMIT", subscription.keyVersion)
                markRevoked(userId, subscription)
                continue
            }
            try {
                require(subscription.keyVersion == ACTIVE_KEY_VERSION) { "Unsupported Push subscription key version." }
                active += ActivePushSubscription(decrypt(subscription.endpointCiphertext), decrypt(subscription.p256dhCiphertext), decrypt(subscription.authCiphertext), subscription.endpointFingerprint)
            } catch (exception: Exception) {
                // Ciphertext/key-version failure means this endpoint cannot safely receive Push.
                val reason = if (subscription.keyVersion != ACTIVE_KEY_VERSION) "UNSUPPORTED_KEY_VERSION" else "DECRYPTION_FAILED"
                log.warn("event=push_subscription_unusable userId={} reason={} keyVersion={}", userId, reason, subscription.keyVersion)
                metrics.pushSubscriptionUnusable(reason, subscription.keyVersion)
                markRevoked(userId, subscription)
            }
        }
        return active
    }

    @Transactional(readOnly = true)
    fun hasActiveSubscription(userId: UUID): Boolean = subscriptions.existsByUserIdAndRevokedAtIsNullAndKeyVersion(userId, ACTIVE_KEY_VERSION)

    @Transactional(readOnly = true)
    fun hasActiveSubscription(userId: UUID, endpoint: String): Boolean {
        requireValidEndpoint(endpoint)
        return subscriptions.existsByUserIdAndEndpointFingerprintAndRevokedAtIsNullAndKeyVersion(
            userId,
            fingerprint(endpoint),
            ACTIVE_KEY_VERSION,
        )
    }

    @Transactional(readOnly = true)
    fun status(userId: UUID): PushSubscriptionStatus {
        val activeCount = subscriptions.countByUserIdAndRevokedAtIsNullAndKeyVersion(userId, ACTIVE_KEY_VERSION)
        return PushSubscriptionStatus(
            hasActiveSubscription = activeCount > 0,
            activeSubscriptionCount = activeCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    @Transactional(readOnly = true)
    fun userIdsWithActiveSubscription(): List<UUID> = subscriptions.findUserIdsWithActiveSubscription(ACTIVE_KEY_VERSION)
    @Transactional
    fun subscribe(userId: UUID, command: PushSubscriptionCommand) {
        requireValidEndpoint(command.endpoint)
        // Serialize subscription changes on the existing account row so the active-device
        // cap remains a strict invariant across concurrent requests and API instances.
        subscriptions.lockUserForSubscriptionUpdate(userId)
        val fingerprint = fingerprint(command.endpoint)
        val now = time.now()
        val subscription = subscriptions.findByUserIdAndEndpointFingerprint(userId, fingerprint)
            ?: NotificationPushSubscriptionEntity(userId = userId, endpointCiphertext = encrypt(command.endpoint), p256dhCiphertext = encrypt(command.p256dh), authCiphertext = encrypt(command.auth), keyVersion = "v1", endpointFingerprint = fingerprint)
        subscription.endpointCiphertext = encrypt(command.endpoint)
        subscription.p256dhCiphertext = encrypt(command.p256dh)
        subscription.authCiphertext = encrypt(command.auth)
        subscription.revokedAt = null
        subscription.updatedAt = now
        subscriptions.save(subscription)
        subscriptions.findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId)
            .drop(properties.webPushMaxSubscriptionsPerUser)
            .forEach { markRevoked(userId, it) }
        schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).filter { it.enabled }.forEach { it.state = FoodReminderScheduleState.ACTIVE; it.updatedAt = now }
    }

    @Transactional
    fun revoke(userId: UUID, fingerprint: String) {
        subscriptions.lockUserForSubscriptionUpdate(userId)
        val subscription = subscriptions.findByUserIdAndEndpointFingerprint(userId, fingerprint) ?: return
        markRevoked(userId, subscription)
    }

    @Transactional
    fun revokeEndpoint(userId: UUID, endpoint: String) {
        requireValidEndpoint(endpoint)
        revoke(userId, fingerprint(endpoint))
    }

    @Transactional
    fun revokeAll(userId: UUID) {
        subscriptions.lockUserForSubscriptionUpdate(userId)
        val now = time.now()
        subscriptions.revokeAllActiveByUserId(userId, now)
        schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId)
            .filter { it.enabled }
            .forEach { it.state = FoodReminderScheduleState.CHANNEL_UNAVAILABLE; it.updatedAt = now }
    }

    private fun markRevoked(userId: UUID, subscription: NotificationPushSubscriptionEntity) {
        if (subscription.revokedAt != null) return
        val now = time.now()
        subscription.revokedAt = now
        subscription.updatedAt = now
        subscriptions.flush()
        if (!subscriptions.existsByUserIdAndRevokedAtIsNullAndKeyVersion(userId, ACTIVE_KEY_VERSION)) {
            schedules.findAllByUserIdOrderByScheduleTypeAscMealTypeAsc(userId).filter { it.enabled }.forEach { it.state = FoodReminderScheduleState.CHANNEL_UNAVAILABLE; it.updatedAt = now }
        }
    }

    private fun encrypt(value: String): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(properties.pushSubscriptionEncryptionKey), "AES"), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun requireValidEndpoint(endpoint: String) {
        require(isAllowedWebPushEndpoint(endpoint, properties.webPushAllowedEndpointHosts)) { "Push endpoint is invalid." }
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(properties.pushSubscriptionEncryptionKey), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun fingerprint(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.endpointFingerprintKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ACTIVE_KEY_VERSION = "v1"
        val log = LoggerFactory.getLogger(PushSubscriptionService::class.java)
    }
}

internal fun isAllowedWebPushEndpoint(endpoint: String, allowedHosts: Set<String>): Boolean {
    if (endpoint.length !in 1..2_048) return false
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.fragment != null) return false
    if (uri.port !in setOf(-1, 443)) return false
    val host = uri.host?.lowercase() ?: return false
    return allowedHosts.any { allowed ->
        if (allowed.startsWith('.')) host.endsWith(allowed) && host.length > allowed.length
        else host == allowed
    }
}
