package com.gyro.api.common.event

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

enum class UserDashboardChangeSource {
    DIARY,
    WEIGHT,
    GOAL,
    PROFILE,
    RECALIBRATION,
    ENTITLEMENT,
}

data class UserDashboardDataChangedEvent(
    val userId: UUID,
    val source: UserDashboardChangeSource,
)

@Component
class UserDashboardDataChangedPublisher(
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun publish(userId: UUID, source: UserDashboardChangeSource) {
        eventPublisher.publishEvent(UserDashboardDataChangedEvent(userId, source))
    }
}
