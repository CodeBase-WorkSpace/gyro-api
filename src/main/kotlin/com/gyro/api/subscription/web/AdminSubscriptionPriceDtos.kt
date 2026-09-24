package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.CreateSubscriptionPriceCommand
import com.gyro.api.subscription.application.PriceImpact
import com.gyro.api.subscription.application.PriceMutationResult
import com.gyro.api.subscription.domain.SubscriptionPlan
import com.gyro.api.subscription.domain.SubscriptionPrice
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import java.math.BigDecimal
import java.time.Instant

data class CreateSubscriptionPriceRequest(@field:NotNull val planId: Long?, @field:NotNull val billingPeriodDays: Int?, @field:NotNull @field:DecimalMin("0.00") val baseAmount: BigDecimal?, @field:DecimalMin("0.00") @field:DecimalMax("99.99") val discountPercent: BigDecimal = BigDecimal.ZERO, @field:NotBlank val currency: String, val badge: String? = null, @field:NotNull val validFrom: Instant?, val internalNotes: String? = null, val expectedCurrentPriceId: Long? = null) {
    fun command() = CreateSubscriptionPriceCommand(requireNotNull(planId), requireNotNull(billingPeriodDays), requireNotNull(baseAmount), discountPercent, currency, badge, requireNotNull(validFrom), internalNotes, expectedCurrentPriceId)
}
data class DeactivateSubscriptionPriceRequest(@field:NotBlank val reason: String)
data class AdminSubscriptionPriceResponse(val id: Long, val planId: Long, val billingPeriodDays: Int, val baseAmount: BigDecimal, val discountPercent: BigDecimal, val amount: BigDecimal, val currency: String, val badge: String?, val active: Boolean, val scheduled: Boolean, val validFrom: Instant, val validUntil: Instant?, val createdBy: String?, val deactivatedBy: String?, val deactivationReason: String?, val internalNotes: String?, val expectedPredecessorId: Long?, val activationConflictedAt: Instant?, val activationConflictReason: String?, val version: Long) {
    companion object { fun from(p: SubscriptionPrice) = AdminSubscriptionPriceResponse(requireNotNull(p.id), p.planId, p.billingPeriodDays, p.baseAmount, p.discountPercent, p.price.amount, p.price.currency, p.badge, p.active, p.scheduled, p.validFrom, p.validUntil, p.createdBy?.toString(), p.deactivatedBy?.toString(), p.deactivationReason, p.internalNotes, p.expectedPredecessorId, p.activationConflictedAt, p.activationConflictReason, p.version) }
}
data class AdminPlanPricesResponse(val id: Long, val code: String, val name: String, val free: Boolean, val active: Boolean, val prices: List<AdminSubscriptionPriceResponse>) {
    companion object { fun from(plan: SubscriptionPlan, prices: List<SubscriptionPrice>) = AdminPlanPricesResponse(requireNotNull(plan.id), plan.code, plan.name, plan.free, plan.active, prices.map(AdminSubscriptionPriceResponse::from)) }
}
data class AdminSubscriptionPricePageResponse(val items: List<AdminSubscriptionPriceResponse>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int)
data class PriceMutationResponse(val created: AdminSubscriptionPriceResponse, val retired: AdminSubscriptionPriceResponse?) { companion object { fun from(r: PriceMutationResult) = PriceMutationResponse(AdminSubscriptionPriceResponse.from(r.created), r.retired?.let(AdminSubscriptionPriceResponse::from)) } }
data class PriceImpactResponse(val activeSubscriptions: Long, val openInvoices: Long, val pendingPaymentAttempts: Long, val activePromotions: Long) { companion object { fun from(i: PriceImpact) = PriceImpactResponse(i.activeSubscriptions, i.openInvoices, i.pendingPaymentAttempts, i.activePromotions) } }
