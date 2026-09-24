package com.gyro.api.subscription.application

import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.*

/**
 * Keeps cache invalidation separate from event parsing and joins the publisher-owned
 * delivery transaction when invoked through [EntitlementCacheInvalidator].
 */
@Component
class EntitlementCacheInvalidationProcessor(
    private val cachedEntitlementService: CachedEntitlementService,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun invalidate(userId: UUID, eventId: Long) {
        cachedEntitlementService.invalidate(userId)
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.ENTITLEMENT)
        log.info(
            "Invalidated entitlement cache for userId={} eventId={}",
            userId, eventId,
        )
    }
}
