package com.gyro.api.notification.application

import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.*
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.util.UUID

@Service
class NotificationPreferenceService(
    private val users: UserRepository,
    private val profiles: UserProfileRepository,
    private val settings: NotificationUserSettingsRepository,
    private val preferences: NotificationPreferenceRepository,
    private val endpointHealth: NotificationEndpointHealthRepository,
    private val fingerprints: NotificationEndpointFingerprintService,
    private val time: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun get(userId: UUID): NotificationPreferencesView {
        val user = users.findById(userId).orElseThrow { ResourceNotFoundException("User") }
        val profile = profiles.findByUser_Id(userId) ?: throw ResourceNotFoundException("User profile")
        val settings = settings.findById(userId).orElse(null)
        return NotificationPreferencesView(
            timezone = profile.timezone,
            quietHoursStart = settings?.quietHoursStart ?: DEFAULT_QUIET_START,
            quietHoursEnd = settings?.quietHoursEnd ?: DEFAULT_QUIET_END,
            categories = OPTIONAL_CATEGORIES.map { category ->
                NotificationCategoryPreferenceView(
                    category = category,
                    enabled = preferences.findByUserIdAndCategory(userId, category)?.enabled ?: defaultEnabled(category),
                    mutable = true,
                )
            } + NotificationCategoryPreferenceView(NotificationCategory.MANDATORY_TRANSACTIONAL, enabled = true, mutable = false),
            emailEligible = user.emailVerificationStatus == VerificationStatus.VERIFIED && user.email?.let {
                isHealthy(NotificationEndpointCandidate(userId, NotificationChannel.EMAIL, NotificationEndpointSource.ACCOUNT_EMAIL, it))
            } == true,
            smsEligible = user.phoneVerificationStatus == VerificationStatus.VERIFIED && user.phoneNumber?.let {
                isHealthy(NotificationEndpointCandidate(userId, NotificationChannel.SMS, NotificationEndpointSource.ACCOUNT_PHONE, it))
            } == true,
        )
    }

    @Transactional
    fun update(userId: UUID, command: UpdateNotificationPreferencesCommand): NotificationPreferencesView {
        require(command.quietHoursStart != command.quietHoursEnd) { "Quiet-hour start and end must differ." }
        require(command.categories.keys.all { it in OPTIONAL_CATEGORIES }) { "Mandatory notification categories cannot be changed." }
        val now = time.now()
        val setting = settings.findById(userId).orElse(null)
        if (setting == null) {
            settings.save(NotificationUserSettingsEntity(userId, command.quietHoursStart, command.quietHoursEnd, now, now))
        } else {
            setting.quietHoursStart = command.quietHoursStart
            setting.quietHoursEnd = command.quietHoursEnd
            setting.updatedAt = now
        }
        command.categories.forEach { (category, enabled) ->
            val preference = preferences.findByUserIdAndCategory(userId, category)
            if (preference == null) {
                preferences.save(NotificationPreferenceEntity(
                    userId = userId,
                    category = category,
                    enabled = enabled,
                    consentSource = SELF_SERVICE,
                    policyVersion = POLICY_VERSION,
                    actorUserId = userId,
                    consentedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ))
            } else {
                preference.enabled = enabled
                preference.consentSource = SELF_SERVICE
                preference.policyVersion = POLICY_VERSION
                preference.actorUserId = userId
                preference.consentedAt = now
                preference.updatedAt = now
            }
        }
        return get(userId)
    }

    @Transactional
    fun setCategoryPreference(userId: UUID, category: NotificationCategory, enabled: Boolean) {
        if (category !in OPTIONAL_CATEGORIES) {
            throw IllegalArgumentException("Mandatory notification categories cannot be changed.")
        }
        val now = time.now()
        val preference = preferences.findByUserIdAndCategory(userId, category)
        if (preference == null) {
            preferences.save(NotificationPreferenceEntity(
                userId = userId,
                category = category,
                enabled = enabled,
                consentSource = SELF_SERVICE,
                policyVersion = POLICY_VERSION,
                actorUserId = userId,
                consentedAt = now,
                createdAt = now,
                updatedAt = now,
            ))
        } else {
            preference.enabled = enabled
            preference.consentSource = SELF_SERVICE
            preference.policyVersion = POLICY_VERSION
            preference.actorUserId = userId
            preference.consentedAt = now
            preference.updatedAt = now
        }
    }

    @Transactional(readOnly = true)
    fun deliveryEligibility(userId: UUID, category: NotificationCategory): NotificationDeliveryEligibility {
        val profile = profiles.findByUser_Id(userId) ?: throw ResourceNotFoundException("User profile")
        val userSettings = settings.findById(userId).orElse(null)
        return NotificationDeliveryEligibility(
            enabled = category == NotificationCategory.MANDATORY_TRANSACTIONAL ||
                (preferences.findByUserIdAndCategory(userId, category)?.enabled ?: defaultEnabled(category)),
            timezone = profile.timezone,
            quietHoursStart = userSettings?.quietHoursStart ?: DEFAULT_QUIET_START,
            quietHoursEnd = userSettings?.quietHoursEnd ?: DEFAULT_QUIET_END,
        )
    }

    fun isHealthy(candidate: NotificationEndpointCandidate): Boolean {
        val health = endpointHealth.findByUserIdAndChannelAndAccountSourceAndDestinationFingerprint(
            candidate.userId,
            candidate.channel,
            candidate.source,
            fingerprints.forValue(candidate.value),
        )
        return health?.healthState != NotificationEndpointHealthState.INVALID
    }

    fun markInvalid(candidate: NotificationEndpointCandidate, reason: NotificationEndpointInvalidReason) {
        val now = time.now()
        val fingerprint = fingerprints.forValue(candidate.value)
        val entity = endpointHealth.findByUserIdAndChannelAndAccountSourceAndDestinationFingerprint(
            candidate.userId, candidate.channel, candidate.source, fingerprint,
        ) ?: NotificationEndpointHealthEntity(
            userId = candidate.userId,
            channel = candidate.channel,
            accountSource = candidate.source,
            destinationFingerprint = fingerprint,
        )
        entity.healthState = NotificationEndpointHealthState.INVALID
        entity.invalidReason = reason
        entity.lastFailureAt = now
        entity.diagnosticDeleteAt = now.plus(DEFAULT_INVALID_DIAGNOSTIC_RETENTION)
        entity.updatedAt = now
        endpointHealth.save(entity)
    }

    // Announcements are opt-out: absent preference rows count as consent until the user disables them.
    private fun defaultEnabled(category: NotificationCategory) = category == NotificationCategory.OPTIONAL_ANNOUNCEMENTS

    companion object {
        private val OPTIONAL_CATEGORIES = setOf(
            NotificationCategory.OPTIONAL_BILLING,
            NotificationCategory.OPTIONAL_FOOD_LOGGING,
            NotificationCategory.OPTIONAL_WEIGHT_LOGGING,
            NotificationCategory.OPTIONAL_ANNOUNCEMENTS,
        )
        private val DEFAULT_QUIET_START = LocalTime.of(22, 0)
        private val DEFAULT_QUIET_END = LocalTime.of(8, 0)
        private val DEFAULT_INVALID_DIAGNOSTIC_RETENTION = java.time.Duration.ofDays(7)
        private const val SELF_SERVICE = "SELF_SERVICE"
        private const val POLICY_VERSION = "notification-preferences-v1"
    }
}
