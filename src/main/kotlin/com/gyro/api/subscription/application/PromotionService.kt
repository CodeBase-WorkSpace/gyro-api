package com.gyro.api.subscription.application

import com.gyro.api.auth.domain.UserStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.AccountDisabledException
import com.gyro.api.common.error.DeletedUserException
import com.gyro.api.common.error.PromotionException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import com.gyro.api.subscription.infrastructure.PromotionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class PromotionService(
    private val promotionRepository: PromotionRepository,
    private val redemptionRepository: PromotionRedemptionRepository,
    private val userRepository: UserRepository,
    private val timeProvider: TimeProvider,
    private val lifecycleService: SubscriptionLifecycleService,
    private val metrics: PromotionMetrics,
    private val affiliateCommissionService: AffiliateCommissionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reservationTtl = java.time.Duration.ofMinutes(15)

    @Transactional
    fun applyForCheckout(
        rawCode: String?,
        userId: UUID,
        planId: Long,
        priceId: Long,
        amount: Money,
        periodEnd: Instant,
        idempotencyKey: String?,
    ): PromotionApplication {
        val code = normalizeCode(rawCode) ?: return PromotionApplication.none(amount, periodEnd)
        val promotion = validateForUpdate(code, userId, planId, priceId)
        val effect = computeEffect(promotion, amount, periodEnd)

        log.info(
            "Promotion validation completed: action=checkout_promotion stage=validate outcome=accepted promotionType={} provider={}",
            promotion.type,
            PaymentProvider.PAYPING,
        )

        metrics.validation("accepted", "none", "checkout")
        return effect
    }

    @Transactional
    fun validateForCheckout(
        rawCode: String?,
        userId: UUID,
        planId: Long,
        priceId: Long,
        amount: Money,
        periodEnd: Instant,
    ): PromotionApplication {
        val code = normalizeCode(rawCode)
            ?: throw PromotionException("promotion_required", "Promotion code is required.")
        val promotion = validateForUpdate(code, userId, planId, priceId)
        val effect = if (promotion.type == PromotionType.EARLY_SUPPORTER_ACCESS) {
            PromotionApplication(promotion.id, promotion.code, promotion.type, amount, periodEnd)
        } else computeEffect(promotion, amount, periodEnd)

        log.info(
            "Promotion validation completed: action=checkout_promotion_preflight stage=validate outcome=accepted promotionType={} provider={}",
            promotion.type,
            PaymentProvider.PAYPING,
        )

        metrics.validation("accepted", "none", "preflight")
        return effect
    }

    @Transactional
    fun reservePromotion(
        application: PromotionApplication,
        userId: UUID,
        invoiceId: UUID,
        paymentAttemptId: UUID?,
        provider: PaymentProvider,
        idempotencyKey: String?,
    ): PromotionRedemption? {
        val promotionId = application.promotionId ?: return null
        val safeKey = idempotencyKey?.takeIf { it.isNotBlank() }
        if (safeKey != null) {
            val existing =
                redemptionRepository.findByUserIdAndPromotionIdAndIdempotencyKey(userId, promotionId, safeKey)
            if (existing.isPresent) {
                return existing.get()
            }
        }
        val now = timeProvider.now()

        val reservation = redemptionRepository.save(
            PromotionRedemption(
                userId = userId,
                promotionId = promotionId,
                invoiceId = invoiceId,
                paymentAttemptId = paymentAttemptId,
                provider = provider,
                reservedAt = now,
                reservationExpiresAt = now.plus(reservationTtl),
                status = PromotionRedemptionStatus.RESERVED,
                idempotencyKey = safeKey,
                safeAuditReference = "promotion:${promotionId}:invoice:$invoiceId",
                discountPercentageSnapshot = application.discountPercentage,
                commissionPercentageSnapshot = affiliateCommissionService.commissionRateFor(promotionId),
            ),
        )

        log.info(
            "Promotion reservation recorded: action=promotion_reserve stage=reserve outcome=success promotionType={} provider={} invoiceId={} paymentAttemptId={}",
            application.type,
            provider,
            invoiceId,
            paymentAttemptId,
        )

        return reservation
    }

    @Transactional
    fun redeemInvoicePromotion(invoiceId: UUID): PromotionRedemption? {
        val existing = redemptionRepository.findByInvoiceId(invoiceId)
            .firstOrNull { it.status == PromotionRedemptionStatus.REDEEMED }
        if (existing != null) {
            return existing
        }

        val reservation = redemptionRepository.findByInvoiceId(invoiceId)
            .firstOrNull { it.status == PromotionRedemptionStatus.RESERVED }
            ?: return null
        val now = timeProvider.now()
        if (!reservation.reservationExpiresAt.isAfter(now)) {
            throw PromotionException(
                "promotion_reservation_expired",
                "Promotion reservation has expired.",
            )
        }

        reservation.status = PromotionRedemptionStatus.REDEEMED
        reservation.redeemedAt = now
        val redeemed = redemptionRepository.save(reservation)

        log.info(
            "Promotion redemption recorded: action=promotion_redeem stage=redeem outcome=success promotionId={} invoiceId={}",
            redeemed.promotionId,
            invoiceId,
        )

        return redeemed
    }

    @Transactional
    fun releaseInvoiceReservation(invoiceId: UUID): PromotionRedemption? {
        val reservation = redemptionRepository.findByInvoiceId(invoiceId)
            .firstOrNull { it.status == PromotionRedemptionStatus.RESERVED }
            ?: return null

        reservation.status = PromotionRedemptionStatus.RELEASED
        reservation.releasedAt = timeProvider.now()
        val released = redemptionRepository.save(reservation)

        log.info(
            "Promotion reservation released: action=promotion_release stage=release outcome=success promotionId={} invoiceId={}",
            released.promotionId,
            invoiceId,
        )

        return released
    }

    @Transactional
    fun redeemEarlySupporter(rawCode: String?, userId: UUID, idempotencyKey: String?): PromotionRedemption {
        val code = normalizeCode(rawCode) ?: throw PromotionException("promotion_required", "Promotion code is required.")
        val promotion = fetchForUpdate(code)
        val key = idempotencyKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            val existing = redemptionRepository.findByUserIdAndPromotionIdAndIdempotencyKey(userId, promotion.id!!, key)
            if (existing.isPresent && existing.get().status == PromotionRedemptionStatus.REDEEMED) return existing.get()
        }
        val planId = promotion.applicablePlanId ?: throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
        assertRedeemable(promotion, userId, planId)
        if (promotion.type != PromotionType.EARLY_SUPPORTER_ACCESS) throw PromotionException("promotion_not_self_redeemable", "Promotion is not self redeemable.")
        val grant = lifecycleService.grantAccess(userId, planId, promotion.value.toInt(), ManualGrantReason.EARLY_SUPPORTER, userId, "promotion:${promotion.code}")
        val now = timeProvider.now()
        return redemptionRepository.save(PromotionRedemption(userId = userId, promotionId = promotion.id!!, manualGrantId = grant.id, reservedAt = now, reservationExpiresAt = now, redeemedAt = now, status = PromotionRedemptionStatus.REDEEMED, idempotencyKey = key, safeAuditReference = "promotion:${promotion.id}:grant:${grant.id}")).also { metrics.redemption("redeemed", "self_redeem") }
    }

    @Transactional
    fun recordManualGrantRedemption(rawCode: String, userId: UUID, planId: Long, grantId: UUID): PromotionRedemption {
        val promotion = validateForUpdate(normalizeCode(rawCode) ?: throw PromotionException("promotion_required", "Promotion code is required."), userId, planId)
        redemptionRepository.findByManualGrantId(grantId)?.let { return it }
        val now = timeProvider.now()
        return redemptionRepository.save(PromotionRedemption(userId = userId, promotionId = promotion.id!!, manualGrantId = grantId, reservedAt = now, reservationExpiresAt = now, redeemedAt = now, status = PromotionRedemptionStatus.REDEEMED, safeAuditReference = "promotion:${promotion.id}:grant:$grantId")).also { metrics.redemption("redeemed", "manual_grant") }
    }

    fun normalizeCode(rawCode: String?): String? =
        rawCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

    @Transactional(readOnly = true)
    fun freeDaysFor(promotionId: Long): Int = promotionRepository.findById(promotionId).orElseThrow().value.toInt()

    private fun validateForUpdate(code: String, userId: UUID, planId: Long, priceId: Long? = null): Promotion = assertRedeemable(fetchForUpdate(code), userId, planId, priceId)

    private fun fetchForUpdate(code: String): Promotion = promotionRepository.findByCodeForUpdate(code)
        .orElseThrow { PromotionException("promotion_unknown", "Promotion code was not found.") }

    private fun assertRedeemable(promotion: Promotion, userId: UUID, planId: Long, priceId: Long? = null): Promotion {
        val user = userRepository.findById(userId).orElseThrow { DeletedUserException() }
        if (user.status == UserStatus.DEACTIVATED) {
            throw DeletedUserException()
        }
        if (user.status == UserStatus.DISABLED) {
            throw AccountDisabledException()
        }

        val now = timeProvider.now()

        if (!promotion.active) {
            throw PromotionException("promotion_inactive", "Promotion code is inactive.")
        }
        if (promotion.startsAt.isAfter(now)) {
            throw PromotionException("promotion_not_started", "Promotion code is not active yet.")
        }
        val endsAt = promotion.endsAt
        if (endsAt != null && !endsAt.isAfter(now)) {
            throw PromotionException("promotion_expired", "Promotion code has expired.")
        }
        if (promotion.applicablePlanId != null && promotion.applicablePlanId != planId) {
            throw PromotionException("promotion_plan_mismatch", "Promotion code is not valid for this plan.")
        }
        if (promotion.applicableSubscriptionPriceId != null && promotion.applicableSubscriptionPriceId != priceId) {
            throw PromotionException("promotion_price_mismatch", "Promotion code is not valid for this billing duration.")
        }
        if (promotion.perUserRedemptionLimit < 1) {
            throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
        }
        val maxRedemptions = promotion.maxRedemptions
        if (maxRedemptions != null &&
            redemptionRepository.countActiveByPromotionId(promotion.id!!, now) >= maxRedemptions
        ) {
            metrics.limit("global")
            throw PromotionException("promotion_limit_reached", "Promotion redemption limit has been reached.")
        }
        if (redemptionRepository.countActiveByUserIdAndPromotionId(userId, promotion.id!!, now) >= promotion.perUserRedemptionLimit) {
            metrics.limit("per_user")
            throw PromotionException("promotion_user_limit_reached", "Promotion user redemption limit has been reached.")
        }

        // Keep affiliate checks after the established promotion checks so existing stable
        // reason-code precedence remains unchanged.
        affiliateCommissionService.validateEligibility(promotion.id!!, userId)

        return promotion
    }

    private fun computeEffect(promotion: Promotion, amount: Money, periodEnd: Instant): PromotionApplication {
        if (promotion.value <= BigDecimal.ZERO) {
            throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
        }

        return when (promotion.type) {
            PromotionType.PERCENTAGE_DISCOUNT -> {
                if (promotion.value >= BigDecimal("100.00")) {
                    throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
                }
                val discount = amount.amount
                    .multiply(promotion.value)
                    .divide(BigDecimal("100.00"), 2, RoundingMode.HALF_UP)
                PromotionApplication(
                    promotionId = promotion.id,
                    code = promotion.code,
                    type = promotion.type,
                    amountAfterDiscount = amount.copy(amount = (amount.amount - discount).max(BigDecimal.ZERO)),
                    periodEnd = periodEnd,
                    discountPercentage = promotion.value,
                )
            }
            PromotionType.FIXED_DISCOUNT -> {
                if (promotion.value >= amount.amount) {
                    throw PromotionException("promotion_free_checkout_unsupported", "Promotion cannot reduce checkout to zero.")
                }
                PromotionApplication(
                    promotionId = promotion.id,
                    code = promotion.code,
                    type = promotion.type,
                    amountAfterDiscount = amount.copy(amount = amount.amount - promotion.value),
                    periodEnd = periodEnd,
                )
            }
            PromotionType.FREE_DAYS,
            PromotionType.TRIAL_EXTENSION,
            -> PromotionApplication(
                promotionId = promotion.id,
                code = promotion.code,
                type = promotion.type,
                amountAfterDiscount = amount,
                periodEnd = periodEnd.plus(promotion.value.toLong(), ChronoUnit.DAYS),
            )
            PromotionType.EARLY_SUPPORTER_ACCESS -> throw PromotionException(
                "promotion_unsupported_type",
                "Promotion code is not supported for checkout.",
            )
        }
    }
}

data class PromotionApplication(
    val promotionId: Long?,
    val code: String?,
    val type: PromotionType?,
    val amountAfterDiscount: Money,
    val periodEnd: Instant,
    val discountPercentage: BigDecimal? = null,
) {
    companion object {
        fun none(amount: Money, periodEnd: Instant) = PromotionApplication(
            promotionId = null,
            code = null,
            type = null,
            amountAfterDiscount = amount,
            periodEnd = periodEnd,
            discountPercentage = null,
        )
    }
}
