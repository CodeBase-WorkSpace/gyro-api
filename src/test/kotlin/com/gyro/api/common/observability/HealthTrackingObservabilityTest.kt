package com.gyro.api.common.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gyro.api.common.request.RequestIds
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthTrackingObservabilityTest {
    private val logger = LoggerFactory.getLogger(HealthTrackingObservability::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        RequestIds.clear()
    }

    @Test
    fun `business logs expose only safe progress goal and weight metadata`() {
        val observability = HealthTrackingObservability(SimpleMeterRegistry())
        val userId = UUID.randomUUID()
        logger.addAppender(appender)
        RequestIds.put("request-123")

        observability.goalSaved(userId, "FLAT")
        observability.weightEntrySaved(userId, "MANUAL")
        observability.weightEntriesBatchAccepted(userId, submittedCount = 2, acceptedCount = 2)
        observability.observeProgressQuery(
            userId = userId,
            domain = "weight",
            mode = "single",
            period = "PHASE",
            rangeCount = 7,
        ) {
            "ok"
        }

        val renderedLogs = appender.list.joinToString(separator = "\n") { event ->
            val keyValues = event.keyValuePairs.joinToString(separator = " ") { "${it.key}=${it.value}" }
            val mdc = event.mdcPropertyMap.entries.joinToString(separator = " ") { "${it.key}=${it.value}" }
            "${event.formattedMessage} $keyValues $mdc"
        }

        assertTrue(renderedLogs.contains("nutrition_goal_saved"))
        assertTrue(renderedLogs.contains("weight_entry_saved"))
        assertTrue(renderedLogs.contains("weight_entries_batch_accepted"))
        assertTrue(renderedLogs.contains("progress_range_requested"))
        assertTrue(renderedLogs.contains("request-123"))

        listOf(
            "82.400",
            "Morning weigh-in",
            "Imported from CSV",
            "Authorization",
            "Bearer secret-access-token",
            "Cookie",
            "refreshToken=secret-refresh-token",
            "verificationCode",
            "654321",
        ).forEach { sensitiveValue ->
            assertFalse(
                renderedLogs.contains(sensitiveValue),
                "Expected business logs not to contain '$sensitiveValue'",
            )
        }
    }

    @Test
    fun `structured health event writes request id once from MDC`() {
        logger.addAppender(appender)
        RequestIds.put("request-123")

        HealthTrackingObservability(SimpleMeterRegistry()).goalSaved(
            userId = UUID.fromString("33a1e6c8-2373-4d86-8fd2-06af87edbcdb"),
            scheduleType = "DAILY",
        )

        val event = appender.list.single()
        val fields = event.keyValuePairs.associate { it.key to it.value }
        assertFalse(fields.containsKey("requestId"))
        assertEquals("request-123", event.mdcPropertyMap["requestId"])

        val json = structuredJson(event)
        assertEquals(1, "\"requestId\"".toRegex().findAll(json).count())
        assertTrue(json.contains("\"requestId\":\"request-123\""))
    }

    private fun structuredJson(event: ILoggingEvent): String {
        val loggerContext = logger.loggerContext.apply {
            putObject(Environment::class.java.name, StandardEnvironment())
        }
        val encoder = StructuredLogEncoder().apply {
            context = loggerContext
            setFormat("logstash")
            start()
        }

        val json = encoder.encode(event).decodeToString()
        encoder.stop()
        return json
    }
}
