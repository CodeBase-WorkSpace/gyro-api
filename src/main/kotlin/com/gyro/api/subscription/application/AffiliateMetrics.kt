package com.gyro.api.subscription.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class AffiliateMetrics(private val registries: ObjectProvider<MeterRegistry>) {
    fun validation(outcome: String, reason: String) = increment("gyro.affiliate.validation", "outcome", outcome, "reason", reason)
    fun conversion() = increment("gyro.affiliate.conversions", "outcome", "success")
    fun commission(outcome: String) = increment("gyro.affiliate.commissions", "outcome", outcome)
    fun accountLink(action: String, outcome: String) = increment("gyro.affiliate.account_links", "action", action, "outcome", outcome)

    private fun increment(name: String, vararg tags: String) {
        registries.ifAvailable { Counter.builder(name).tags(*tags).register(it).increment() }
    }
}
