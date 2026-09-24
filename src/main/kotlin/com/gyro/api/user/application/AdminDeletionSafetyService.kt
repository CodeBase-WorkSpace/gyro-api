package com.gyro.api.user.application

import com.gyro.api.common.ratelimit.RateLimitService
import com.gyro.api.common.security.JwtService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*

@Service("adminDeletionPolicy")
class AdminDeletionSafetyService(
    private val rateLimitService: RateLimitService,
    private val jwtService: JwtService,
    @Value("\${app.admin-user-deletion.enabled:false}")
    private val enabled: Boolean,
    @Value("\${app.admin-user-deletion.rate-limit:30}")
    private val rateLimit: Long,
    @Value("\${app.admin-user-deletion.rate-window:1h}")
    private val rateWindow: Duration,
    @Value("\${app.admin-user-deletion.enforce-elevated-controls:true}")
    private val enforceElevatedControls: Boolean,
    @Value("\${app.admin-user-deletion.operator-ids:}")
    private val operatorIds: String,
    @Value("\${app.admin-user-deletion.recent-auth-window:10m}")
    private val recentAuthWindow: Duration,
) {
    fun enabled(): Boolean = enabled

    fun checkRateLimit(adminId: UUID) {
        rateLimitService.check("rate:admin:user-deletion:$adminId", rateLimit, rateWindow)
    }

    fun requireDeletionAuthority(adminId: UUID, authorizationHeader: String?) {
        if (!enforceElevatedControls) return
        val allowlist = operatorIds.split(',').mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }.toSet()
        if (adminId !in allowlist) throw AccessDeniedException("Deletion operator authority is required.")
        val issuedAt = authorizationHeader?.let(jwtService::getAccessTokenIssuedAt)
        if (issuedAt == null || issuedAt.plus(recentAuthWindow).isBefore(Instant.now())) {
            throw AccessDeniedException("Recent administrator authentication is required.")
        }
    }
}
