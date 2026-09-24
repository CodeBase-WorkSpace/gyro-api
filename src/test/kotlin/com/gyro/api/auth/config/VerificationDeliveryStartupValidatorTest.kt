package com.gyro.api.auth.config

import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertFailsWith

class VerificationDeliveryStartupValidatorTest {
    private val validator = VerificationDeliveryStartupValidator()

    @Test
    fun `production rejects log only delivery mode`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.LOG_ONLY,
                allowCodeLogging = false,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("prod") },
        )

        assertFailsWith<IllegalArgumentException> {
            runner.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `production rejects raw verification code logging`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                allowCodeLogging = true,
                smsIr = SmsIrProperties(
                    apiKey = "sandbox-key",
                ),
            ),
            environment = MockEnvironment().apply { setActiveProfiles("prod") },
        )

        assertFailsWith<IllegalArgumentException> {
            runner.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `staging allows log only delivery mode`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.LOG_ONLY,
                allowCodeLogging = true,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )

        runner.run(DefaultApplicationArguments())
    }

    @Test
    fun `sms ir provider requires api key`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                allowCodeLogging = false,
                smsIr = SmsIrProperties(
                    apiKey = "",
                ),
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertFailsWith<IllegalArgumentException> {
            runner.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `Kavenegar provider requires API key and templates`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.KAVENEGAR,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertFailsWith<IllegalArgumentException> {
            runner.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `Kavenegar provider accepts API key with approved default templates`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.KAVENEGAR,
                kavenegar = KavenegarProperties(apiKey = "sandbox-key"),
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )

        runner.run(DefaultApplicationArguments())
    }

    @Test
    fun `smtp email provider requires mail settings`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                emailProvider = VerificationEmailProvider.SMTP,
            ),
            environment = MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertFailsWith<IllegalArgumentException> {
            runner.run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `smtp email provider accepts complete mail settings`() {
        val runner = validator.validateVerificationDeliveryConfiguration(
            properties = VerificationDeliveryProperties(
                emailProvider = VerificationEmailProvider.SMTP,
            ),
            environment = MockEnvironment().apply {
                setActiveProfiles("staging")
                setProperty("spring.mail.host", "mail.chabookan.ir")
                setProperty("spring.mail.username", "info@gyrohealth.ir")
                setProperty("spring.mail.password", "secret")
                setProperty("gyro.email.from", "info@gyrohealth.ir")
            },
        )

        runner.run(DefaultApplicationArguments())
    }
}
