package com.gyro.api.subscription.application

import com.gyro.api.common.ratelimit.RateLimitService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

@Service
class PromotionRateLimitService(
    private val rateLimitService: RateLimitService,
    @Value("\${app.rate-limit.promotion.validate-per-ip-limit}") private val validatePerIp: Long,
    @Value("\${app.rate-limit.promotion.validate-per-user-limit}") private val validatePerUser: Long,
    @Value("\${app.rate-limit.promotion.redeem-per-ip-limit}") private val redeemPerIp: Long,
    @Value("\${app.rate-limit.promotion.redeem-per-user-limit}") private val redeemPerUser: Long,
    @Value("\${app.rate-limit.promotion.window}") private val window: Duration,
) {
    fun checkValidate(userId: UUID, ip: String) = check("validate", userId, ip, validatePerIp, validatePerUser)
    fun checkRedeem(userId: UUID, ip: String) = check("redeem", userId, ip, redeemPerIp, redeemPerUser)
    private fun check(action: String, userId: UUID, ip: String, ipLimit: Long, userLimit: Long) {
        rateLimitService.check("rate:promotion:$action:ip:${ip.hash()}", ipLimit, window)
        rateLimitService.check("rate:promotion:$action:user:${userId.toString().hash()}", userLimit, window)
    }
    private fun String.hash(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(toByteArray()))
}
