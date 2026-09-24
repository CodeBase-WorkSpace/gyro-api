package com.gyro.api.subscription.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.premium-gating")
data class PremiumGatingProperties(
    val disabled: Boolean = false,
    val freeFutureDiaryDays: Int = 3,
) {
    init {
        require(freeFutureDiaryDays >= 0) { "freeFutureDiaryDays must not be negative." }
    }
}
