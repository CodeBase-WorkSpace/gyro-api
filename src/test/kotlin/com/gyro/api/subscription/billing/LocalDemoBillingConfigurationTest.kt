package com.gyro.api.subscription.billing

import com.gyro.api.subscription.web.LocalDemoBillingController
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LocalDemoBillingConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(LocalDemoBillingConfiguration::class.java)
        .withPropertyValues("app.billing.demo.enabled=true", "app.billing.payping.enabled=false")

    @Test
    fun `demo provider exists only in the dev profile with explicit opt in`() {
        contextRunner.withPropertyValues("spring.profiles.active=dev").run { context ->
            assertTrue(context.containsBean("localDemoBillingProvider"))
        }
        contextRunner.withPropertyValues("spring.profiles.active=prod").run { context ->
            assertFalse(context.containsBean("localDemoBillingProvider"))
        }
        contextRunner.withPropertyValues("spring.profiles.active=dev,prod").run { context ->
            assertFalse(context.containsBean("localDemoBillingProvider"))
        }
        contextRunner.withPropertyValues("spring.profiles.active=dev", "app.billing.demo.enabled=false")
            .run { context -> assertFalse(context.containsBean("localDemoBillingProvider")) }
        contextRunner.withPropertyValues("spring.profiles.active=dev", "app.billing.payping.enabled=true")
            .run { context -> assertFalse(context.containsBean("localDemoBillingProvider")) }
    }

    @Test
    fun `demo checkout endpoint is absent in production even when opt in is set`() {
        ApplicationContextRunner()
            .withUserConfiguration(LocalDemoBillingController::class.java)
            .withPropertyValues(
                "spring.profiles.active=prod",
                "app.billing.demo.enabled=true",
                "app.billing.payping.enabled=false",
            )
            .run { context -> assertFalse(context.containsBean("localDemoBillingController")) }
    }
}
