package com.gyro.api.subscription.application

import com.gyro.api.common.error.PromotionException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.subscription.domain.Promotion
import com.gyro.api.subscription.domain.PromotionType
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import com.gyro.api.subscription.infrastructure.PromotionRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPriceRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

data class PromotionCommand(val code: String, val type: PromotionType, val value: BigDecimal, val applicablePlanId: Long?, val applicableSubscriptionPriceId: Long?, val startsAt: Instant, val endsAt: Instant?, val maxRedemptions: Int?, val perUserRedemptionLimit: Int, val active: Boolean, val internalNotes: String?, val expectedVersion: Long? = null)

@Service
class AdminPromotionService(
    private val promotions: PromotionRepository,
    private val redemptions: PromotionRedemptionRepository,
    private val plans: SubscriptionPlanRepository,
    private val prices: SubscriptionPriceRepository,
    private val metrics: PromotionMetrics,
) {
    @Transactional(readOnly = true)
    fun list(query: String?, active: Boolean?, type: PromotionType?, page: Int, size: Int): Page<Promotion> {
        var spec: Specification<Promotion> = Specification { _, _, _ -> null }
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { value -> spec = spec.and { root, _, cb -> cb.like(cb.upper(root.get("code")), "%${value.uppercase()}%") } }
        active?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<Boolean>("active"), value) } }
        type?.let { value -> spec = spec.and { root, _, cb -> cb.equal(root.get<PromotionType>("type"), value) } }
        return promotions.findAll(spec, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))
    }
    @Transactional(readOnly = true) fun detail(id: Long): Promotion = promotions.findById(id).orElseThrow { ResourceNotFoundException("Promotion") }
    @Transactional fun create(command: PromotionCommand): Promotion = save(command) { Promotion(code = normalized(command.code), type = command.type, value = command.value, applicablePlanId = command.applicablePlanId, applicableSubscriptionPriceId = command.applicableSubscriptionPriceId, startsAt = command.startsAt, endsAt = command.endsAt, maxRedemptions = command.maxRedemptions, perUserRedemptionLimit = command.perUserRedemptionLimit, active = command.active, internalNotes = command.internalNotes) }.also { metrics.adminMutation("create") }
    @Transactional fun update(id: Long, command: PromotionCommand): Promotion {
        val existing = detail(id)
        if (command.expectedVersion != existing.version) throw PromotionException("promotion_version_conflict", "Promotion was changed by another administrator.")
        if (normalized(command.code) != existing.code || command.type != existing.type) throw PromotionException("promotion_immutable_field", "Promotion code and type cannot be changed.")
        return save(command) {
            existing.apply {
                value = command.value
                applicablePlanId = command.applicablePlanId
                applicableSubscriptionPriceId = command.applicableSubscriptionPriceId
                startsAt = command.startsAt
                endsAt = command.endsAt
                maxRedemptions = command.maxRedemptions
                perUserRedemptionLimit = command.perUserRedemptionLimit
                active = command.active
                internalNotes = command.internalNotes
                updatedAt = Instant.now()
            }
        }.also { metrics.adminMutation("update") }
    }
    @Transactional fun archive(id: Long, expectedVersion: Long): Promotion {
        val existing = detail(id)
        if (expectedVersion != existing.version) throw PromotionException("promotion_version_conflict", "Promotion was changed by another administrator.")
        existing.active = false
        existing.updatedAt = Instant.now()
        return promotions.save(existing).also { metrics.adminMutation("archive") }
    }
    fun redemptionCounts(id: Long) = mapOf("redeemed" to redemptions.countByPromotionIdAndStatus(id, com.gyro.api.subscription.domain.PromotionRedemptionStatus.REDEEMED), "reserved" to redemptions.countByPromotionIdAndStatus(id, com.gyro.api.subscription.domain.PromotionRedemptionStatus.RESERVED), "released" to redemptions.countByPromotionIdAndStatus(id, com.gyro.api.subscription.domain.PromotionRedemptionStatus.RELEASED))
    fun redemptionCounts(ids: Collection<Long>): Map<Long, Map<String, Long>> = redemptions
        .countStatusesByPromotionIds(ids)
        .groupBy { it.getPromotionId() }
        .mapValues { (_, counts) -> counts.associate { it.getStatus().name.lowercase() to it.getCount() } }
    private fun save(command: PromotionCommand, build: () -> Promotion): Promotion {
        validate(command)
        try { return promotions.save(build()) } catch (_: DataIntegrityViolationException) { throw PromotionException("promotion_code_conflict", "Promotion code already exists.") }
    }
    private fun validate(command: PromotionCommand) {
        if (normalized(command.code).isBlank() || command.value <= BigDecimal.ZERO || command.perUserRedemptionLimit < 1 || command.maxRedemptions?.let { it < 1 } == true) throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
        if (command.type == PromotionType.PERCENTAGE_DISCOUNT && command.value >= BigDecimal(100)) throw PromotionException("promotion_invalid_value", "Promotion configuration is invalid.")
        if (command.endsAt != null && !command.endsAt.isAfter(command.startsAt)) throw PromotionException("promotion_invalid_window", "Promotion end must be after start.")
        command.applicablePlanId?.let { if (!plans.existsByIdAndActiveTrue(it)) throw PromotionException("promotion_plan_mismatch", "Promotion plan is not active.") }
        command.applicableSubscriptionPriceId?.let { priceId ->
            val price = prices.findById(priceId).orElseThrow { PromotionException("promotion_price_mismatch", "Promotion price is not active.") }
            if (!price.active || command.applicablePlanId != price.planId) throw PromotionException("promotion_price_mismatch", "Promotion price is not active for the selected plan.")
        }
        if (command.type == PromotionType.EARLY_SUPPORTER_ACCESS && (command.applicablePlanId == null || command.value.stripTrailingZeros().scale() > 0)) throw PromotionException("promotion_invalid_value", "Early supporter promotions require a plan and whole-number days.")
    }
    private fun normalized(value: String) = value.trim().uppercase()
}
