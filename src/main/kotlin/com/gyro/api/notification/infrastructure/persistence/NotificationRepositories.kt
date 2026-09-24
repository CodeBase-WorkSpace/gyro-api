package com.gyro.api.notification.infrastructure.persistence

import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationEndpointSource
import com.gyro.api.notification.domain.NotificationIntentStatus
import com.gyro.api.notification.domain.NotificationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface NotificationTemplateRepository : JpaRepository<NotificationTemplateEntity, UUID> {
    fun findByTemplateKeyAndVersionAndChannelAndLocale(
        templateKey: String,
        version: Int,
        channel: NotificationChannel,
        locale: String,
    ): NotificationTemplateEntity?
}

interface NotificationIntentRepository : JpaRepository<NotificationIntentEntity, UUID>, JpaSpecificationExecutor<NotificationIntentEntity> {
    fun findBySourceTypeAndIdempotencyKey(sourceType: String, idempotencyKey: String): NotificationIntentEntity?
    fun findAllByUserId(userId: UUID): List<NotificationIntentEntity>
    fun countByTypeAndStatus(type: NotificationType, status: NotificationIntentStatus): Long
    fun findFirstByTypeAndStatusOrderByTerminalAtAsc(type: NotificationType, status: NotificationIntentStatus): NotificationIntentEntity?
}

interface NotificationDeliveryRepository : JpaRepository<NotificationDeliveryEntity, UUID> {
    fun findAllByIntentId(intentId: UUID): List<NotificationDeliveryEntity>
    fun findAllByIntentIdIn(intentIds: Collection<UUID>): List<NotificationDeliveryEntity>
}

interface NotificationAttemptRepository : JpaRepository<NotificationAttemptEntity, UUID> {
    fun countByDeliveryId(deliveryId: UUID): Long
    fun findAllByDeliveryId(deliveryId: UUID): List<NotificationAttemptEntity>
}

interface NotificationUserSettingsRepository : JpaRepository<NotificationUserSettingsEntity, UUID>

interface NotificationPreferenceRepository : JpaRepository<NotificationPreferenceEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<NotificationPreferenceEntity>
    fun findByUserIdAndCategory(userId: UUID, category: NotificationCategory): NotificationPreferenceEntity?
}

interface NotificationEndpointHealthRepository : JpaRepository<NotificationEndpointHealthEntity, UUID> {
    fun findByUserIdAndChannelAndAccountSourceAndDestinationFingerprint(
        userId: UUID,
        channel: NotificationChannel,
        accountSource: NotificationEndpointSource,
        destinationFingerprint: String,
    ): NotificationEndpointHealthEntity?
}
