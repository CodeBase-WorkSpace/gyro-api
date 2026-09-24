package com.gyro.api.common.observability

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.observability")
data class ObservabilityProperties(
    val metricsToken: String,
)
