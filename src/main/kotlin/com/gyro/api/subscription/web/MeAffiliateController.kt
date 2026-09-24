package com.gyro.api.subscription.web

import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.AffiliateService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/me/affiliate")
@PreAuthorize("isAuthenticated()")
class MeAffiliateController(private val service: AffiliateService) {
    @GetMapping("/availability") fun availability(request: HttpServletRequest) = AffiliateAvailabilityResponse(service.isDashboardAvailable(userId(request)))
    @GetMapping("/summary") fun summary(request: HttpServletRequest): AffiliateSummaryResponse { val affiliate = service.forUser(userId(request)); val promotion = service.promotion(affiliate); val affiliateId = affiliate.id!!; val all = service.aggregate(affiliateId); val monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC); val month = service.aggregate(affiliateId, monthStart, Instant.now()); return AffiliateSummaryResponse(affiliate.status, promotion.code, promotion.value, affiliate.commissionPercentage, all.successfulCustomers, all.customerPaidAmount, all.earnedAmount, month.successfulCustomers, month.customerPaidAmount, month.earnedAmount) }
    @GetMapping("/earnings") fun earnings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant, @RequestParam(defaultValue = "daily") granularity: String, request: HttpServletRequest): AffiliateEarningsResponse { require(to.isAfter(from)); val affiliate = service.forUser(userId(request)); val rows = service.earnings(affiliate, from, to); val monthly = granularity == "monthly"; val groups = rows.groupBy { val date = it.earnedAt.atZone(ZoneOffset.UTC).toLocalDate(); if (monthly) date.withDayOfMonth(1).toString().substring(0, 7) else date.toString() }; val buckets = groups.toSortedMap().map { (period, entries) -> AffiliateEarningBucketResponse(period, entries.size.toLong(), entries.fold(BigDecimal.ZERO) { a, e -> a + e.customerPaidAmount }, entries.fold(BigDecimal.ZERO) { a, e -> a + e.earningAmount }, entries.first().currency) }; return AffiliateEarningsResponse(if (monthly) "monthly" else "daily", from, to, buckets) }
    private fun userId(r: HttpServletRequest) = UUID.fromString(r.getAttribute(USER_ID_ATTRIBUTE) as String)
}
