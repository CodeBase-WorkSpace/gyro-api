package com.gyro.api.goal.application.coach

import com.gyro.api.common.event.UserDashboardDataChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Evicts only committed changes. `fallbackExecution` also covers outbox
 * callbacks and administrative paths that are invoked without a transaction.
 */
@Component
class NutritionCoachStateCacheInvalidator(
    private val cache: NutritionCoachStateCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun invalidate(event: UserDashboardDataChangedEvent) {
        cache.invalidate(event.userId)
        log.debug(
            "Nutrition Coach cache eviction published for userId={} source={}",
            event.userId,
            event.source,
        )
    }
}
