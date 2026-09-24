package com.gyro.api.subscription.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.trial")
data class TrialProperties(
    /** Master switch for self-serve trials (signup grants, claims, reconciliation). */
    val enabled: Boolean = true,
    /** Trial length in days. */
    @field:Min(1)
    @field:Max(90)
    val days: Int = 14,
    /** Plan granted for the trial period. */
    @field:NotBlank
    val planKey: String = "ADVANCED",
    /**
     * Secret pepper for the HMAC over the normalized contact identifier
     * stored in trial_redemptions. Dedicated secret; never persisted.
     * Rotating it changes every stored hash, which breaks re-farming lookups
     * for identifiers redeemed under the old pepper -- rotate only with a
     * migration plan.
     */
    @field:NotBlank
    val identifierPepper: String,
)
