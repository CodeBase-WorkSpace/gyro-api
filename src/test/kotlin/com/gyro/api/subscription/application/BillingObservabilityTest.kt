package com.gyro.api.subscription.application

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

class BillingObservabilityTest {
    @Test
    fun `failed refresh increments the stale metrics detector`() {
        val registry = SimpleMeterRegistry()
        val beanFactory = StaticListableBeanFactory(mapOf("meterRegistry" to registry))
        val observability = BillingObservability(
            jdbcTemplate = FailingJdbcTemplate(),
            meterRegistryProvider = beanFactory.getBeanProvider(MeterRegistry::class.java),
            clock = Clock.systemUTC(),
        )

        observability.registerMetrics()
        observability.refresh()

        assertEquals(2.0, registry.get("gyro.billing.metrics.refresh.failures").counter().count())
    }

    private class FailingJdbcTemplate : JdbcTemplate() {
        override fun <T : Any> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            throw DataAccessResourceFailureException("database unavailable")
        }
    }
}
