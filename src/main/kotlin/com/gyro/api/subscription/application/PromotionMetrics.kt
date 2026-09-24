package com.gyro.api.subscription.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class PromotionMetrics(private val meterRegistryProvider: ObjectProvider<MeterRegistry>) {
    fun validation(outcome: String, reason: String, flow: String) = increment("gyro.promotion.validation.outcomes", "outcome", outcome, "reason", reason, "flow", flow)
    fun redemption(outcome: String, flow: String) = increment("gyro.promotion.redemption.outcomes", "outcome", outcome, "flow", flow)
    fun limit(scope: String) = increment("gyro.promotion.limit.hits", "scope", scope)
    fun rateLimited(endpoint: String) = increment("gyro.promotion.validation.rate_limited", "endpoint", endpoint)
    fun adminMutation(action: String) = increment("gyro.promotion.admin.mutations", "action", action)

    private fun increment(name: String, vararg tags: String) {
        meterRegistryProvider.ifAvailable { registry -> Counter.builder(name).tags(*tags).register(registry).increment() }
    }
}
