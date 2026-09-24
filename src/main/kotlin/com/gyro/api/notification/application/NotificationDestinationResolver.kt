package com.gyro.api.notification.application

import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.RenderedNotification
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import org.springframework.stereotype.Service

@Service
class NotificationDestinationResolver(
    private val intents: NotificationIntentRepository,
    private val users: UserRepository,
) {
    fun userId(notification: RenderedNotification): java.util.UUID? =
        intents.findById(notification.intentId).orElse(null)?.userId

    fun resolve(notification: RenderedNotification): String? {
        val userId = userId(notification) ?: return null
        val user = users.findById(userId).orElse(null) ?: return null
        return when (notification.channel) {
            NotificationChannel.EMAIL -> user.email?.takeIf { user.emailVerificationStatus == VerificationStatus.VERIFIED }
            NotificationChannel.SMS -> user.phoneNumber?.takeIf { user.phoneVerificationStatus == VerificationStatus.VERIFIED }
            else -> null
        }
    }
}
