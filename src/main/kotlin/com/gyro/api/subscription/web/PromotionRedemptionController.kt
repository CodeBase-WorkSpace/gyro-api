package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.InvalidIdempotencyKeyException
import com.gyro.api.subscription.application.PromotionMetrics
import com.gyro.api.subscription.application.PromotionRateLimitService
import com.gyro.api.subscription.application.PromotionService
import com.gyro.api.subscription.infrastructure.PromotionRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/billing/promotions")
class PromotionRedemptionController(
    private val promotionService: PromotionService,
    private val promotionRepository: PromotionRepository,
    private val rateLimitService: PromotionRateLimitService,
    private val metrics: PromotionMetrics,
    private val accountAuditService: AccountAuditService,
) {
    @PostMapping("/redeem")
    fun redeem(@RequestBody request: PromotionRedeemRequest, @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?, @AuthenticationPrincipal principal: String, servletRequest: HttpServletRequest): ResponseEntity<PromotionRedeemResponse> {
        val userId = UUID.fromString(principal)
        val safeIdempotencyKey = idempotencyKey?.trim()?.takeIf { IDEMPOTENCY_KEY_PATTERN.matches(it) }
            ?: throw InvalidIdempotencyKeyException()
        try { rateLimitService.checkRedeem(userId, servletRequest.remoteAddr) } catch (exception: RuntimeException) { metrics.rateLimited("redeem"); throw exception }
        val redemption = promotionService.redeemEarlySupporter(request.promotionCode, userId, safeIdempotencyKey)
        val promotion = promotionRepository.findById(redemption.promotionId).orElseThrow()
        accountAuditService.record(userId, userId, AccountAuditEventType.PROMOTION_SELF_REDEEMED, metadata = mapOf("promotionId" to promotion.id, "grantId" to redemption.manualGrantId))
        return ResponseEntity.ok(PromotionRedeemResponse("REDEEMED", requireNotNull(redemption.manualGrantId), requireNotNull(promotion.applicablePlanId), promotion.value.toInt(), null))
    }
    private companion object {
        val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9._:-]{8,255}$")
    }
}

data class PromotionRedeemRequest(val promotionCode: String)
data class PromotionRedeemResponse(val status: String, val grantId: UUID, val planId: Long, val freeDays: Int, val expiresAt: java.time.Instant?)
