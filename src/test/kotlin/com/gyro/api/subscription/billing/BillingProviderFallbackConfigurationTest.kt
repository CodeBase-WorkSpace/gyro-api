package com.gyro.api.subscription.billing

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class BillingProviderFallbackConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(DisabledProviderTestConfiguration::class.java)
        .withPropertyValues("spring.profiles.active=prod")

    @Test
    fun `prod context has disabled billing provider when PayPing is disabled`() {
        contextRunner.run { context ->
            val provider = context.getBean(BillingProvider::class.java)
            assertNotNull(provider)
            assertIs<DisabledBillingProvider>(provider)
        }
    }
}

@Configuration
@Import(DisabledBillingProvider::class)
private class DisabledProviderTestConfiguration
