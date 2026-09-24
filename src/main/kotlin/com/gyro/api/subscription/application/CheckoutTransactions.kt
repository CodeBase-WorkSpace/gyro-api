package com.gyro.api.subscription.application

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.time.temporal.ChronoUnit
import java.util.*

/** Owns the committed database phases on either side of the checkout provider call. */
@Service
class CheckoutTransactions(
    private val invoiceRepository: InvoiceRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val priceRepository: SubscriptionPriceRepository,
    private val providerMappingRepository: ProviderPriceMappingRepository,
    private val userSubscriptionRepository: UserSubscriptionRepository,
    private val promotionService: PromotionService,
    private val timeProvider: TimeProvider,
    private val environment: Environment,
) {
    @Transactional
    fun validatePromotion(
        userId: UUID,
        priceId: Long,
        promotionCode: String?,
    ): PromotionValidationResult {
        val now = timeProvider.now()
        val price = priceRepository.findById(priceId)
            .orElseThrow { ResourceNotFoundException("SubscriptionPrice") }
        if (!price.active) {
            throw ResourceNotFoundException("SubscriptionPrice")
        }

        val checkoutAmount = resolveCheckoutAmount(PaymentProvider.PAYPING, priceId, price.price)
        val periodStart = resolvePeriodStart(userId, now)
        val basePeriodEnd = periodStart.plus(price.billingPeriodDays.toLong(), ChronoUnit.DAYS)
        val promotionApplication = promotionService.validateForCheckout(
            rawCode = promotionCode,
            userId = userId,
            planId = price.planId,
            priceId = priceId,
            amount = checkoutAmount,
            periodEnd = basePeriodEnd,
        )
        val discountAmount = checkoutAmount.copy(
            amount = (checkoutAmount.amount - promotionApplication.amountAfterDiscount.amount)
                .max(java.math.BigDecimal.ZERO),
        )

        return PromotionValidationResult(
            promotionCode = requireNotNull(promotionApplication.code),
            amountBeforeDiscount = checkoutAmount,
            amountAfterDiscount = promotionApplication.amountAfterDiscount,
            discountAmount = discountAmount,
            promotionType = requireNotNull(promotionApplication.type).name,
            redemptionMode = if (promotionApplication.type == PromotionType.EARLY_SUPPORTER_ACCESS) {
                "FREE_ACTIVATION"
            } else {
                "PAID_CHECKOUT"
            },
            freeDays = promotionApplication.type
                .takeIf {
                    it == PromotionType.EARLY_SUPPORTER_ACCESS ||
                            it == PromotionType.FREE_DAYS ||
                            it == PromotionType.TRIAL_EXTENSION
                }
                ?.let { promotionService.freeDaysFor(requireNotNull(promotionApplication.promotionId)) },
        )
    }

    @Transactional
    fun prepareCheckout(
        userId: UUID,
        priceId: Long,
        promotionCode: String?,
        idempotencyKey: String?,
    ): PreparedCheckout {
        val now = timeProvider.now()
        val price = priceRepository.findById(priceId)
            .orElseThrow { ResourceNotFoundException("SubscriptionPrice") }
        if (!price.active) {
            throw ResourceNotFoundException("SubscriptionPrice")
        }

        val checkoutAmount = resolveCheckoutAmount(PaymentProvider.PAYPING, priceId, price.price)
        val periodStart = resolvePeriodStart(userId, now)
        val basePeriodEnd = periodStart.plus(price.billingPeriodDays.toLong(), ChronoUnit.DAYS)
        val promotionApplication = promotionService.applyForCheckout(
            rawCode = promotionCode,
            userId = userId,
            planId = price.planId,
            priceId = priceId,
            amount = checkoutAmount,
            periodEnd = basePeriodEnd,
            idempotencyKey = idempotencyKey,
        )

        val invoice = invoiceRepository.save(
            Invoice(
                userId = userId,
                planId = price.planId,
                periodStart = periodStart,
                periodEnd = promotionApplication.periodEnd,
                amountDue = checkoutAmount,
                amountAfterDiscount = promotionApplication.amountAfterDiscount,
                status = InvoiceStatus.OPEN,
                promotionCode = promotionApplication.code,
                manual = false,
                subscriptionPriceId = priceId,
            ),
        )
        val invoiceId = requireNotNull(invoice.id)

        val clientRefId = "checkout_${userId}_${now.toEpochMilli()}_${UUID.randomUUID()}"
        val paymentAttempt = paymentAttemptRepository.save(
            PaymentAttempt(
                invoiceId = invoiceId,
                provider = PaymentProvider.PAYPING,
                clientRefId = clientRefId,
                amount = promotionApplication.amountAfterDiscount,
                status = PaymentAttemptStatus.PENDING,
            ),
        )
        val paymentAttemptId = requireNotNull(paymentAttempt.id)

        promotionService.reservePromotion(
            application = promotionApplication,
            userId = userId,
            invoiceId = invoiceId,
            paymentAttemptId = paymentAttemptId,
            provider = PaymentProvider.PAYPING,
            idempotencyKey = idempotencyKey,
        )

        return PreparedCheckout(
            invoiceId = invoiceId,
            paymentAttemptId = paymentAttemptId,
            clientRefId = clientRefId,
            checkoutAmount = promotionApplication.amountAfterDiscount,
        )
    }

    @Transactional
    fun recordProviderSuccess(
        paymentAttemptId: UUID,
        providerCode: String,
        providerRequestId: String?,
    ) {
        val paymentAttempt = paymentAttemptRepository.findById(paymentAttemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        paymentAttempt.providerCode = providerCode
        paymentAttempt.providerRequestId = providerRequestId
        paymentAttempt.status = PaymentAttemptStatus.PENDING
        paymentAttempt.updatedAt = timeProvider.now()
    }

    @Transactional
    fun recordProviderFailure(invoiceId: UUID, paymentAttemptId: UUID) {
        val paymentAttempt = paymentAttemptRepository.findById(paymentAttemptId)
            .orElseThrow { ResourceNotFoundException("PaymentAttempt") }
        paymentAttempt.status = PaymentAttemptStatus.CREATE_FAILED
        paymentAttempt.updatedAt = timeProvider.now()
        promotionService.releaseInvoiceReservation(invoiceId)
    }

    private fun resolveCheckoutAmount(provider: PaymentProvider, priceId: Long, catalogAmount: Money): Money {
        val mapping = providerMappingRepository.findBySubscriptionPriceIdAndProviderAndEnvironmentAndActiveTrue(
            subscriptionPriceId = priceId,
            provider = provider,
            environment = ProviderEnvironment.PROD,
        )
        return mapping.map {
            val override = it.providerAmountOverride
            check(override == null || !environment.acceptsProfiles(Profiles.of("prod"))) {
                "Provider amount overrides are forbidden in production."
            }
            override ?: catalogAmount
        }.orElse(catalogAmount)
    }

    private fun resolvePeriodStart(userId: UUID, now: java.time.Instant): java.time.Instant {
        val existingSubscription = userSubscriptionRepository.findByUserId(userId).orElse(null)
        return if (existingSubscription != null &&
            existingSubscription.status == SubscriptionStatus.ACTIVE &&
            existingSubscription.periodEnd != null &&
            existingSubscription.periodEnd!!.isAfter(now)
        ) {
            existingSubscription.periodEnd!!
        } else {
            now
        }
    }
}

data class PreparedCheckout(
    val invoiceId: UUID,
    val paymentAttemptId: UUID,
    val clientRefId: String,
    val checkoutAmount: Money,
)
