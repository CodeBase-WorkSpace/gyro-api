package com.gyro.api.subscription.application

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.BillingBlockedException
import com.gyro.api.common.error.InvoiceNotFoundException
import com.gyro.api.common.error.InvoiceStatusConflictException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.Invoice
import com.gyro.api.subscription.domain.InvoiceStatus
import com.gyro.api.subscription.domain.PaymentProvider
import com.gyro.api.subscription.domain.SubscriptionStatus
import com.gyro.api.subscription.infrastructure.InvoiceRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPriceRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val priceRepository: SubscriptionPriceRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val userSubscriptionRepository: UserSubscriptionRepository,
    private val promotionService: PromotionService,
    private val affiliateCommissionService: AffiliateCommissionService,
    private val userRepository: UserRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createInvoice(
        userId: UUID,
        priceId: Long,
        promotionCode: String? = null,
    ): Invoice {
        val price = priceRepository.findById(priceId)
            .orElseThrow { ResourceNotFoundException("SubscriptionPrice") }

        // Reject inactive prices
        if (!price.active) {
            log.warn(
                "event=subscription_price_rejection reason=inactive_price price_id={} plan_id={}",
                price.id,
                price.planId,
            )
            throw ResourceNotFoundException("SubscriptionPrice")
        }

        // Reject free/lifetime prices (billingPeriodDays <= 0)
        if (price.billingPeriodDays <= 0) {
            log.warn(
                "event=subscription_price_rejection reason=invalid_billing_period price_id={} billing_period_days={}",
                price.id,
                price.billingPeriodDays,
            )
            throw InvoiceStatusConflictException("billingPeriodDays > 0", "billingPeriodDays = ${price.billingPeriodDays}")
        }

        val plan = planRepository.findById(price.planId)
            .orElseThrow { ResourceNotFoundException("SubscriptionPlan") }

        if (!plan.active) {
            log.warn(
                "event=subscription_price_rejection reason=inactive_plan price_id={} plan_id={}",
                price.id,
                plan.id,
            )
            throw ResourceNotFoundException("SubscriptionPlan")
        }

        // Reject BILLED_BLOCKED users
        val existingSubscription = userSubscriptionRepository.findByUserId(userId).orElse(null)
        if (existingSubscription?.status == SubscriptionStatus.BILLED_BLOCKED) {
            throw BillingBlockedException()
        }

        val now = timeProvider.now()

        // Compute billing period
        val periodStart = if (existingSubscription != null &&
            existingSubscription.status == SubscriptionStatus.ACTIVE &&
            existingSubscription.periodEnd != null &&
            existingSubscription.periodEnd!!.isAfter(now)
        ) {
            existingSubscription.periodEnd!!
        } else {
            now
        }

        val basePeriodEnd = periodStart.plus(price.billingPeriodDays.toLong(), ChronoUnit.DAYS)
        val promotionApplication = promotionService.applyForCheckout(
            rawCode = promotionCode,
            userId = userId,
            planId = price.planId,
            priceId = priceId,
            amount = price.price,
            periodEnd = basePeriodEnd,
            idempotencyKey = null,
        )

        val invoice = Invoice(
            userId = userId,
            planId = price.planId,
            periodStart = periodStart,
            periodEnd = promotionApplication.periodEnd,
            amountDue = price.price,
            amountAfterDiscount = promotionApplication.amountAfterDiscount,
            status = InvoiceStatus.OPEN,
            promotionCode = promotionApplication.code,
            manual = false,
            subscriptionPriceId = priceId,
        )

        val saved = invoiceRepository.save(invoice)
        promotionService.reservePromotion(
            application = promotionApplication,
            userId = userId,
            invoiceId = saved.id!!,
            paymentAttemptId = null,
            provider = PaymentProvider.MANUAL,
            idempotencyKey = null,
        )

        return saved
    }

    /**
     * Mark invoice as paid. Idempotent: returns existing PAID invoice if already paid.
     */
    @Transactional
    fun markPaid(invoiceId: UUID): Invoice {
        // Resolve ownership as a scalar so the invoice itself is first loaded only after its
        // row lock is acquired; otherwise a managed pre-lock snapshot could overwrite a void.
        val invoiceOwnerId = invoiceRepository.findUserIdById(invoiceId)
            ?: throw InvoiceNotFoundException()
        userRepository.findByIdForUpdate(invoiceOwnerId)
            .orElseThrow { ResourceNotFoundException("User") }
        val invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow { InvoiceNotFoundException() }

        return when (invoice.status) {
            InvoiceStatus.OPEN -> {
                invoice.status = InvoiceStatus.PAID
                invoice.updatedAt = timeProvider.now()
                val paid = invoiceRepository.save(invoice)
                val redemption = promotionService.redeemInvoicePromotion(invoiceId)
                affiliateCommissionService.createForPaidInvoice(paid, redemption)
                paid
            }
            InvoiceStatus.PAID -> invoice // Idempotent: already paid
            else -> throw InvoiceStatusConflictException(InvoiceStatus.OPEN.name, invoice.status.name)
        }
    }

    @Transactional
    fun voidInvoice(invoiceId: UUID, reason: String): Invoice {
        val invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow { InvoiceNotFoundException() }

        if (invoice.status != InvoiceStatus.OPEN) {
            throw InvoiceStatusConflictException(InvoiceStatus.OPEN.name, invoice.status.name)
        }

        invoice.status = InvoiceStatus.VOID
        invoice.updatedAt = timeProvider.now()
        val saved = invoiceRepository.save(invoice)
        promotionService.releaseInvoiceReservation(invoiceId)
        return saved
    }

    @Transactional(readOnly = true)
    fun getInvoice(invoiceId: UUID): Invoice {
        return invoiceRepository.findById(invoiceId)
            .orElseThrow { InvoiceNotFoundException() }
    }
}
