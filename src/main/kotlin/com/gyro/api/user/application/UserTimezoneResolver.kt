package com.gyro.api.user.application

import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.util.UUID

/**
 * Resolves the canonical timezone for user-facing "today" calculations without
 * invoking profile creation or failing reads because of legacy invalid data.
 */
@Component
class UserTimezoneResolver(
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
) {
    fun resolve(userId: UUID): ZoneId {
        val configured = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        return runCatching { ZoneId.of(configured) }
            .getOrElse { ZoneId.of(userPreferencesProperties.normalizedDefaultTimezone) }
    }
}
