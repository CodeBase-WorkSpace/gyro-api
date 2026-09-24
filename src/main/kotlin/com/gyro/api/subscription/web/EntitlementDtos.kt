package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.trial.TrialStatus
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementSource
import com.gyro.api.subscription.domain.EntitlementStatus
import java.time.Instant

data class TrialResponse(
    val active: Boolean,
    val eligible: Boolean,
    val expiresAt: Instant?,
)

data class EntitlementResponse(
    val planKey: String,
    val status: EntitlementStatus,
    val features: Set<String>,
    val currentPeriodEnd: Instant?,
    val gracePeriodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val supportReasonCode: String?,
    val source: EntitlementSource,
    val premiumGatingDisabled: Boolean = false,
    val trial: TrialResponse? = null,
)

fun Entitlement.toResponse(trial: TrialStatus? = null): EntitlementResponse {
    return EntitlementResponse(
        planKey = planKey,
        status = status,
        features = features,
        currentPeriodEnd = currentPeriodEnd,
        gracePeriodEnd = gracePeriodEnd,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        supportReasonCode = supportReasonCode,
        source = source,
        premiumGatingDisabled = premiumGatingDisabled,
        trial = trial?.let {
            TrialResponse(active = it.active, eligible = it.eligible, expiresAt = it.expiresAt)
        },
    )
}
