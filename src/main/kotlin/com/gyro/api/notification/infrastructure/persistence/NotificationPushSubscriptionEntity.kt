package com.gyro.api.notification.infrastructure.persistence

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@Entity
@Table(name = "notification_push_subscriptions")
class NotificationPushSubscriptionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "endpoint_ciphertext", nullable = false) var endpointCiphertext: String,
    @Column(name = "p256dh_ciphertext", nullable = false) var p256dhCiphertext: String,
    @Column(name = "auth_ciphertext", nullable = false) var authCiphertext: String,
    @Column(name = "key_version", nullable = false) var keyVersion: String,
    @Column(name = "endpoint_fingerprint", nullable = false) val endpointFingerprint: String,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

interface NotificationPushSubscriptionRepository : org.springframework.data.jpa.repository.JpaRepository<NotificationPushSubscriptionEntity, UUID> {
    @Query(value = "select id from users where id = :userId for update", nativeQuery = true)
    fun lockUserForSubscriptionUpdate(@Param("userId") userId: UUID): UUID?
    fun findFirstByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId: UUID): NotificationPushSubscriptionEntity?
    fun findAllByUserIdAndRevokedAtIsNullOrderByUpdatedAtDesc(userId: UUID): List<NotificationPushSubscriptionEntity>
    fun findByUserIdAndEndpointFingerprint(userId: UUID, endpointFingerprint: String): NotificationPushSubscriptionEntity?
    fun existsByUserIdAndEndpointFingerprintAndRevokedAtIsNullAndKeyVersion(userId: UUID, endpointFingerprint: String, keyVersion: String): Boolean
    @Modifying
    @Query("update NotificationPushSubscriptionEntity s set s.revokedAt = :now, s.updatedAt = :now where s.userId = :userId and s.revokedAt is null")
    fun revokeAllActiveByUserId(@Param("userId") userId: UUID, @Param("now") now: Instant): Int
    fun existsByUserIdAndRevokedAtIsNullAndKeyVersion(userId: UUID, keyVersion: String): Boolean
    fun countByUserIdAndRevokedAtIsNullAndKeyVersion(userId: UUID, keyVersion: String): Long

    @org.springframework.data.jpa.repository.Query(
        "select distinct s.userId from NotificationPushSubscriptionEntity s where s.revokedAt is null and s.keyVersion = :keyVersion",
    )
    fun findUserIdsWithActiveSubscription(keyVersion: String): List<UUID>
}
