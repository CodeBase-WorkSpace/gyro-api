package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.trial.TrialService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}")
class EntitlementController(
    private val entitlementGateService: EntitlementGateService,
    private val trialService: TrialService,
) {
    @GetMapping("/billing/me/entitlement")
    fun currentEntitlement(
        @AuthenticationPrincipal userId: String,
    ): EntitlementResponse {
        val id = UUID.fromString(userId)
        val entitlement = entitlementGateService.entitlementFor(id)
        return entitlement.toResponse(
            trial = trialService.statusFor(id, entitlement),
        )
    }

    @GetMapping("/admin/users/{userId}/entitlement")
    fun userEntitlement(
        @PathVariable userId: UUID,
    ): EntitlementResponse {
        val entitlement = entitlementGateService.entitlementFor(userId)
        return entitlement.toResponse(
            trial = trialService.statusFor(userId, entitlement),
        )
    }
}
