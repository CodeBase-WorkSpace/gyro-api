package com.gyro.api.subscription.application

import com.gyro.api.common.error.*
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.outbox.OutboxEventWriter
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.*
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class SubscriptionLifecycleService(
    private val userSubscriptionRepository: UserSubscriptionRepository,
    private val manualGrantRepository: ManualGrantRepository,
    private val eventService: SubscriptionEventService,
    private val outboxWriter: OutboxEventWriter,
    private val timeProvider: TimeProvider,
    private val priceRepository: SubscriptionPriceRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val planFeatureRepository: PlanFeatureRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val applyPaidInvoiceTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    /**
     * Apply a paid invoice to the user's subscription.
     * Creates a new subscription if none exists.
     * Reactivates expired subscription by updating the existing row.
     * Renews active subscription by extending period.
     *
     * Standalone optimistic lock retries each receive a fresh transaction. When a caller already
     * owns a transaction, lifecycle changes participate in it atomically and retry is left to that
     * caller because an optimistic locking failure makes the current transaction unusable.
     */
    fun applyPaidInvoice(invoice: Invoice): UserSubscription {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return applyPaidInvoiceInCurrentTransaction(invoice)
        }

        repeat(2) { attempt ->
            try {
                return applyPaidInvoiceTransaction.execute {
                    applyPaidInvoiceInCurrentTransaction(invoice)
                } ?: error("Paid invoice transaction returned no subscription")
            } catch (e: ObjectOptimisticLockingFailureException) {
                if (attempt == 1) throw e
                log.warn("Optimistic lock on subscription for user ${invoice.userId}, retrying once")
            }
        }
        error("unreachable")
    }

    private fun applyPaidInvoiceInCurrentTransaction(invoice: Invoice): UserSubscription {
        val now = timeProvider.now()

        // Idempotency guard: check if this invoice was already processed (any transition)
        if (eventService.alreadyProcessedSource(
                sourceType = EventSourceType.PAYPING,
                sourceId = invoice.id.toString(),
            )
        ) {
            return userSubscriptionRepository.findByUserId(invoice.userId)
                .orElseThrow { SubscriptionNotFoundException() }
        }

        val existing = userSubscriptionRepository.findByUserId(invoice.userId).orElse(null)

        return when {
            existing == null -> {
                val lockedPrice = invoice.subscriptionPriceId?.let { priceId ->
                    priceRepository.findById(priceId).orElseThrow {
                        InvoicePriceSnapshotMissingException(invoice.id.toString())
                    }
                }
                createSubscriptionInternal(
                    userId = invoice.userId,
                    planId = invoice.planId,
                    periodStart = invoice.periodStart,
                    periodEnd = invoice.periodEnd,
                    lockedPriceId = lockedPrice?.id,
                    lockedPrice = lockedPrice?.price,
                    transitionType = SubscriptionTransitionType.FIRST_PURCHASE,
                    sourceType = EventSourceType.PAYPING,
                    sourceId = invoice.id.toString(),
                )
            }
            existing.status == SubscriptionStatus.EXPIRED -> reactivateSubscription(
                existing, invoice.planId, invoice.periodStart, invoice.periodEnd,
                SubscriptionTransitionType.RENEWAL, EventSourceType.PAYPING, invoice.id.toString(),
                invoice = invoice,
            )
            existing.status == SubscriptionStatus.GRACE_PERIOD -> renewFromGrace(existing, invoice)
            existing.status == SubscriptionStatus.ACTIVE -> renewSubscription(existing, invoice)
            else -> renewSubscription(existing, invoice)
        }
    }

    /**
     * Create a new subscription. Called for first purchase.
     */
    @Transactional
    fun createSubscription(
        userId: UUID,
        planId: Long,
        periodStart: Instant,
        periodEnd: Instant?,
        lockedPriceId: Long?,
        lockedPrice: Money? = null,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        actorId: UUID? = null,
        reason: String? = null,
    ): UserSubscription {
        return createSubscriptionInternal(
            userId, planId, periodStart, periodEnd, lockedPriceId, lockedPrice,
            transitionType, sourceType, sourceId, actorId, reason,
        )
    }

    private fun createSubscriptionInternal(
        userId: UUID,
        planId: Long,
        periodStart: Instant,
        periodEnd: Instant?,
        lockedPriceId: Long?,
        lockedPrice: Money? = null,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        actorId: UUID? = null,
        reason: String? = null,
    ): UserSubscription {
        val now = timeProvider.now()
        val subscription = UserSubscription(
            userId = userId,
            planId = planId,
            status = SubscriptionStatus.ACTIVE,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lockedPriceId = lockedPriceId,
            lockedPrice = lockedPrice,
        )
        val saved = userSubscriptionRepository.save(subscription)

        eventService.recordTransition(
            userId = userId,
            transitionType = transitionType,
            sourceType = sourceType,
            sourceId = sourceId,
            after = saved,
            actorId = actorId,
            reason = reason,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = userId,
                transitionType = transitionType.name,
                planId = planId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                occurredAt = now,
            ),
        )

        return saved
    }

    /**
     * Reactivate an expired subscription by updating the existing row.
     * Generic method used by both invoice payment and manual grant flows.
     */
    private fun reactivateSubscription(
        subscription: UserSubscription,
        planId: Long,
        periodStart: Instant,
        periodEnd: Instant?,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        invoice: Invoice? = null,
        actorId: UUID? = null,
        reason: String? = null,
    ): UserSubscription {
        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()

        val updated = subscription.update(
            planId = planId,
            status = SubscriptionStatus.ACTIVE,
            periodStart = periodStart,
            periodEnd = periodEnd,
            cancelAtPeriodEnd = false,
            gracePeriodEnd = null,
            graceReason = null,
            lockedPriceId = subscription.lockedPriceId ?: invoice?.subscriptionPriceId,
            lockedPrice = subscription.lockedPrice ?: invoice?.subscriptionPriceId?.let { priceId ->
                priceRepository.findById(priceId).orElseThrow {
                    InvoicePriceSnapshotMissingException(invoice.id.toString())
                }.price
            },
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = transitionType,
            sourceType = sourceType,
            sourceId = sourceId,
            before = before,
            after = saved,
            actorId = actorId,
            reason = reason,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = transitionType.name,
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )

        return saved
    }

    /**
     * Renew an existing active subscription by extending the period end.
     */
    @Transactional
    fun renewSubscription(subscription: UserSubscription, invoice: Invoice): UserSubscription {
        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()

        val periodLength = ChronoUnit.SECONDS.between(invoice.periodStart, invoice.periodEnd)
        val newPeriodEnd = if (subscription.periodEnd != null && subscription.periodEnd!!.isAfter(now)) {
            subscription.periodEnd!!.plus(periodLength, ChronoUnit.SECONDS)
        } else {
            invoice.periodEnd
        }

        val updated = subscription.update(
            periodEnd = newPeriodEnd,
            cancelAtPeriodEnd = false,
            lockedPriceId = subscription.lockedPriceId ?: invoice.subscriptionPriceId,
            lockedPrice = subscription.lockedPrice ?: invoice.subscriptionPriceId?.let { priceId ->
                priceRepository.findById(priceId).orElseThrow {
                    InvoicePriceSnapshotMissingException(invoice.id.toString())
                }.price
            },
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = SubscriptionTransitionType.RENEWAL,
            sourceType = EventSourceType.PAYPING,
            sourceId = invoice.id.toString(),
            before = before,
            after = saved,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = "RENEWAL",
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )

        return saved
    }

    /**
     * Recover from grace period: clear grace fields, set status to ACTIVE, and extend period.
     */
    @Transactional
    fun renewFromGrace(subscription: UserSubscription, invoice: Invoice): UserSubscription {
        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()

        val periodLength = ChronoUnit.SECONDS.between(invoice.periodStart, invoice.periodEnd)
        val newPeriodEnd = if (subscription.periodEnd != null && subscription.periodEnd!!.isAfter(now)) {
            subscription.periodEnd!!.plus(periodLength, ChronoUnit.SECONDS)
        } else {
            invoice.periodEnd
        }

        val updated = subscription.update(
            status = SubscriptionStatus.ACTIVE,
            periodEnd = newPeriodEnd,
            cancelAtPeriodEnd = false,
            gracePeriodEnd = null,
            graceReason = null,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = SubscriptionTransitionType.RENEWAL,
            sourceType = EventSourceType.PAYPING,
            sourceId = invoice.id.toString(),
            before = before,
            after = saved,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = "RENEWAL",
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )

        return saved
    }

    /** Processes a completed billing period, entering grace when the plan permits it. */
    @Transactional
    fun processPeriodExpiry(subscriptionId: Long) {
        val subscription = userSubscriptionRepository.findById(subscriptionId)
            .orElseThrow { SubscriptionNotFoundException() }

        val now = timeProvider.now()
        val periodEnd = subscription.periodEnd ?: return
        if (subscription.status !in setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED) || !periodEnd.isBefore(now)) {
            return
        }

        val before = subscription.toStateSnapshot()
        val plan = planRepository.findById(subscription.planId).orElseThrow {
            com.gyro.api.common.error.ResourceNotFoundException("Subscription plan")
        }
        val entersGrace = subscription.status == SubscriptionStatus.ACTIVE &&
            !subscription.cancelAtPeriodEnd && plan.gracePeriodDays > 0
        val transition = if (entersGrace) SubscriptionTransitionType.GRACE_ENTRY else SubscriptionTransitionType.PERIOD_EXPIRED
        val sourceId = if (entersGrace) {
            "grace_entry_${subscriptionId}_${periodEnd.epochSecond}"
        } else {
            "period_expiry_${subscriptionId}_${periodEnd.epochSecond}"
        }
        val updated = if (entersGrace) subscription.update(
            status = SubscriptionStatus.GRACE_PERIOD,
            gracePeriodEnd = periodEnd.plus(plan.gracePeriodDays.toLong(), ChronoUnit.DAYS),
            graceReason = "RENEWAL_OVERDUE",
            updatedAt = now,
        ) else subscription.update(
            status = SubscriptionStatus.EXPIRED,
            gracePeriodEnd = null,
            graceReason = null,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = transition,
            sourceType = EventSourceType.SYSTEM,
            sourceId = sourceId,
            before = before,
            after = saved,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = transition.name,
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )
    }

    /** Exits a grace period after its deadline when payment has not recovered the subscription. */
    @Transactional
    fun processGraceExit(subscriptionId: Long) {
        val subscription = userSubscriptionRepository.findById(subscriptionId)
            .orElseThrow { SubscriptionNotFoundException() }
        val now = timeProvider.now()
        val graceEnd = subscription.gracePeriodEnd ?: return
        if (subscription.status != SubscriptionStatus.GRACE_PERIOD || !graceEnd.isBefore(now)) return

        val before = subscription.toStateSnapshot()
        val saved = userSubscriptionRepository.save(subscription.update(
            status = SubscriptionStatus.EXPIRED,
            gracePeriodEnd = null,
            graceReason = null,
            updatedAt = now,
        ))
        val transition = SubscriptionTransitionType.GRACE_EXIT_FAILURE
        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = transition,
            sourceType = EventSourceType.SYSTEM,
            sourceId = "grace_exit_failure_${subscriptionId}_${graceEnd.epochSecond}",
            before = before,
            after = saved,
        )
        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(subscription.userId, transition.name, saved.planId, saved.periodStart, saved.periodEnd, now),
        )
    }

    /**
     * Set cancelAtPeriodEnd flag on the user's active subscription.
     */
    @Transactional
    fun cancelAtPeriodEnd(userId: UUID): UserSubscription {
        val subscription = userSubscriptionRepository.findByUserId(userId)
            .orElseThrow { SubscriptionNotFoundException() }

        if (subscription.status != SubscriptionStatus.ACTIVE) throw SubscriptionStatusConflictException("ACTIVE", subscription.status.name)
        if (subscription.cancelAtPeriodEnd) return subscription

        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()
        val updated = subscription.update(
            cancelAtPeriodEnd = true,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = userId,
            transitionType = SubscriptionTransitionType.USER_CANCEL,
            sourceType = EventSourceType.USER,
            sourceId = "user_cancel_$userId",
            before = before,
            after = saved,
            actorId = userId,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = SubscriptionTransitionType.USER_CANCEL.name,
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )
        return saved
    }

    /** Restores an active subscription whose end-of-period cancellation was requested. */
    @Transactional
    fun restoreSubscription(userId: UUID): UserSubscription {
        val subscription = userSubscriptionRepository.findByUserId(userId).orElseThrow { SubscriptionNotFoundException() }
        if (subscription.status != SubscriptionStatus.ACTIVE) throw SubscriptionStatusConflictException("ACTIVE", subscription.status.name)
        if (!subscription.cancelAtPeriodEnd) return subscription
        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()
        val saved = userSubscriptionRepository.save(subscription.update(cancelAtPeriodEnd = false, updatedAt = now))
        eventService.recordTransition(
            userId = userId,
            transitionType = SubscriptionTransitionType.USER_RESTORE,
            sourceType = EventSourceType.USER,
            sourceId = "user_restore_${userId}_${now.epochSecond}",
            before = before,
            after = saved,
            actorId = userId,
        )
        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(userId, SubscriptionTransitionType.USER_RESTORE.name, saved.planId, saved.periodStart, saved.periodEnd, now),
        )
        return saved
    }

    /**
     * Create a manual grant and extend the user's subscription.
     * Grant period starts from the current periodEnd if the user has an active subscription.
     * Grant expiresAt is computed from periodStart, not from now.
     */
    @Transactional
    fun grantAccess(
        userId: UUID,
        planId: Long,
        durationDays: Int,
        reason: ManualGrantReason,
        grantedBy: UUID,
        reasonNote: String?,
    ): ManualGrant {
        val now = timeProvider.now()
        val grantPlanRank = planRank(planId)

        // Compute periodStart first
        val existing = userSubscriptionRepository.findByUserId(userId).orElse(null)
        if (existing != null &&
            existing.status == SubscriptionStatus.ACTIVE &&
            isLowerTierGrant(planId, existing.planId)
        ) {
            throw LowerTierManualGrantException()
        }

        val periodStart = if (existing != null &&
            existing.status == SubscriptionStatus.ACTIVE &&
            existing.periodEnd != null &&
            existing.periodEnd!!.isAfter(now)
        ) {
            existing.periodEnd!!
        } else {
            now
        }

        val periodEnd = periodStart.plus(durationDays.toLong(), ChronoUnit.DAYS)

        // Create grant with expiresAt computed from periodStart
        val grant = ManualGrant(
            userId = userId,
            planId = planId,
            durationDays = durationDays,
            periodStart = periodStart,
            expiresAt = periodEnd,
            reason = reason,
            reasonNote = reasonNote,
            grantedBy = grantedBy,
        )
        val savedGrant = manualGrantRepository.save(grant)

        if (existing == null) {
            createSubscriptionInternal(
                userId = userId,
                planId = planId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                lockedPriceId = null,
                transitionType = SubscriptionTransitionType.ADMIN_GRANT,
                sourceType = EventSourceType.ADMIN,
                sourceId = savedGrant.id.toString(),
                actorId = grantedBy,
                reason = reasonNote,
            )
        } else if (existing.status == SubscriptionStatus.EXPIRED) {
            reactivateSubscription(
                existing, planId, periodStart, periodEnd,
                SubscriptionTransitionType.ADMIN_GRANT, EventSourceType.ADMIN, savedGrant.id.toString(),
                actorId = grantedBy,
                reason = reasonNote,
            )
        } else {
            val before = existing.toStateSnapshot()
            val updated = existing.update(
                planId = planId,
                periodEnd = periodEnd,
                cancelAtPeriodEnd = false,
                updatedAt = now,
            )
            val saved = userSubscriptionRepository.save(updated)

            eventService.recordTransition(
                userId = userId,
                transitionType = SubscriptionTransitionType.ADMIN_GRANT,
                sourceType = EventSourceType.ADMIN,
                sourceId = savedGrant.id.toString(),
                before = before,
                after = saved,
                actorId = grantedBy,
                reason = reasonNote,
            )

            outboxWriter.writeSubscriptionEvent(
                aggregateId = saved.id.toString(),
                payload = SubscriptionEventPayload(
                    userId = userId,
                    transitionType = "ADMIN_GRANT",
                    planId = saved.planId,
                    periodStart = saved.periodStart,
                    periodEnd = saved.periodEnd,
                    occurredAt = now,
                ),
            )
        }

        outboxWriter.writeSubscriptionEvent(
            aggregateId = savedGrant.id.toString(),
            payload = SubscriptionEventPayload(
                userId = userId,
                transitionType = "MANUAL_GRANT_CREATED",
                planId = planId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                occurredAt = now,
            ),
        )

        return savedGrant
    }

    /**
     * Extend a manual grant through the same subscription lifecycle/audit/outbox path as grant creation.
     */
    @Transactional
    fun extendGrant(grantId: UUID, additionalDays: Int, adminId: UUID, reason: String): ManualGrant {
        val grant = manualGrantRepository.findById(grantId)
            .orElseThrow { ManualGrantNotFoundException() }
        val now = timeProvider.now()
        val currentExpiresAt = grant.expiresAt
        val extensionSourceId = "manual_grant_extension_${grantId}_${UUID.randomUUID()}"

        if (grant.revokedAt != null || currentExpiresAt == null || !currentExpiresAt.isAfter(now)) {
            throw ManualGrantNotFoundException()
        }

        val previousExpiresAt = grant.expiresAt
        val savedGrant = manualGrantRepository.save(
            grant.update(
                durationDays = (grant.durationDays ?: 0) + additionalDays,
            ),
        )
        val extendedGrant = manualGrantRepository.findById(savedGrant.id!!)
            .orElseThrow { ManualGrantNotFoundException() }

        val subscription = userSubscriptionRepository.findByUserId(grant.userId).orElse(null)
        if (subscription == null || subscription.status == SubscriptionStatus.EXPIRED) {
            val periodStart = now
            val periodEnd = periodStart.plus((extendedGrant.durationDays ?: 0).toLong(), ChronoUnit.DAYS)
            val recomputedGrant = manualGrantRepository.save(
                extendedGrant.update(
                    periodStart = periodStart,
                    expiresAt = periodEnd,
                ),
            )
            createOrReactivateGrantSubscription(
                subscription = subscription,
                grant = recomputedGrant,
                now = now,
                transitionType = SubscriptionTransitionType.ADMIN_GRANT_EXTEND,
                sourceId = extensionSourceId,
                actorId = adminId,
                reason = reason,
            )
        } else {
            val before = subscription.toStateSnapshot()
            val recomputedGrants = recomputeManualGrantPeriods(
                subscription = subscription,
                removedGrant = null,
                remainingGrants = manualGrantRepository.findEffectiveByUserId(grant.userId, now),
                now = now,
            )
            val bestGrant = selectBestGrant(recomputedGrants) ?: extendedGrant
            val finalPeriodEnd = recomputedGrants.mapNotNull { it.expiresAt }.maxOrNull()
                ?: bestGrant.expiresAt
            val updated = subscription.update(
                planId = bestGrant.planId,
                status = SubscriptionStatus.ACTIVE,
                periodEnd = finalPeriodEnd,
                cancelAtPeriodEnd = false,
                updatedAt = now,
            )
            val saved = userSubscriptionRepository.save(updated)
            recordLifecycleTransition(
                subscription = saved,
                before = before,
                transitionType = SubscriptionTransitionType.ADMIN_GRANT_EXTEND,
                sourceType = EventSourceType.ADMIN,
                sourceId = extensionSourceId,
                actorId = adminId,
                reason = reason,
                now = now,
            )
        }

        val responseGrant = manualGrantRepository.findById(savedGrant.id!!)
            .orElseThrow { ManualGrantNotFoundException() }
        outboxWriter.writeSubscriptionEvent(
            aggregateId = responseGrant.id.toString(),
            payload = SubscriptionEventPayload(
                userId = responseGrant.userId,
                transitionType = "MANUAL_GRANT_EXTENDED",
                planId = responseGrant.planId,
                periodStart = previousExpiresAt,
                periodEnd = responseGrant.expiresAt,
                occurredAt = now,
            ),
        )

        return responseGrant
    }

    /**
     * Revoke a manual grant.
     */
    @Transactional
    fun revokeGrant(grantId: UUID, revokedBy: UUID, reason: String) {
        val grant = manualGrantRepository.findById(grantId)
            .orElseThrow { ManualGrantNotFoundException() }

        if (grant.revokedAt != null) {
            return // Already revoked, idempotent
        }

        val now = timeProvider.now()
        val revoked = grant.update(
            revokedAt = now,
            revokedBy = revokedBy,
            revokeReason = reason,
        )
        manualGrantRepository.save(revoked)
        applyManualGrantRemoval(
            grant = grant,
            transitionType = SubscriptionTransitionType.ADMIN_REVOKE,
            sourceType = EventSourceType.ADMIN,
            sourceId = grantId.toString(),
            actorId = revokedBy,
            reason = reason,
            now = now,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = grantId.toString(),
            payload = SubscriptionEventPayload(
                userId = grant.userId,
                transitionType = "MANUAL_GRANT_REVOKED",
                planId = grant.planId,
                periodStart = null,
                periodEnd = null,
                occurredAt = now,
            ),
        )
    }

    @Transactional
    fun expireGrant(grantId: UUID) {
        val grant = manualGrantRepository.findById(grantId)
            .orElseThrow { ManualGrantNotFoundException() }

        if (grant.revokedAt != null) {
            return
        }

        val now = timeProvider.now()
        val expiresAt = grant.expiresAt
        if (expiresAt == null || expiresAt.isAfter(now)) {
            return
        }

        val expired = grant.update(
            revokedAt = now,
            revokeReason = "expired",
        )
        manualGrantRepository.save(expired)
        applyManualGrantRemoval(
            grant = grant,
            transitionType = SubscriptionTransitionType.PERIOD_EXPIRED,
            sourceType = EventSourceType.SYSTEM,
            sourceId = "manual_grant_expiry_$grantId",
            actorId = null,
            reason = "expired",
            now = now,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = grantId.toString(),
            payload = SubscriptionEventPayload(
                userId = grant.userId,
                transitionType = "MANUAL_GRANT_EXPIRED",
                planId = grant.planId,
                periodStart = null,
                periodEnd = grant.expiresAt,
                occurredAt = now,
            ),
        )
    }

    private fun applyManualGrantRemoval(
        grant: ManualGrant,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        actorId: UUID?,
        reason: String,
        now: Instant,
    ) {
        val subscription = userSubscriptionRepository.findByUserId(grant.userId).orElse(null) ?: return
        val before = subscription.toStateSnapshot()
        val remainingGrants = manualGrantRepository.findEffectiveByUserId(grant.userId, now)
        val recomputed = recomputeManualGrantPeriods(
            subscription = subscription,
            removedGrant = grant,
            remainingGrants = remainingGrants,
            now = now,
        )
        val remainingGrant = selectBestGrant(recomputed)

        val updated = if (remainingGrant != null) {
            subscription.update(
                planId = remainingGrant.planId,
                status = SubscriptionStatus.ACTIVE,
                periodEnd = recomputed.mapNotNull { it.expiresAt }.maxOrNull(),
                cancelAtPeriodEnd = false,
                updatedAt = now,
            )
        } else {
            val effectivePeriodEnd = paidBaseEnd(subscription, listOf(grant), now)
            val status = if (effectivePeriodEnd.isAfter(now)) {
                SubscriptionStatus.ACTIVE
            } else {
                SubscriptionStatus.EXPIRED
            }
            subscription.update(
                status = status,
                periodEnd = effectivePeriodEnd,
                cancelAtPeriodEnd = false,
                updatedAt = now,
            )
        }

        val saved = userSubscriptionRepository.save(updated)
        recordLifecycleTransition(
            subscription = saved,
            before = before,
            transitionType = transitionType,
            sourceType = sourceType,
            sourceId = sourceId,
            actorId = actorId,
            reason = reason,
            now = now,
        )
    }

    private fun createOrReactivateGrantSubscription(
        subscription: UserSubscription?,
        grant: ManualGrant,
        now: Instant,
        transitionType: SubscriptionTransitionType,
        sourceId: String,
        actorId: UUID,
        reason: String,
    ) {
        if (subscription == null) {
            createSubscriptionInternal(
                userId = grant.userId,
                planId = grant.planId,
                periodStart = now,
                periodEnd = grant.expiresAt,
                lockedPriceId = null,
                transitionType = transitionType,
                sourceType = EventSourceType.ADMIN,
                sourceId = sourceId,
                actorId = actorId,
                reason = reason,
            )
            return
        }

        val before = subscription.toStateSnapshot()
        val updated = subscription.update(
            planId = grant.planId,
            status = SubscriptionStatus.ACTIVE,
            periodStart = now,
            periodEnd = grant.expiresAt,
            cancelAtPeriodEnd = false,
            gracePeriodEnd = null,
            graceReason = null,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)
        recordLifecycleTransition(
            subscription = saved,
            before = before,
            transitionType = transitionType,
            sourceType = EventSourceType.ADMIN,
            sourceId = sourceId,
            actorId = actorId,
            reason = reason,
            now = now,
        )
    }

    private fun recordLifecycleTransition(
        subscription: UserSubscription,
        before: SubscriptionStateSnapshot?,
        transitionType: SubscriptionTransitionType,
        sourceType: EventSourceType,
        sourceId: String,
        actorId: UUID?,
        reason: String?,
        now: Instant,
    ) {
        eventService.recordTransition(
            userId = subscription.userId,
            transitionType = transitionType,
            sourceType = sourceType,
            sourceId = sourceId,
            before = before,
            after = subscription,
            actorId = actorId,
            reason = reason,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = subscription.id.toString(),
            payload = SubscriptionEventPayload(
                userId = subscription.userId,
                transitionType = transitionType.name,
                planId = subscription.planId,
                periodStart = subscription.periodStart,
                periodEnd = subscription.periodEnd,
                occurredAt = now,
            ),
        )
    }

    private fun selectBestGrant(grants: List<ManualGrant>): ManualGrant? {
        return grants.maxWithOrNull(
            compareBy<ManualGrant> { planRank(it.planId) }
                .thenBy { it.expiresAt ?: Instant.MAX }
                .thenBy { it.createdAt }
                .thenBy { it.id.toString() },
        )
    }

    private fun recomputeManualGrantPeriods(
        subscription: UserSubscription,
        removedGrant: ManualGrant?,
        remainingGrants: List<ManualGrant>,
        now: Instant,
    ): List<ManualGrant> {
        val baseEnd = if (removedGrant == null) {
            remainingGrants.mapNotNull { it.periodStart }.minOrNull()
                ?: paidBaseEnd(subscription, remainingGrants, now)
        } else {
            paidBaseEnd(subscription, remainingGrants + removedGrant, now)
        }
        var cursor = if (removedGrant == null || baseEnd.isAfter(now)) baseEnd else now
        return remainingGrants.map { grant ->
            val durationDays = grant.durationDays ?: return@map grant
            val periodStart = cursor
            val periodEnd = periodStart.plus(durationDays.toLong(), ChronoUnit.DAYS)
            cursor = periodEnd
            if (grant.periodStart == periodStart && grant.expiresAt == periodEnd) {
                grant
            } else {
                manualGrantRepository.save(
                    grant.update(
                        periodStart = periodStart,
                        expiresAt = periodEnd,
                    ),
                )
            }
        }
    }

    private fun paidBaseEnd(subscription: UserSubscription, grants: List<ManualGrant>, now: Instant): Instant {
        val periodEnd = subscription.periodEnd ?: now
        val manualDays = grants.sumOf { it.durationDays ?: 0 }
        return periodEnd.minus(manualDays.toLong(), ChronoUnit.DAYS)
    }

    private fun isLowerTierGrant(newPlanId: Long, existingPlanId: Long): Boolean {
        val newFeatures = planFeatureRepository.findEnabledByPlanId(newPlanId).map { it.featureKey }.toSet()
        val existingFeatures = planFeatureRepository.findEnabledByPlanId(existingPlanId).map { it.featureKey }.toSet()
        if (newFeatures.containsAll(existingFeatures)) {
            return false
        }
        return planRank(newPlanId) < planRank(existingPlanId)
    }

    private fun planRank(planId: Long): Int {
        val plan = planRepository.findById(planId)
            .orElseThrow { com.gyro.api.common.error.ResourceNotFoundException("Subscription plan") }
        val featureCount = planFeatureRepository.findEnabledByPlanId(planId).size
        return if (plan.free) featureCount else 1_000 + featureCount
    }

    /**
     * Block billing for a user. Subscription moves to BILLED_BLOCKED.
     */
    @Transactional
    fun blockBilling(userId: UUID, actorId: UUID, reason: String) {
        val subscription = userSubscriptionRepository.findByUserId(userId)
            .orElseThrow { SubscriptionNotFoundException() }

        if (subscription.status == SubscriptionStatus.BILLED_BLOCKED) {
            return // Already blocked, idempotent
        }

        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()
        val updated = subscription.update(
            status = SubscriptionStatus.BILLED_BLOCKED,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = userId,
            transitionType = SubscriptionTransitionType.ADMIN_BILLING_BLOCK,
            sourceType = EventSourceType.ADMIN,
            sourceId = "billing_block_$userId",
            before = before,
            after = saved,
            actorId = actorId,
            reason = reason,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = userId,
                transitionType = "ADMIN_BILLING_BLOCK",
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )
    }

    /**
     * Unblock billing. Recomputes state: ACTIVE if periodEnd in future, EXPIRED otherwise.
     */
    @Transactional
    fun unblockBilling(userId: UUID, actorId: UUID, reason: String) {
        val subscription = userSubscriptionRepository.findByUserId(userId)
            .orElseThrow { SubscriptionNotFoundException() }

        if (subscription.status != SubscriptionStatus.BILLED_BLOCKED) {
            return // Not blocked, idempotent
        }

        val now = timeProvider.now()
        val before = subscription.toStateSnapshot()

        val newStatus = if (subscription.periodEnd != null && subscription.periodEnd!!.isAfter(now)) {
            SubscriptionStatus.ACTIVE
        } else {
            SubscriptionStatus.EXPIRED
        }

        val updated = subscription.update(
            status = newStatus,
            updatedAt = now,
        )
        val saved = userSubscriptionRepository.save(updated)

        eventService.recordTransition(
            userId = userId,
            transitionType = SubscriptionTransitionType.ADMIN_BILLING_UNBLOCK,
            sourceType = EventSourceType.ADMIN,
            sourceId = "billing_unblock_$userId",
            before = before,
            after = saved,
            actorId = actorId,
            reason = reason,
        )

        outboxWriter.writeSubscriptionEvent(
            aggregateId = saved.id.toString(),
            payload = SubscriptionEventPayload(
                userId = userId,
                transitionType = "ADMIN_BILLING_UNBLOCK",
                planId = saved.planId,
                periodStart = saved.periodStart,
                periodEnd = saved.periodEnd,
                occurredAt = now,
            ),
        )
    }
}
