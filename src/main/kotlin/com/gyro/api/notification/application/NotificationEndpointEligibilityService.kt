package com.gyro.api.notification.application

import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationEndpointCandidate
import com.gyro.api.notification.domain.NotificationEndpointSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class NotificationEndpointEligibilityService(
    private val users: UserRepository,
    private val preferences: NotificationPreferenceService,
) {
    @Transactional(readOnly = true)
    fun isEligible(userId: UUID, channel: NotificationChannel, endpointReference: String, adapterKey: String): Boolean {
        if (endpointReference == INTERNAL_CORE_PROBE_REFERENCE) {
            return adapterKey == LOG_ONLY_ADAPTER_KEY
        }
        val expectedReference = when (channel) {
            NotificationChannel.EMAIL -> ACCOUNT_EMAIL_REFERENCE
            NotificationChannel.SMS -> ACCOUNT_PHONE_REFERENCE
            else -> return true
        }
        if (endpointReference != expectedReference) return false
        val user = users.findById(userId).orElse(null) ?: return false
        val candidate = when (channel) {
            NotificationChannel.EMAIL -> user.email
                ?.takeIf { user.emailVerificationStatus == VerificationStatus.VERIFIED }
                ?.let { NotificationEndpointCandidate(userId, channel, NotificationEndpointSource.ACCOUNT_EMAIL, it) }
            NotificationChannel.SMS -> user.phoneNumber
                ?.takeIf { user.phoneVerificationStatus == VerificationStatus.VERIFIED }
                ?.let { NotificationEndpointCandidate(userId, channel, NotificationEndpointSource.ACCOUNT_PHONE, it) }
            else -> null
        } ?: return false
        return preferences.isHealthy(candidate)
    }

    companion object {
        const val ACCOUNT_EMAIL_REFERENCE = "account:email"
        const val ACCOUNT_PHONE_REFERENCE = "account:phone"
        const val INTERNAL_CORE_PROBE_REFERENCE = "internal:notification-core-probe"
        private const val LOG_ONLY_ADAPTER_KEY = "log-only"
    }
}
