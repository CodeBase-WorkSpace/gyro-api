package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.EmailMessage
import com.gyro.api.auth.application.EmailSender
import com.gyro.api.common.error.ExternalServiceException
import jakarta.mail.MessagingException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.util.Locale

@Component
@ConditionalOnProperty(prefix = "gyro.verification", name = ["email-provider"], havingValue = "smtp")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    @Value("\${gyro.email.from}")
    private val from: String,
) : EmailSender {
    override fun send(message: EmailMessage) {
        try {
            val mimeMessage = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(mimeMessage, "UTF-8")
            helper.setFrom(from)
            helper.setTo(message.to)
            helper.setSubject(message.subject)
            helper.setText(message.html, true)

            mailSender.send(mimeMessage)
            logger.info("Sent verification email via SMTP. to={}", message.to.maskEmail())
        } catch (ex: MailException) {
            logger.error("Failed to send verification email via SMTP. to={}", message.to.maskEmail(), ex)
            throw ExternalServiceException("SMTP email")
        } catch (ex: MessagingException) {
            logger.error("Failed to compose verification email. to={}", message.to.maskEmail(), ex)
            throw ExternalServiceException("SMTP email")
        }
    }

    private fun String.maskEmail(): String {
        val parts = trim().lowercase(Locale.ROOT).split("@", limit = 2)
        if (parts.size != 2) return "***"

        return "${parts[0].take(1).ifBlank { "*" }}***@${parts[1]}"
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SmtpEmailSender::class.java)
    }
}
