package com.gyro.api.subscription.application

import com.gyro.api.common.error.PromotionException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.AffiliateCommissionRepository
import com.gyro.api.subscription.infrastructure.AffiliateRepository
import com.gyro.api.subscription.infrastructure.InvoiceRepository
import com.gyro.api.subscription.infrastructure.PromotionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

@Service
class AffiliateCommissionService(
    private val affiliates: AffiliateRepository,
    private val commissions: AffiliateCommissionRepository,
    private val invoices: InvoiceRepository,
    private val promotions: PromotionRepository,
    private val timeProvider: TimeProvider,
    private val metrics: AffiliateMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validateEligibility(promotionId: Long, userId: UUID) {
        val affiliate = affiliates.findByPromotionId(promotionId).orElse(null) ?: return
        if (affiliate.status != AffiliateStatus.ACTIVE) reject("affiliate_inactive", "Affiliate code is inactive.")
        if (affiliate.linkedUserId == userId) reject("affiliate_self_referral", "Affiliate accounts cannot use their own code.")
        if (invoices.countByUserIdAndStatus(userId, InvoiceStatus.PAID) > 0 || commissions.existsByReferredUserId(userId)) {
            reject("affiliate_existing_paying_customer", "Affiliate codes are available only for a first paid invoice.")
        }
        metrics.validation("accepted", "none")
    }

    fun commissionRateFor(promotionId: Long): BigDecimal? = affiliates.findByPromotionId(promotionId).orElse(null)?.commissionPercentage

    /** Called only after the invoice is PAID and its reservation has become a redemption. */
    @Transactional
    fun createForPaidInvoice(invoice: Invoice, redemption: PromotionRedemption?): AffiliateCommission? {
        if (invoice.manual || redemption == null || redemption.status != PromotionRedemptionStatus.REDEEMED) return null
        commissions.findByInvoiceId(invoice.id!!).orElse(null)?.let {
            metrics.commission("duplicate_suppressed")
            return it
        }
        val affiliate = affiliates.findByPromotionId(redemption.promotionId).orElse(null) ?: return null
        if (invoices.countByUserIdAndStatus(invoice.userId, InvoiceStatus.PAID) != 1L) return null
        if (affiliate.linkedUserId == invoice.userId) return null
        val promotion = promotions.findById(redemption.promotionId).orElseThrow()
        val commissionRate = redemption.commissionPercentageSnapshot ?: affiliate.commissionPercentage
        val earning = invoice.amountAfterDiscount.amount
            .multiply(commissionRate)
            .divide(BigDecimal("100.00"), 2, RoundingMode.HALF_UP)
        val commission = AffiliateCommission(
            affiliateId = affiliate.id!!,
            referredUserId = invoice.userId,
            invoiceId = invoice.id!!,
            promotionRedemptionId = redemption.id!!,
            currency = invoice.amountAfterDiscount.currency,
            originalInvoiceAmount = invoice.amountDue.amount,
            customerPaidAmount = invoice.amountAfterDiscount.amount,
            discountPercentageSnapshot = redemption.discountPercentageSnapshot ?: promotion.value,
            commissionPercentageSnapshot = commissionRate,
            earningAmount = earning,
            earnedAt = timeProvider.now(),
        )
        return commissions.saveAndFlush(commission).also {
            metrics.commission("created")
            metrics.conversion()
            log.info(
                "event=affiliate_commission_created affiliate_id={} invoice_id={} currency={} amount={}",
                affiliate.id,
                invoice.id,
                it.currency,
                it.earningAmount
            )
        }
    }

    private fun reject(code: String, message: String): Nothing {
        metrics.validation("rejected", code)
        throw PromotionException(code, message)
    }
}
