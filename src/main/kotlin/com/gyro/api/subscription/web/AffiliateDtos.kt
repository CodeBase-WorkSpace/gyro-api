package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.*
import com.gyro.api.subscription.domain.*
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AffiliateCreateRequest(@field:NotBlank @field:Size(max = 64) val code: String, @field:NotBlank @field:Size(max = 160) val displayName: String, @field:DecimalMin("0.01") @field:DecimalMax("99.99") val discountPercentage: BigDecimal = BigDecimal("15.00"), @field:DecimalMin("0.01") @field:DecimalMax("99.99") val commissionPercentage: BigDecimal = BigDecimal("10.00"), val applicablePlanId: Long? = null, val applicablePriceId: Long? = null, val startsAt: Instant = Instant.now(), val endsAt: Instant? = null, @field:Positive val maxRedemptions: Int? = null, @field:Size(max = 4000) val internalNotes: String? = null) {
    fun command() = AffiliateCreateCommand(code, displayName, discountPercentage, commissionPercentage, applicablePlanId, applicablePriceId, startsAt, endsAt, maxRedemptions, internalNotes)
}
data class AffiliateUpdateRequest(@field:NotBlank @field:Size(max = 160) val displayName: String, @field:DecimalMin("0.01") @field:DecimalMax("99.99") val discountPercentage: BigDecimal, @field:DecimalMin("0.01") @field:DecimalMax("99.99") val commissionPercentage: BigDecimal, val applicablePlanId: Long? = null, val applicablePriceId: Long? = null, val startsAt: Instant, val endsAt: Instant? = null, @field:Positive val maxRedemptions: Int? = null, @field:Size(max = 4000) val internalNotes: String? = null, val expectedVersion: Long) {
    fun command() = AffiliateUpdateCommand(displayName, commissionPercentage, discountPercentage, applicablePlanId, applicablePriceId, startsAt, endsAt, maxRedemptions, internalNotes, expectedVersion)
}
data class AffiliateAccountRequest(val userId: UUID?)
data class AffiliateResponse(val id: UUID, val displayName: String, val status: AffiliateStatus, val code: String, val discountPercentage: BigDecimal, val commissionPercentage: BigDecimal, val linkedUserId: UUID?, val applicablePlanId: Long?, val applicablePriceId: Long?, val startsAt: Instant, val endsAt: Instant?, val maxRedemptions: Int?, val internalNotes: String?, val version: Long, val successfulCustomers: Long, val customerPaidAmount: BigDecimal, val earnedAmount: BigDecimal) {
    companion object { fun from(a: Affiliate, p: Promotion, totals: AffiliateAggregate) = AffiliateResponse(a.id!!, a.displayName, a.status, p.code, p.value, a.commissionPercentage, a.linkedUserId, p.applicablePlanId, p.applicableSubscriptionPriceId, p.startsAt, p.endsAt, p.maxRedemptions, a.internalNotes, a.version, totals.successfulCustomers, totals.customerPaidAmount, totals.earnedAmount) }
}
data class AffiliatePageResponse(val content: List<AffiliateResponse>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Int)
data class AffiliateSummaryResponse(val status: AffiliateStatus, val code: String, val discountPercentage: BigDecimal, val commissionPercentage: BigDecimal, val successfulCustomerCount: Long, val totalCustomerPaidAmount: BigDecimal, val totalEarnedAmount: BigDecimal, val currentMonthCustomerCount: Long, val currentMonthCustomerPaidAmount: BigDecimal, val currentMonthEarnedAmount: BigDecimal, val currency: String = "IRR")
data class AffiliateAvailabilityResponse(val available: Boolean)
data class AffiliateEarningBucketResponse(val period: String, val successfulCustomerCount: Long, val customerPaidAmount: BigDecimal, val earnedAmount: BigDecimal, val currency: String)
data class AffiliateEarningsResponse(val granularity: String, val from: Instant, val to: Instant, val buckets: List<AffiliateEarningBucketResponse>)
