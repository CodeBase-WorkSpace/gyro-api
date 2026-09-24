package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.SubscriptionEvent
import com.gyro.api.subscription.domain.SubscriptionTransitionType
import com.gyro.api.subscription.infrastructure.SubscriptionEventRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class EntitlementLossBoundaryService(
    private val subscriptionEventRepository: SubscriptionEventRepository,
) {
    fun resolveDate(
        userId: UUID,
        entitlement: Entitlement,
        zone: ZoneId,
        persistedFallback: Instant,
    ): LocalDate {
        val lapseInstant = entitlement.gracePeriodEnd
            ?: entitlement.currentPeriodEnd
            ?: latestLossEvent(userId)?.effectiveLossInstant()
            ?: persistedFallback
        return lapseInstant.atZone(zone).toLocalDate()
    }

    private fun latestLossEvent(userId: UUID): SubscriptionEvent? {
        return subscriptionEventRepository.findFirstByUserIdAndTransitionTypeInOrderByCreatedAtDesc(
            userId = userId,
            transitionTypes = FEATURE_LOSS_TRANSITIONS,
        )
    }

    private fun SubscriptionEvent.effectiveLossInstant(): Instant {
        return when (transitionType) {
            SubscriptionTransitionType.GRACE_EXIT_FAILURE -> gracePeriodEndBefore ?: createdAt
            SubscriptionTransitionType.PERIOD_EXPIRED -> periodEndBefore ?: periodEndAfter ?: createdAt
            else -> createdAt
        }
    }

    private companion object {
        private val FEATURE_LOSS_TRANSITIONS = setOf(
            SubscriptionTransitionType.PERIOD_EXPIRED,
            SubscriptionTransitionType.GRACE_EXIT_FAILURE,
            SubscriptionTransitionType.ADMIN_REVOKE,
            SubscriptionTransitionType.ADMIN_TERMINATE,
            SubscriptionTransitionType.ADMIN_BILLING_BLOCK,
            SubscriptionTransitionType.REFUND,
        )
    }
}
