package com.gyro.api.auth.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.config.VerificationDeliveryProperties
import com.gyro.api.auth.config.VerificationSmsProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogOnlyVerificationDeliveryAdapterTest {
    private val emailLogger = LoggerFactory.getLogger(LogOnlyEmailDeliveryAdapter::class.java) as Logger
    private val smsLogger = LoggerFactory.getLogger(LogOnlySmsDeliveryAdapter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @AfterEach
    fun tearDown() {
        emailLogger.detachAppender(appender)
        smsLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `email adapter logs searchable dev verification event with masked identifier and code in staging`() {
        val adapter = LogOnlyEmailDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.LOG_ONLY,
                allowCodeLogging = true,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )
        emailLogger.addAppender(appender)

        adapter.deliver(
            VerificationDeliveryRequest(
                channel = VerificationChannel.EMAIL,
                identifier = "Person@example.com",
                purpose = VerificationPurpose.SIGNUP,
                code = "123456",
            )
        )

        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("DEV_VERIFICATION_CODE", fields["event"])
        assertEquals("EMAIL", fields["channel"])
        assertEquals("p***@example.com", fields["identifier"])
        assertEquals("SIGNUP", fields["purpose"])
        assertEquals("123456", fields["code"])
    }

    @Test
    fun `sms adapter omits raw code outside dev and staging`() {
        val adapter = LogOnlySmsDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.LOG_ONLY,
                allowCodeLogging = true,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("test") },
        )
        smsLogger.addAppender(appender)

        adapter.deliver(
            VerificationDeliveryRequest(
                channel = VerificationChannel.SMS,
                identifier = "+989121234567",
                purpose = VerificationPurpose.LOGIN,
                code = "654321",
            )
        )

        val fields = appender.list.single().keyValuePairs.associate { it.key to it.value }
        assertEquals("DEV_VERIFICATION_CODE", fields["event"])
        assertEquals("SMS", fields["channel"])
        assertEquals("***4567", fields["identifier"])
        assertEquals("LOGIN", fields["purpose"])
        assertNull(fields["code"])
    }
}
