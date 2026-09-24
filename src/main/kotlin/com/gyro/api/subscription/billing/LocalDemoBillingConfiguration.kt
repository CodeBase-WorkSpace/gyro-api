package com.gyro.api.subscription.billing

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile

@Configuration
@Profile("dev & !prod")
@ConditionalOnProperty(prefix = "app.billing.demo", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "app.billing.payping", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class LocalDemoBillingConfiguration {
    @Bean
    @Primary
    fun localDemoBillingProvider(
        @Value("\${server.port:8080}") serverPort: Int,
        @Value("\${app.api.base-path:/api/v1}") apiBasePath: String,
    ): LocalDemoBillingProvider = LocalDemoBillingProvider(serverPort, apiBasePath)
}
