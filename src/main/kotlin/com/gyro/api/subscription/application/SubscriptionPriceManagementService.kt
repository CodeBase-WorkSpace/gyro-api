package com.gyro.api.subscription.application

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.error.SubscriptionPriceManagementException
import com.gyro.api.common.request.RequestIds
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.Money
import com.gyro.api.subscription.domain.CatalogPriceCalculator
import com.gyro.api.subscription.domain.SubscriptionPrice
import com.gyro.api.subscription.infrastructure.*
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateSubscriptionPriceCommand(
    val planId: Long,
    val billingPeriodDays: Int,
    val baseAmount: BigDecimal,
    val discountPercent: BigDecimal,
    val currency: String,
    val badge: String?,
    val validFrom: Instant,
    val internalNotes: String?,
    val expectedCurrentPriceId: Long?,
)
data class PriceMutationResult(val created: SubscriptionPrice, val retired: SubscriptionPrice?)
data class PriceImpact(val activeSubscriptions: Long, val openInvoices: Long, val pendingPaymentAttempts: Long, val activePromotions: Long)

@Service
class SubscriptionPriceManagementService(
    private val prices: SubscriptionPriceRepository,
    private val plans: SubscriptionPlanRepository,
    private val subscriptions: UserSubscriptionRepository,
    private val invoices: InvoiceRepository,
    private val attempts: PaymentAttemptRepository,
    private val promotions: PromotionRepository,
    private val audit: AccountAuditService,
    private val metrics: SubscriptionPriceManagementMetrics,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun plans() = plans.findByActiveTrueOrderByFreeDescCodeAsc().map { it to prices.findAll(Specification { root, _, cb -> cb.equal(root.get<Long>("planId"), it.id) }, PageRequest.of(0, 500)).content }

    @Transactional(readOnly = true)
    fun search(planId: Long?, active: Boolean?, days: Int?, page: Int, size: Int): Page<SubscriptionPrice> {
        var spec: Specification<SubscriptionPrice> = Specification { _, _, _ -> null }
        planId?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<Long>("planId"), value) } }
        active?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<Boolean>("active"), value) } }
        days?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<Int>("billingPeriodDays"), value) } }
        return prices.findAll(spec, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))
    }

    @Transactional
    fun create(command: CreateSubscriptionPriceCommand, actorId: UUID): PriceMutationResult {
        val plan = plans.findById(command.planId).orElseThrow { ResourceNotFoundException("SubscriptionPlan") }
        val currency = command.currency.trim().uppercase()
        val now = timeProvider.now()
        val amount = try {
            CatalogPriceCalculator.calculate(command.baseAmount, command.discountPercent)
        } catch (_: ArithmeticException) {
            throw SubscriptionPriceManagementException("Base amount and discount may have at most two decimal places.")
        } catch (_: IllegalArgumentException) {
            throw SubscriptionPriceManagementException("Catalog discount configuration is invalid.")
        }
        val validPlanPrice = if (plan.free) {
            command.billingPeriodDays == 0 &&
                command.baseAmount.compareTo(BigDecimal.ZERO) == 0 &&
                command.discountPercent.compareTo(BigDecimal.ZERO) == 0 &&
                amount.compareTo(BigDecimal.ZERO) == 0
        } else {
            command.billingPeriodDays > 0 && command.baseAmount > BigDecimal.ZERO && amount > BigDecimal.ZERO
        }
        if (!plan.active || !validPlanPrice || currency !in setOf("IRR")) throw SubscriptionPriceManagementException("Price configuration is invalid.")
        if (command.validFrom.isBefore(now.minusSeconds(1))) throw SubscriptionPriceManagementException("validFrom cannot be in the past.")
        val current = prices.findActiveForUpdate(command.planId, command.billingPeriodDays, currency).orElse(null)
        if (current?.id != command.expectedCurrentPriceId) throw SubscriptionPriceManagementException("Current price changed. Refresh and try again.", true)
        val scheduled = command.validFrom.isAfter(now.plusSeconds(1))
        if (prices.existsByPlanIdAndBillingPeriodDaysAndPriceCurrencyAndScheduledTrue(command.planId, command.billingPeriodDays, currency)) throw SubscriptionPriceManagementException("A scheduled price already exists for this duration. Cancel it before creating another replacement.", true)
        var retired: SubscriptionPrice? = null
        if (!scheduled && current != null) {
            current.active = false
            current.validUntil = now
            current.deactivatedBy = actorId
            current.deactivationReason = "Replaced by a new price version"
            retired = prices.save(current)
            prices.flush()
        }
        val created = try {
            prices.saveAndFlush(SubscriptionPrice(planId = command.planId, billingPeriodDays = command.billingPeriodDays, price = Money(amount, currency), baseAmount = command.baseAmount.setScale(2), discountPercent = command.discountPercent.setScale(2), badge = command.badge, active = !scheduled, scheduled = scheduled, expectedPredecessorId = current?.id.takeIf { scheduled }, validFrom = command.validFrom, createdBy = actorId, internalNotes = command.internalNotes))
        } catch (_: DataIntegrityViolationException) {
            metrics.mutation("create", "conflict")
            throw SubscriptionPriceManagementException("Another administrator changed this price. Refresh and try again.", true)
        }
        record(actorId, "create", created, current, command.internalNotes)
        metrics.mutation(if (scheduled) "schedule" else "activate", "success")
        return PriceMutationResult(created, retired)
    }

    @Transactional
    fun deactivate(id: Long, actorId: UUID, reason: String): SubscriptionPrice {
        if (reason.isBlank()) throw SubscriptionPriceManagementException("A deactivation reason is required.")
        val existing = prices.findById(id).orElseThrow { ResourceNotFoundException("SubscriptionPrice") }
        if (!existing.active && !existing.scheduled) return existing
        val plan = plans.findById(existing.planId).orElseThrow { ResourceNotFoundException("SubscriptionPlan") }
        if (!plan.free && existing.active) throw SubscriptionPriceManagementException("Create a safe replacement in the same transaction before deactivating a paid price.", true)
        existing.active = false
        existing.scheduled = false
        existing.validUntil = timeProvider.now()
        existing.deactivatedBy = actorId
        existing.deactivationReason = reason.trim()
        val updated = prices.save(existing)
        record(actorId, "deactivate", updated, existing, reason)
        metrics.mutation("deactivate", "success")
        return updated
    }

    @Transactional(readOnly = true)
    fun impact(id: Long): PriceImpact {
        val price = prices.findById(id).orElseThrow { ResourceNotFoundException("SubscriptionPrice") }
        return PriceImpact(subscriptions.countActiveLockedToPrice(id), invoices.countOpenBySubscriptionPriceId(id), attempts.countPendingBySubscriptionPriceId(id), promotions.countActiveApplicableToPrice(id, price.planId))
    }

    @Scheduled(fixedDelayString = "\${app.billing.price-activation-delay:60000}")
    @Transactional
    fun activateScheduledPrices() {
        val now = timeProvider.now()
        prices.findDueScheduledForUpdate(now).forEach { scheduled ->
            val current = prices.findActiveForUpdate(scheduled.planId, scheduled.billingPeriodDays, scheduled.price.currency).orElse(null)
            if (current?.id != scheduled.expectedPredecessorId) {
                val reason = "Expected active price ${scheduled.expectedPredecessorId ?: "none"}, but found ${current?.id ?: "none"}. Administrator reconciliation is required."
                scheduled.activationConflictedAt = now
                scheduled.activationConflictReason = reason
                val conflicted = prices.save(scheduled)
                scheduled.createdBy?.let { record(it, "scheduled_conflict", conflicted, current, reason) }
                metrics.mutation("scheduled_activate", "conflict")
                log.warn("event=subscription_price_schedule_conflict scheduledPriceId={} expectedPredecessorId={} actualPredecessorId={} requestId={}", scheduled.id, scheduled.expectedPredecessorId, current?.id, RequestIds.current())
                return@forEach
            }
            current?.let {
                it.active = false
                it.validUntil = now
                it.deactivatedBy = scheduled.createdBy
                it.deactivationReason = "Scheduled price activation"
                prices.save(it)
            }
            prices.flush()
            scheduled.active = true
            scheduled.scheduled = false
            prices.save(scheduled)
            scheduled.createdBy?.let { record(it, "scheduled_activate", scheduled, current, "Scheduled activation") }
            metrics.mutation("scheduled_activate", "success")
        }
    }

    private fun record(actor: UUID, action: String, target: SubscriptionPrice, old: SubscriptionPrice?, reason: String?) {
        val metadata = mapOf("action" to action, "priceId" to target.id, "planId" to target.planId, "billingPeriodDays" to target.billingPeriodDays, "oldBaseAmount" to old?.baseAmount, "newBaseAmount" to target.baseAmount, "oldDiscountPercent" to old?.discountPercent, "newDiscountPercent" to target.discountPercent, "oldAmount" to old?.price?.amount, "newAmount" to target.price.amount, "requestId" to RequestIds.current())
        audit.record(actor, actor, AccountAuditEventType.ADMIN_SUBSCRIPTION_PRICE_CHANGED, reason, metadata)
        log.info("event=admin_subscription_price_changed action={} actorId={} priceId={} planId={} billingPeriodDays={} requestId={}", action, actor, target.id, target.planId, target.billingPeriodDays, RequestIds.current())
    }
}
