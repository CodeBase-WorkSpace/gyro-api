package com.gyro.api.notification.application

import com.gyro.api.common.ratelimit.RateLimitService
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramEndpointEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramEndpointRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramLinkCodeEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramLinkCodeRepository
import com.gyro.api.notification.infrastructure.persistence.NotificationTelegramLinkTokenRepository
import com.gyro.api.notification.infrastructure.persistence.TelegramEndpointState
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class TelegramLinkState { UNAVAILABLE, UNLINKED, PENDING, LINKED, BLOCKED, RELINK_REQUIRED }
enum class TelegramLinkConsumption { LINKED, INVALID, COLLISION }

data class TelegramLinkStatus(
    val enabled: Boolean,
    val state: TelegramLinkState,
    val linkedAt: Instant? = null,
    val pendingUntil: Instant? = null,
)

data class TelegramLink(val url: String)
data class TelegramLinkCode(val id: UUID, val code: String, val expiresAt: Instant)
data class ActiveTelegramEndpoint(val chatId: String)
data class TelegramLinkResult(val outcome: TelegramLinkConsumption, val userId: UUID? = null)

@Service
class TelegramLinkRateLimitService(
    private val rateLimits: RateLimitService,
    @Value("\${app.rate-limit.telegram.link-per-user-limit}") private val linkLimit: Long,
    @Value("\${app.rate-limit.telegram.link-window}") private val linkWindow: Duration,
    @Value("\${app.rate-limit.telegram.confirm-per-user-limit}") private val confirmPerUserLimit: Long,
    @Value("\${app.rate-limit.telegram.confirm-per-ip-limit}") private val confirmPerIpLimit: Long,
    @Value("\${app.rate-limit.telegram.confirm-window}") private val confirmWindow: Duration,
    @Value("\${app.rate-limit.telegram.test-per-user-limit}") private val testLimit: Long,
    @Value("\${app.rate-limit.telegram.test-window}") private val testWindow: Duration,
) {
    fun checkLink(userId: UUID) = check("link", userId, linkLimit, linkWindow)
    fun checkConfirm(userId: UUID, clientIp: String) {
        check("confirm", userId, confirmPerUserLimit, confirmWindow)
        rateLimits.check("rate:telegram:confirm:ip:${hash(clientIp)}", confirmPerIpLimit, confirmWindow)
    }
    fun checkTest(userId: UUID) = check("test", userId, testLimit, testWindow)

    private fun check(action: String, userId: UUID, limit: Long, window: Duration) {
        rateLimits.check("rate:telegram:$action:user:${hash(userId.toString())}", limit, window)
    }

    private fun hash(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )
}

@Service
class TelegramAccountLinkingService(
    private val tokens: NotificationTelegramLinkTokenRepository,
    private val codes: NotificationTelegramLinkCodeRepository,
    private val endpoints: NotificationTelegramEndpointRepository,
    private val jdbc: JdbcTemplate,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    fun createLink(): TelegramLink {
        require(properties.telegramEnabled && properties.telegramLinkingEnabled) { "Telegram linking is unavailable." }
        return TelegramLink("https://t.me/${botIdentity()}?start=link")
    }

    @Transactional(readOnly = true)
    fun status(userId: UUID): TelegramLinkStatus {
        if (!properties.telegramEnabled || !properties.telegramLinkingEnabled) {
            return TelegramLinkStatus(false, TelegramLinkState.UNAVAILABLE)
        }
        val endpoint = endpoints.findByUserIdAndBotIdentity(userId, botIdentity())
        if (endpoint?.state == TelegramEndpointState.ACTIVE) {
            return TelegramLinkStatus(true, TelegramLinkState.LINKED, endpoint.linkedAt)
        }
        val state = when (endpoint?.state) {
            TelegramEndpointState.BLOCKED -> TelegramLinkState.BLOCKED
            TelegramEndpointState.REVOKED -> TelegramLinkState.RELINK_REQUIRED
            else -> TelegramLinkState.UNLINKED
        }
        return TelegramLinkStatus(true, state, endpoint?.linkedAt)
    }

    @Transactional
    fun unlink(userId: UUID) {
        endpoints.lockUserForTelegramUpdate(userId)
        val now = time.now()
        tokens.findAllByUserIdAndConsumedAtIsNull(userId).forEach {
            it.consumedAt = now
            it.updatedAt = now
        }
        endpoints.findByUserIdAndBotIdentity(userId, botIdentity())?.apply {
            state = TelegramEndpointState.REVOKED
            revokedAt = now
            updatedAt = now
        }
    }

    @Transactional
    fun createCodeForWebhook(updateId: Long, telegramUserId: Long, chatId: Long): TelegramLinkCode? {
        if (updateId < 0 || telegramUserId <= 0 || chatId <= 0) return null
        val recorded = jdbc.update(
            """insert into notification_telegram_webhook_updates(bot_identity, update_id, received_at)
                values (?, ?, ?) on conflict do nothing""",
            botIdentity(), updateId, Timestamp.from(time.now()),
        ) == 1
        if (!recorded) return null
        // Keep replay registration and code creation in one commit. A crash between two
        // transactions would otherwise make Telegram's retry look processed without a code.
        return createCode(telegramUserId, chatId)
    }

    @Transactional
    fun createCode(telegramUserId: Long, chatId: Long): TelegramLinkCode? {
        if (telegramUserId <= 0 || chatId <= 0) return null
        val now = time.now()
        val identity = botIdentity()
        val userFingerprint = fingerprint("user:$telegramUserId")
        val chatFingerprint = fingerprint("chat:$chatId")
        lockTelegramIdentity(identity, userFingerprint)
        codes.findAllByBotIdentityAndUserFingerprintAndConsumedAtIsNull(identity, userFingerprint).forEach {
            it.consumedAt = now
            it.updatedAt = now
        }
        val canonicalCode = buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]) }
        }
        val id = UUID.randomUUID()
        val expiresAt = now.plus(properties.telegramLinkTokenTtl)
        codes.save(
            NotificationTelegramLinkCodeEntity(
                id = id,
                botIdentity = identity,
                codeHash = sha256(canonicalCode),
                telegramUserCiphertext = encrypt(telegramUserId.toString(), codeContext(identity, id, "user")),
                chatIdCiphertext = encrypt(chatId.toString(), codeContext(identity, id, "chat")),
                keyVersion = ACTIVE_KEY_VERSION,
                userFingerprint = userFingerprint,
                chatFingerprint = chatFingerprint,
                expiresAt = expiresAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return TelegramLinkCode(id, canonicalCode.chunked(4).joinToString("-"), expiresAt)
    }

    fun discardWebhookUpdate(updateId: Long) {
        jdbc.update(
            "delete from notification_telegram_webhook_updates where bot_identity = ? and update_id = ?",
            botIdentity(),
            updateId,
        )
    }

    @Transactional
    fun consume(userId: UUID, code: String): TelegramLinkResult {
        val canonicalCode = code.uppercase().filterNot { it == '-' || it.isWhitespace() }
        if (!CODE_PATTERN.matches(canonicalCode)) return TelegramLinkResult(TelegramLinkConsumption.INVALID)
        val now = time.now()
        val linkCode = codes.findByCodeHashForUpdate(sha256(canonicalCode))
            ?: return TelegramLinkResult(TelegramLinkConsumption.INVALID)
        if (linkCode.consumedAt != null || !now.isBefore(linkCode.expiresAt) || linkCode.keyVersion != ACTIVE_KEY_VERSION) {
            return TelegramLinkResult(TelegramLinkConsumption.INVALID)
        }
        val telegramUserId = runCatching {
            decrypt(linkCode.telegramUserCiphertext, codeContext(linkCode.botIdentity, linkCode.id, "user")).toLong()
        }.getOrNull() ?: return TelegramLinkResult(TelegramLinkConsumption.INVALID)
        val chatId = runCatching {
            decrypt(linkCode.chatIdCiphertext, codeContext(linkCode.botIdentity, linkCode.id, "chat")).toLong()
        }.getOrNull() ?: return TelegramLinkResult(TelegramLinkConsumption.INVALID)
        endpoints.lockUserForTelegramUpdate(userId)
        val identity = botIdentity()
        val userFingerprint = linkCode.userFingerprint
        val chatFingerprint = linkCode.chatFingerprint
        // User-row locks serialize changes within one Gyro account. This advisory lock also
        // serializes two different accounts racing to claim the same immutable Telegram user.
        lockTelegramIdentity(identity, userFingerprint)
        val identityOwner = endpoints.findByBotIdentityAndUserFingerprint(identity, userFingerprint)
        val chatOwner = endpoints.findByBotIdentityAndChatFingerprint(identity, chatFingerprint)
        if ((identityOwner != null && identityOwner.userId != userId) ||
            (chatOwner != null && chatOwner.userId != userId)
        ) {
            linkCode.consumedAt = now
            linkCode.updatedAt = now
            return TelegramLinkResult(TelegramLinkConsumption.COLLISION)
        }
        val encryptedUserId = encrypt(telegramUserId.toString(), endpointContext(identity, userId, "user"))
        val encryptedChatId = encrypt(chatId.toString(), endpointContext(identity, userId, "chat"))
        val endpoint = endpoints.findByUserIdAndBotIdentity(userId, identity)
            ?: NotificationTelegramEndpointEntity(
                userId = userId,
                botIdentity = identity,
                telegramUserCiphertext = encryptedUserId,
                chatIdCiphertext = encryptedChatId,
                keyVersion = ACTIVE_KEY_VERSION,
                userFingerprint = userFingerprint,
                chatFingerprint = chatFingerprint,
                state = TelegramEndpointState.ACTIVE,
                linkedAt = now,
                verifiedAt = now,
                lastInboundAt = now,
                createdAt = now,
                updatedAt = now,
            )
        endpoint.telegramUserCiphertext = encryptedUserId
        endpoint.chatIdCiphertext = encryptedChatId
        endpoint.keyVersion = ACTIVE_KEY_VERSION
        endpoint.userFingerprint = userFingerprint
        endpoint.chatFingerprint = chatFingerprint
        endpoint.state = TelegramEndpointState.ACTIVE
        endpoint.linkedAt = now
        endpoint.verifiedAt = now
        endpoint.lastInboundAt = now
        endpoint.revokedAt = null
        endpoint.updatedAt = now
        endpoints.save(endpoint)
        linkCode.claimedUserId = userId
        linkCode.consumedAt = now
        linkCode.updatedAt = now
        return TelegramLinkResult(TelegramLinkConsumption.LINKED, userId)
    }

    @Transactional
    fun active(userId: UUID): ActiveTelegramEndpoint? {
        val endpoint = endpoints.findByUserIdAndBotIdentity(userId, botIdentity())
            ?.takeIf { it.state == TelegramEndpointState.ACTIVE && it.keyVersion == ACTIVE_KEY_VERSION }
            ?: return null
        return runCatching {
            ActiveTelegramEndpoint(
                decrypt(endpoint.chatIdCiphertext, endpointContext(endpoint.botIdentity, endpoint.userId, "chat")),
            )
        }
            .getOrElse {
                endpoint.state = TelegramEndpointState.REVOKED
                endpoint.revokedAt = time.now()
                endpoint.updatedAt = time.now()
                null
            }
    }

    @Transactional(readOnly = true)
    fun hasActiveEndpoint(userId: UUID): Boolean =
        endpoints.findByUserIdAndBotIdentity(userId, botIdentity())?.let {
            it.state == TelegramEndpointState.ACTIVE && it.keyVersion == ACTIVE_KEY_VERSION
        } == true

    @Transactional
    fun markBlocked(userId: UUID) = updateState(userId, TelegramEndpointState.BLOCKED)

    @Transactional
    fun markRevoked(userId: UUID) = updateState(userId, TelegramEndpointState.REVOKED)

    private fun updateState(userId: UUID, state: TelegramEndpointState) {
        val endpoint = endpoints.findByUserIdAndBotIdentity(userId, botIdentity()) ?: return
        val now = time.now()
        endpoint.state = state
        endpoint.revokedAt = now.takeIf { state == TelegramEndpointState.REVOKED }
        endpoint.updatedAt = now
    }

    private fun botIdentity(): String = properties.telegramBotUsername.removePrefix("@").lowercase()

    private fun encrypt(value: String, context: String): String {
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decrypt(value: String, context: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 28) { "Telegram endpoint ciphertext is invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun endpointContext(botIdentity: String, userId: UUID, field: String) =
        "telegram-endpoint:v1:$botIdentity:$userId:$field"

    private fun codeContext(botIdentity: String, codeId: UUID, field: String) =
        "telegram-link-code:v1:$botIdentity:$codeId:$field"

    private fun lockTelegramIdentity(identity: String, userFingerprint: String) {
        jdbc.execute("select pg_advisory_xact_lock(hashtextextended(?, 0))") { statement ->
            statement.setString(1, "$identity:$userFingerprint")
            statement.execute()
        }
    }

    private fun encryptionKey() = SecretKeySpec(
        Base64.getDecoder().decode(properties.telegramEndpointEncryptionKey),
        "AES",
    )

    private fun fingerprint(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.endpointFingerprintKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("${botIdentity()}:$value".toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val CODE_LENGTH = 8
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val ACTIVE_KEY_VERSION = "v1"
        val CODE_PATTERN = Regex("^[A-HJ-NP-Z2-9]{8}$")
        val secureRandom = SecureRandom()
    }
}
