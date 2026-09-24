package com.gyro.api.auth.config

import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.util.StringUtils

@Configuration
class VerificationDeliveryStartupValidator {
    @Bean
    fun validateVerificationDeliveryConfiguration(
        properties: VerificationDeliveryProperties,
        environment: Environment,
    ): ApplicationRunner {
        return ApplicationRunner {
            if (properties.emailProvider == VerificationEmailProvider.SMTP) {
                require(environment.requiredMailProperty("spring.mail.host").isNotBlank()) {
                    "MAIL_HOST must be configured when gyro.verification.email-provider=smtp."
                }
                require(environment.requiredMailProperty("spring.mail.username").isNotBlank()) {
                    "MAIL_USERNAME must be configured when gyro.verification.email-provider=smtp."
                }
                require(environment.requiredMailProperty("spring.mail.password").isNotBlank()) {
                    "MAIL_PASSWORD must be configured when gyro.verification.email-provider=smtp."
                }
                require(environment.requiredMailProperty("gyro.email.from").isNotBlank()) {
                    "MAIL_FROM must be configured when gyro.verification.email-provider=smtp."
                }
            }

            if (properties.smsProvider == VerificationSmsProvider.SMS_IR) {
                require(properties.smsIr.apiKey.isNotBlank()) {
                    "gyro.verification.sms-ir.api-key must be configured when gyro.verification.sms-provider=sms-ir."
                }
                validateTemplate(properties.smsIr.signup, "signup")
                validateTemplate(properties.smsIr.login, "login")
                validateTemplate(properties.smsIr.passwordReset, "password-reset")
                validateTemplate(properties.smsIr.changeIdentifier, "change-identifier")
            }

            if (properties.smsProvider == VerificationSmsProvider.KAVENEGAR) {
                require(properties.kavenegar.apiKey.isNotBlank()) {
                    "gyro.verification.kavenegar.api-key must be configured when gyro.verification.sms-provider=kavenegar."
                }
                validateKavenegarTemplate(properties.kavenegar.signup, "signup")
                validateKavenegarTemplate(properties.kavenegar.login, "login")
                validateKavenegarTemplate(properties.kavenegar.passwordReset, "password-reset")
                validateKavenegarTemplate(properties.kavenegar.changeIdentifier, "change-identifier")
            }

            if (!environment.activeProfiles.contains(PROD_PROFILE)) return@ApplicationRunner

            require(properties.emailProvider != VerificationEmailProvider.LOG_ONLY) {
                "Production cannot use gyro.verification.email-provider=log-only."
            }

            require(properties.smsProvider != VerificationSmsProvider.LOG_ONLY) {
                "Production cannot use gyro.verification.sms-provider=log-only."
            }

            require(!properties.allowCodeLogging) {
                "Production cannot use gyro.verification.allow-code-logging=true."
            }
        }
    }

    private companion object {
        private const val PROD_PROFILE = "prod"
    }

    private fun Environment.requiredMailProperty(name: String): String {
        return getProperty(name).orEmpty().takeIf(StringUtils::hasText).orEmpty()
    }

    private fun validateTemplate(
        template: SmsIrTemplateProperties,
        templateName: String,
    ) {
        require(template.templateId > 0) {
            "gyro.verification.sms-ir.$templateName.template-id must be greater than zero when gyro.verification.sms-provider=sms-ir."
        }
        require(template.parameterName.isNotBlank()) {
            "gyro.verification.sms-ir.$templateName.parameter-name must not be blank when gyro.verification.sms-provider=sms-ir."
        }
    }

    private fun validateKavenegarTemplate(
        template: KavenegarTemplateProperties,
        templateName: String,
    ) {
        require(template.template.isNotBlank()) {
            "gyro.verification.kavenegar.$templateName.template must not be blank when gyro.verification.sms-provider=kavenegar."
        }
    }
}
