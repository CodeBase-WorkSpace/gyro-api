package com.gyro.api.user.application

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.domain.UserStatus
import com.gyro.api.auth.infrastructure.RefreshTokenRepository
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.domain.UserProfile
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val timeProvider: TimeProvider,
    private val accountAuditService: AccountAuditService,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
) : UserService {

    @Transactional
    override fun getProfile(userId: UUID): UserProfileView {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("User") }
        val profile = userProfileRepository.findByUser_Id(userId)
            ?: userProfileRepository.save(user.defaultProfile())

        return user.toProfileView(profile)
    }

    @Transactional
    override fun updateProfile(userId: UUID, command: UpdateUserProfileCommand): UserProfileView {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("User") }
        val profile = userProfileRepository.findByUser_Id(userId)
            ?: userProfileRepository.save(user.defaultProfile())
        val previousTimezone = profile.timezone

        profile.displayName = command.displayName?.trim()?.ifBlank { null }
        command.timezone?.let { profile.timezone = UserPreferencesProperties.normalizeTimezone(it) }
        command.locale?.let { profile.locale = UserPreferencesProperties.normalizeLocale(it) }

        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PROFILE_UPDATED,
            metadata = mapOf(
                "changedFields" to listOfNotNull(
                    command.displayName?.let { "displayName" },
                    command.timezone?.let { "timezone" },
                    command.locale?.let { "locale" },
                )
            )
        )

        if (profile.timezone != previousTimezone) {
            dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.PROFILE)
        }
        return user.toProfileView(profile)
    }

    @Transactional
    override fun markOnboardingWelcomeSeen(userId: UUID) {
        val user = userRepository.findByIdForUpdate(userId)
            .orElseThrow { ResourceNotFoundException("User") }

        if (user.onboardingWelcomeSeenAt == null) {
            user.onboardingWelcomeSeenAt = timeProvider.now()
            userRepository.save(user)
        }
    }

    @Transactional
    override fun acknowledgeCalculatorRerunPrompt(userId: UUID) {
        val user = userRepository.findByIdForUpdate(userId)
            .orElseThrow { ResourceNotFoundException("User") }

        if (user.calculatorRerunPromptAcknowledgedAt == null) {
            user.calculatorRerunPromptAcknowledgedAt = timeProvider.now()
            userRepository.save(user)
        }
    }

    @Transactional
    override fun deactivateCurrentUser(userId: UUID) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User") }
        val now = timeProvider.now()

        user.status = UserStatus.DEACTIVATED
        user.deactivatedAt = now

        refreshTokenRepository.revokeAllByUserId(
            userId = userId,
            revokedAt = now,
        )

        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.ACCOUNT_DEACTIVATED,
            metadata = mapOf("policy" to "soft_deactivation"),
        )

        userRepository.save(user)
    }

    private fun GyroUser.toProfileView(profile: UserProfile): UserProfileView {
        return UserProfileView(
            id = requireNotNull(id),
            email = email,
            phoneNumber = phoneNumber,
            displayName = profile.displayName,
            timezone = profile.timezone,
            locale = profile.locale,
            role = role.name,
            status = status.name,
            emailVerificationStatus = emailVerificationStatus.name,
            phoneVerificationStatus = phoneVerificationStatus.name,
            hasPassword = hasPassword,
            onboardingWelcomeSeenAt = onboardingWelcomeSeenAt,
            calculatorRerunPromptAcknowledgedAt = calculatorRerunPromptAcknowledgedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun GyroUser.defaultProfile(): UserProfile {
        return UserProfile(
            user = this,
            timezone = userPreferencesProperties.normalizedDefaultTimezone,
            locale = userPreferencesProperties.normalizedDefaultLocale,
        )
    }
}
