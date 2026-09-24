package com.gyro.api.common.config

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DisabledUserPasswordAuthConfigTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(UserDetailsServiceAutoConfiguration::class.java))
        .withUserConfiguration(DisabledUserPasswordAuthConfig::class.java)

    @Test
    fun `prevents Spring Boot from creating a generated in-memory user`() {
        contextRunner.run { context ->
            val services = context.getBeansOfType(UserDetailsService::class.java)

            assertEquals(1, services.size)
            assertFalse(services.values.any { it is InMemoryUserDetailsManager })
            assertFailsWith<UsernameNotFoundException> {
                services.values.single().loadUserByUsername("user")
            }
        }
    }
}
