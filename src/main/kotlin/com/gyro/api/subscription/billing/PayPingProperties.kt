package com.gyro.api.subscription.billing

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.billing.payping")
data class PayPingProperties(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.payping.ir",
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val reportEnabled: Boolean = false,
)
