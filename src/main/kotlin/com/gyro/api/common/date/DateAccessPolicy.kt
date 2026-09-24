package com.gyro.api.common.date

import com.gyro.api.common.error.FutureDateLimitException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.config.PremiumGatingProperties
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class DateAccessPolicy(
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val timeProvider: TimeProvider,
    private val entitlementGateService: EntitlementGateService,
    private val premiumGatingProperties: PremiumGatingProperties,
) {
    fun assertWritable(userId: UUID, date: LocalDate) {
        if (!canWrite(userId, date)) {
            val today = timeProvider.today(userZone(userId))
            throw FutureDateLimitException(
                date = date,
                maximumDate = today.plusDays(premiumGatingProperties.freeFutureDiaryDays.toLong()),
            )
        }
    }

    fun canWrite(userId: UUID, date: LocalDate): Boolean {
        if (entitlementGateService.hasFeatureAccess(userId, FUTURE_MEAL_PLANNING_FEATURE)) return true
        val today = timeProvider.today(userZone(userId))
        return !date.isAfter(today.plusDays(premiumGatingProperties.freeFutureDiaryDays.toLong()))
    }

    private fun userZone(userId: UUID): ZoneId {
        val timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone
        return ZoneId.of(timezone)
    }

    private companion object {
        private const val FUTURE_MEAL_PLANNING_FEATURE = "future_meal_planning"
    }
}
