package com.gyro.api.subscription.application

import com.gyro.api.auth.domain.UserStatus
import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.PromotionException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.*

data class AffiliateCreateCommand(val code: String, val displayName: String, val discountPercentage: BigDecimal = BigDecimal("15.00"), val commissionPercentage: BigDecimal = BigDecimal("10.00"), val applicablePlanId: Long?, val applicablePriceId: Long?, val startsAt: Instant, val endsAt: Instant?, val maxRedemptions: Int?, val internalNotes: String?)
data class AffiliateUpdateCommand(val displayName: String, val commissionPercentage: BigDecimal, val discountPercentage: BigDecimal, val applicablePlanId: Long?, val applicablePriceId: Long?, val startsAt: Instant, val endsAt: Instant?, val maxRedemptions: Int?, val internalNotes: String?, val expectedVersion: Long)
data class AffiliateAggregate(val successfulCustomers: Long, val customerPaidAmount: BigDecimal, val earnedAmount: BigDecimal)

@Service
class AffiliateService(
    private val affiliates: AffiliateRepository,
    private val commissions: AffiliateCommissionRepository,
    private val promotions: PromotionRepository,
    private val users: UserRepository,
    private val metrics: AffiliateMetrics,
    private val plans: SubscriptionPlanRepository,
    private val prices: SubscriptionPriceRepository,
) {
    @Transactional(readOnly = true)
    fun list(query: String?, status: AffiliateStatus?, page: Int, size: Int): Page<Affiliate> {
        var spec: Specification<Affiliate> = Specification { _, _, _ -> null }
        query?.trim()?.takeIf(String::isNotEmpty)?.let { q ->
            spec = spec.and { root, _, cb -> cb.like(cb.lower(root.get("displayName")), "%${q.lowercase()}%") }
        }
        status?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<AffiliateStatus>("status"), value) } }
        return affiliates.findAll(spec, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))
    }

    @Transactional(readOnly = true) fun detail(id: UUID): Affiliate = affiliates.findById(id).orElseThrow { ResourceNotFoundException("Affiliate") }

    @Transactional
    fun create(command: AffiliateCreateCommand): Affiliate {
        validateRates(command.discountPercentage, command.commissionPercentage)
        validateTarget(command.applicablePlanId, command.applicablePriceId, command.startsAt, command.endsAt)
        val normalizedCode = command.code.trim().uppercase()
        if (!normalizedCode.matches(AFFILIATE_CODE_PATTERN)) {
            throw PromotionException(
                "affiliate_invalid_code",
                "Affiliate code must contain only English letters and numbers."
            )
        }
        return try {
            val promotion = promotions.saveAndFlush(
                Promotion(
                    code = normalizedCode,
                    type = PromotionType.PERCENTAGE_DISCOUNT,
                    value = command.discountPercentage,
                    applicablePlanId = command.applicablePlanId,
                    applicableSubscriptionPriceId = command.applicablePriceId,
                    startsAt = command.startsAt,
                    endsAt = command.endsAt,
                    maxRedemptions = command.maxRedemptions,
                    perUserRedemptionLimit = 1,
                    active = true,
                    internalNotes = "Managed by affiliate: ${command.internalNotes.orEmpty()}"
                )
            )
            affiliates.saveAndFlush(Affiliate(displayName = command.displayName.trim(), promotionId = promotion.id!!, commissionPercentage = command.commissionPercentage, internalNotes = command.internalNotes))
        } catch (_: DataIntegrityViolationException) {
            throw PromotionException("promotion_code_conflict", "Promotion code or affiliate assignment already exists.")
        }
    }

    @Transactional
    fun update(id: UUID, command: AffiliateUpdateCommand): Affiliate {
        validateRates(command.discountPercentage, command.commissionPercentage)
        validateTarget(command.applicablePlanId, command.applicablePriceId, command.startsAt, command.endsAt)
        val current = detail(id)
        if (current.version != command.expectedVersion) throw PromotionException("affiliate_version_conflict", "Affiliate was changed by another administrator.")
        val promotion = promotions.findById(current.promotionId).orElseThrow()
        val now = Instant.now()
        promotion.value = command.discountPercentage
        promotion.applicablePlanId = command.applicablePlanId
        promotion.applicableSubscriptionPriceId = command.applicablePriceId
        promotion.startsAt = command.startsAt
        promotion.endsAt = command.endsAt
        promotion.maxRedemptions = command.maxRedemptions
        promotion.internalNotes = "Managed by affiliate: ${command.internalNotes.orEmpty()}"
        promotion.updatedAt = now
        promotions.save(promotion)
        current.displayName = command.displayName.trim()
        current.commissionPercentage = command.commissionPercentage
        current.internalNotes = command.internalNotes
        current.updatedAt = now
        return affiliates.save(current)
    }

    @Transactional fun setActive(id: UUID, active: Boolean): Affiliate {
        val current = detail(id)
        current.status = if (active) AffiliateStatus.ACTIVE else AffiliateStatus.INACTIVE
        current.updatedAt = Instant.now()
        return affiliates.save(current)
    }

    @Transactional fun assignAccount(id: UUID, userId: UUID?): Affiliate {
        val current = detail(id)
        if (userId == null) {
            current.linkedUserId = null
            current.updatedAt = Instant.now()
            return affiliates.save(current).also { metrics.accountLink("remove", "success") }
        }
        val user = users.findById(userId).orElseThrow { ResourceNotFoundException("User") }
        if (user.status != UserStatus.ACTIVE || (user.emailVerificationStatus != VerificationStatus.VERIFIED && user.phoneVerificationStatus != VerificationStatus.VERIFIED)) {
            metrics.accountLink("assign", "rejected")
            throw PromotionException("affiliate_account_unverified", "Affiliate account must be active and verified.")
        }
        affiliates.findByLinkedUserId(userId).orElse(null)?.takeIf { it.id != id }?.let {
            metrics.accountLink("assign", "duplicate")
            throw PromotionException("affiliate_duplicate_account_assignment", "This account is already linked to an affiliate.")
        }
        current.linkedUserId = userId
        current.updatedAt = Instant.now()
        return affiliates.save(current).also { metrics.accountLink("assign", "success") }
    }

    @Transactional(readOnly = true) fun aggregate(id: UUID, from: Instant? = null, to: Instant? = null): AffiliateAggregate {
        val rows = if (from != null || to != null) commissions.findByAffiliateIdAndEarnedAtBetweenOrderByEarnedAtAsc(id, from ?: Instant.EPOCH, to ?: Instant.MAX) else commissions.findByAffiliateId(id)
        val earned = rows.filter { it.status == AffiliateCommissionStatus.EARNED }
        return AffiliateAggregate(earned.size.toLong(), earned.fold(BigDecimal.ZERO) { a, r -> a + r.customerPaidAmount }, earned.fold(BigDecimal.ZERO) { a, r -> a + r.earningAmount })
    }

    @Transactional(readOnly = true) fun forUser(userId: UUID): Affiliate = affiliates.findByLinkedUserId(userId).orElseThrow { PromotionException("affiliate_dashboard_unlinked", "No active affiliate dashboard is linked to this account.") }.also { if (it.status != AffiliateStatus.ACTIVE) throw PromotionException("affiliate_dashboard_unlinked", "No active affiliate dashboard is linked to this account.") }
    @Transactional(readOnly = true) fun isDashboardAvailable(userId: UUID): Boolean = affiliates.findByLinkedUserId(userId).orElse(null)?.status == AffiliateStatus.ACTIVE
    @Transactional(readOnly = true) fun promotion(affiliate: Affiliate): Promotion = promotions.findById(affiliate.promotionId).orElseThrow()
    @Transactional(readOnly = true) fun earnings(affiliate: Affiliate, from: Instant, to: Instant) = commissions.findByAffiliateIdAndEarnedAtBetweenOrderByEarnedAtAsc(affiliate.id!!, from, to).filter { it.status == AffiliateCommissionStatus.EARNED }

    private fun validateRates(discount: BigDecimal, commission: BigDecimal) {
        if (discount <= BigDecimal.ZERO || discount >= BigDecimal("100") || commission <= BigDecimal.ZERO || commission >= BigDecimal("100")) throw PromotionException("affiliate_invalid_rate", "Affiliate rates must be between zero and one hundred.")
    }

    private fun validateTarget(planId: Long?, priceId: Long?, startsAt: Instant, endsAt: Instant?) {
        if (endsAt != null && !endsAt.isAfter(startsAt)) throw PromotionException("promotion_invalid_window", "Promotion end must be after start.")
        planId?.let { if (!plans.existsByIdAndActiveTrue(it)) throw PromotionException("promotion_plan_mismatch", "Affiliate plan is not active.") }
        priceId?.let { id -> val price = prices.findById(id).orElseThrow { PromotionException("promotion_price_mismatch", "Affiliate price is not active.") }; if (!price.active || price.planId != planId) throw PromotionException("promotion_price_mismatch", "Affiliate price is not active for the selected plan.") }
    }

    private companion object {
        val AFFILIATE_CODE_PATTERN = Regex("^[A-Z0-9]{1,64}$")
    }
}
