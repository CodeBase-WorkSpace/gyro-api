package com.gyro.api.auth.config

import com.gyro.api.auth.application.verification.VerificationPurpose
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gyro.verification")
data class VerificationDeliveryProperties(
    val emailProvider: VerificationEmailProvider = VerificationEmailProvider.LOG_ONLY,
    val smsProvider: VerificationSmsProvider = VerificationSmsProvider.LOG_ONLY,
    val allowCodeLogging: Boolean = false,
    val smsIr: SmsIrProperties = SmsIrProperties(),
    val kavenegar: KavenegarProperties = KavenegarProperties(),
)

data class SmsIrProperties(
    val baseUrl: String = "https://api.sms.ir/v1",
    val apiKey: String = "",
    val signup: SmsIrTemplateProperties = SmsIrTemplateProperties(
        templateId = 258377,
        parameterName = "Code",
    ),
    val login: SmsIrTemplateProperties = SmsIrTemplateProperties(),
    val passwordReset: SmsIrTemplateProperties = SmsIrTemplateProperties(),
    val changeIdentifier: SmsIrTemplateProperties = SmsIrTemplateProperties(),
) {
    fun templateFor(purpose: VerificationPurpose): SmsIrTemplateProperties {
        return when (purpose) {
            VerificationPurpose.SIGNUP -> signup
            VerificationPurpose.LOGIN -> login
            VerificationPurpose.PASSWORD_RESET -> passwordReset
            VerificationPurpose.CHANGE_IDENTIFIER -> changeIdentifier
        }
    }
}

data class SmsIrTemplateProperties(
    val templateId: Int = 123456,
    val parameterName: String = "Code",
)

data class KavenegarProperties(
    val baseUrl: String = "https://api.kavenegar.com/v1",
    val apiKey: String = "",
    val signup: KavenegarTemplateProperties = KavenegarTemplateProperties("welcomeotp"),
    val login: KavenegarTemplateProperties = KavenegarTemplateProperties("welcomeotp"),
    val passwordReset: KavenegarTemplateProperties = KavenegarTemplateProperties("passwordotp"),
    val changeIdentifier: KavenegarTemplateProperties = KavenegarTemplateProperties("welcomeotp"),
) {
    fun templateFor(purpose: VerificationPurpose): KavenegarTemplateProperties {
        return when (purpose) {
            VerificationPurpose.SIGNUP -> signup
            VerificationPurpose.LOGIN -> login
            VerificationPurpose.PASSWORD_RESET -> passwordReset
            VerificationPurpose.CHANGE_IDENTIFIER -> changeIdentifier
        }
    }
}

data class KavenegarTemplateProperties(
    val template: String,
)

enum class VerificationSmsProvider {
    LOG_ONLY,
    SMS_IR,
    KAVENEGAR,
}

enum class VerificationEmailProvider {
    LOG_ONLY,
    SMTP,
}
