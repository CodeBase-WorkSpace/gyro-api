package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.EmailMessage
import com.gyro.api.auth.application.EmailSender
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryPort
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.application.verification.VerificationPurpose
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConditionalOnProperty(prefix = "gyro.verification", name = ["email-provider"], havingValue = "smtp")
class SmtpEmailDeliveryAdapter(
    private val emailSender: EmailSender,
    private val verificationEmailTemplate: VerificationEmailTemplate,
    @Value("\${app.auth.verification-code-ttl:10m}")
    private val ttl: Duration,
) : VerificationDeliveryPort {
    override val channel = VerificationChannel.EMAIL

    override fun deliver(request: VerificationDeliveryRequest) {
        require(request.channel == channel) {
            "SmtpEmailDeliveryAdapter cannot deliver channel=${request.channel}."
        }

        emailSender.send(
            EmailMessage(
                to = request.identifier,
                subject = request.purpose.subject(),
                html = verificationEmailTemplate.render(
                    code = request.code,
                    expiresIn = ttl,
                    purpose = request.purpose,
                ),
            )
        )
    }

    private fun VerificationPurpose.subject(): String {
        return when (this) {
            VerificationPurpose.SIGNUP -> "تایید ایمیل جیرو"
            VerificationPurpose.LOGIN -> "کد ورود به جیرو"
            VerificationPurpose.PASSWORD_RESET -> "بازیابی رمز عبور جیرو"
            VerificationPurpose.CHANGE_IDENTIFIER -> "تایید ایمیل جدید جیرو"
        }
    }
}
