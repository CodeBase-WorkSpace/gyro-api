package com.gyro.api.subscription.application

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SubscriptionPriceManagementMetrics(private val registry: MeterRegistry) {
    fun mutation(action: String, outcome: String) {
        registry.counter("billing.subscription.price.mutations", "action", action, "outcome", outcome).increment()
    }
}
