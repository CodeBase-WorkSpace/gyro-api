package com.gyro.api.notification.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_announcements")
class AdminAnnouncementEntity(
    @Id val id: UUID,
    @Column(nullable = false) val title: String,
    @Column(nullable = false) val body: String,
    @Column(name = "content_hash", nullable = false) val contentHash: String,
    @Column(name = "target_web_push", nullable = false) val targetWebPush: Boolean,
    @Column(name = "target_telegram_channel", nullable = false) val targetTelegramChannel: Boolean,
    @Column(name = "recipient_user_id") val recipientUserId: UUID? = null,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

interface AdminAnnouncementRepository : org.springframework.data.jpa.repository.JpaRepository<AdminAnnouncementEntity, UUID>
