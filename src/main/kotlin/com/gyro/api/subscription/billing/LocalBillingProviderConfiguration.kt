package com.gyro.api.subscription.billing

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("!prod")
class LocalBillingProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(BillingProvider::class)
    fun billingProvider(): BillingProvider = FakeBillingProvider()
}
