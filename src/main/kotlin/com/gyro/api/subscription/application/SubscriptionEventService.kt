package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.EventSourceType
import com.gyro.api.subscription.domain.SubscriptionEvent
import com.gyro.api.subscription.domain.SubscriptionStatus
import com.gyro.api.subscription.domain.SubscriptionTransitionType
import com.gyro.api.subscription.domain.UserSubscription
import com.gyro.api.subscription.infrastructure.SubscriptionEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class SubscriptionEventService(
    private val eventRepository: SubscriptionEventRepository,
) {
    @Transactional
    fun recordTransition(
        userId: UUID,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        before: SubscriptionStateSnapshot? = null,
        after: UserSubscription? = null,
        actorId: UUID? = null,
        reason: String? = null,
    ): SubscriptionEvent {
        val idempotencyKey = "${sourceType.name}_${sourceId}_${transitionType.name}"

        // Idempotent: return existing if key already present
        val existing = eventRepository.findByIdempotencyKey(idempotencyKey)
        if (existing.isPresent) {
            return existing.get()
        }

        val event = SubscriptionEvent(
            userId = userId,
            transitionType = transitionType,
            sourceType = sourceType,
            sourceId = sourceId,
            idempotencyKey = idempotencyKey,
            statusBefore = before?.status,
            planIdBefore = before?.planId,
            periodStartBefore = before?.periodStart,
            periodEndBefore = before?.periodEnd,
            cancelAtPeriodEndBefore = before?.cancelAtPeriodEnd,
            gracePeriodEndBefore = before?.gracePeriodEnd,
            graceReasonBefore = before?.graceReason,
            statusAfter = after?.status,
            planIdAfter = after?.planId,
            periodStartAfter = after?.periodStart,
            periodEndAfter = after?.periodEnd,
            cancelAtPeriodEndAfter = after?.cancelAtPeriodEnd,
            gracePeriodEndAfter = after?.gracePeriodEnd,
            graceReasonAfter = after?.graceReason,
            actorId = actorId,
            reason = reason,
        )

        return eventRepository.save(event)
    }

    /**
     * Check if a transition has already been processed (idempotency guard).
     * Call this before mutating subscription state to prevent duplicate processing.
     */
    @Transactional(readOnly = true)
    fun alreadyProcessed(
        sourceType: EventSourceType,
        sourceId: String,
        transitionType: SubscriptionTransitionType,
    ): Boolean {
        val idempotencyKey = "${sourceType.name}_${sourceId}_${transitionType.name}"
        return eventRepository.findByIdempotencyKey(idempotencyKey).isPresent
    }

    /**
     * Check if any transition has been processed for a given source (idempotency guard).
     * One invoice should only produce one subscription lifecycle application.
     * Call this before mutating subscription state to prevent duplicate processing.
     */
    @Transactional(readOnly = true)
    fun alreadyProcessedSource(
        sourceType: EventSourceType,
        sourceId: String,
    ): Boolean {
        return eventRepository.existsBySourceTypeAndSourceId(sourceType, sourceId)
    }
}
