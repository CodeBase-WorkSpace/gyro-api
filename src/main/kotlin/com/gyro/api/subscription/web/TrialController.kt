package com.gyro.api.subscription.web

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.trial.TrialService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class TrialClaimResponse(
    val expiresAt: Instant,
    val entitlement: EntitlementResponse,
)

@RestController
@RequestMapping("\${app.api.base-path}")
class TrialController(
    private val trialService: TrialService,
    private val userRepository: UserRepository,
    private val entitlementGateService: EntitlementGateService,
) {
    @PostMapping("/billing/me/trial/claim")
    fun claimTrial(
        @AuthenticationPrincipal userId: String,
    ): TrialClaimResponse {
        val id = UUID.fromString(userId)
        val user = userRepository.findById(id).orElse(null)
        val redemption = trialService.claimTrial(id, user?.email ?: user?.phoneNumber)
        val entitlement = entitlementGateService.entitlementFor(id)
        return TrialClaimResponse(
            expiresAt = redemption.expiresAt,
            entitlement = entitlement.toResponse(
                trial = trialService.statusFor(id, entitlement),
            ),
        )
    }
}
