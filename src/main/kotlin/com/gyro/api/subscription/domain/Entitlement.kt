package com.gyro.api.subscription.domain

import java.time.Instant

data class Entitlement(
    val status: EntitlementStatus,
    val planKey: String,
    val features: Set<String>,
    val currentPeriodEnd: Instant?,
    val gracePeriodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val supportReasonCode: String?,
    val source: EntitlementSource = EntitlementSource.FREE,
    val premiumGatingDisabled: Boolean = false,
)

enum class EntitlementStatus {
    FREE,
    ACTIVE,
    GRACE_PERIOD,
    EXPIRED,
    CANCELED,
    BILLED_BLOCKED,
    ADMIN_OVERRIDE,
}

enum class EntitlementSource {
    FREE,
    SUBSCRIPTION,
    MANUAL_GRANT,
}
