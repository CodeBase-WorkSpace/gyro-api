package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.PromotionCommand
import com.gyro.api.subscription.domain.Promotion
import com.gyro.api.subscription.domain.PromotionType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant

data class AdminPromotionRequest(@field:NotBlank val code: String, @field:NotNull val type: PromotionType?, @field:Positive val value: BigDecimal?, @field:NotNull val startsAt: Instant?, val applicablePlanId: Long? = null, val applicableSubscriptionPriceId: Long? = null, val endsAt: Instant? = null, val maxRedemptions: Int? = null, val perUserRedemptionLimit: Int = 1, val active: Boolean = true, val internalNotes: String? = null, val expectedVersion: Long? = null) { fun command() = PromotionCommand(code, requireNotNull(type), requireNotNull(value), applicablePlanId, applicableSubscriptionPriceId, requireNotNull(startsAt), endsAt, maxRedemptions, perUserRedemptionLimit, active, internalNotes, expectedVersion) }
data class AdminPromotionResponse(val id: Long, val code: String, val type: PromotionType, val value: BigDecimal, val applicablePlanId: Long?, val applicableSubscriptionPriceId: Long?, val startsAt: Instant, val endsAt: Instant?, val maxRedemptions: Int?, val perUserRedemptionLimit: Int, val active: Boolean, val internalNotes: String?, val version: Long, val counts: Map<String, Long>? = null) { companion object { fun from(p: Promotion, counts: Map<String, Long>? = null) = AdminPromotionResponse(requireNotNull(p.id), p.code, p.type, p.value, p.applicablePlanId, p.applicableSubscriptionPriceId, p.startsAt, p.endsAt, p.maxRedemptions, p.perUserRedemptionLimit, p.active, p.internalNotes, p.version, counts) } }
data class AdminPromotionPageResponse(val items: List<AdminPromotionResponse>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int)
data class ArchivePromotionRequest(val expectedVersion: Long)
