package com.gyro.api.notification.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID
import jakarta.persistence.LockModeType

enum class TelegramEndpointState { ACTIVE, BLOCKED, REVOKED }

@Entity
@Table(name = "notification_telegram_link_tokens")
class NotificationTelegramLinkTokenEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "token_hash", nullable = false) val tokenHash: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

interface NotificationTelegramLinkTokenRepository : JpaRepository<NotificationTelegramLinkTokenEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from NotificationTelegramLinkTokenEntity t where t.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): NotificationTelegramLinkTokenEntity?

    fun findFirstByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
        userId: UUID,
        now: Instant,
    ): NotificationTelegramLinkTokenEntity?

    fun findAllByUserIdAndConsumedAtIsNull(userId: UUID): List<NotificationTelegramLinkTokenEntity>
}

@Entity
@Table(name = "notification_telegram_link_codes")
class NotificationTelegramLinkCodeEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "claimed_user_id") var claimedUserId: UUID? = null,
    @Column(name = "bot_identity", nullable = false) val botIdentity: String,
    @Column(name = "code_hash", nullable = false) val codeHash: String,
    @Column(name = "telegram_user_ciphertext", nullable = false) val telegramUserCiphertext: String,
    @Column(name = "chat_id_ciphertext", nullable = false) val chatIdCiphertext: String,
    @Column(name = "key_version", nullable = false) val keyVersion: String,
    @Column(name = "user_fingerprint", nullable = false) val userFingerprint: String,
    @Column(name = "chat_fingerprint", nullable = false) val chatFingerprint: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

interface NotificationTelegramLinkCodeRepository : JpaRepository<NotificationTelegramLinkCodeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from NotificationTelegramLinkCodeEntity c where c.codeHash = :codeHash")
    fun findByCodeHashForUpdate(@Param("codeHash") codeHash: String): NotificationTelegramLinkCodeEntity?

    fun findAllByBotIdentityAndUserFingerprintAndConsumedAtIsNull(
        botIdentity: String,
        userFingerprint: String,
    ): List<NotificationTelegramLinkCodeEntity>
}

@Entity
@Table(name = "notification_telegram_endpoints")
class NotificationTelegramEndpointEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "bot_identity", nullable = false) val botIdentity: String,
    @Column(name = "telegram_user_ciphertext", nullable = false) var telegramUserCiphertext: String,
    @Column(name = "chat_id_ciphertext", nullable = false) var chatIdCiphertext: String,
    @Column(name = "key_version", nullable = false) var keyVersion: String,
    @Column(name = "user_fingerprint", nullable = false) var userFingerprint: String,
    @Column(name = "chat_fingerprint", nullable = false) var chatFingerprint: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false) var state: TelegramEndpointState,
    @Column(name = "linked_at", nullable = false) var linkedAt: Instant,
    @Column(name = "verified_at", nullable = false) var verifiedAt: Instant,
    @Column(name = "last_inbound_at", nullable = false) var lastInboundAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

interface NotificationTelegramEndpointRepository : JpaRepository<NotificationTelegramEndpointEntity, UUID> {
    @Query(value = "select id from users where id = :userId for update", nativeQuery = true)
    fun lockUserForTelegramUpdate(@Param("userId") userId: UUID): UUID?

    fun findByUserIdAndBotIdentity(userId: UUID, botIdentity: String): NotificationTelegramEndpointEntity?
    fun findByBotIdentityAndUserFingerprint(botIdentity: String, userFingerprint: String): NotificationTelegramEndpointEntity?
    fun findByBotIdentityAndChatFingerprint(botIdentity: String, chatFingerprint: String): NotificationTelegramEndpointEntity?
}
