package com.gyro.api.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun gyroOpenApi(): OpenAPI {
        return OpenAPI().info(
            Info()
                .title("Gyro API")
                .description("Public HTTP contract for Gyro clients.")
                .version("1.0.0"),
        ).servers(listOf(Server().url("/")))
    }
}
