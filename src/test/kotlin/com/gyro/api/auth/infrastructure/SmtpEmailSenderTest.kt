package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.EmailMessage
import com.gyro.api.auth.application.verification.VerificationPurpose
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.Duration

class SmtpEmailSenderTest {
    @Test
    fun `smtp sender composes html message with configured sender`() {
        val mailSender = CapturingJavaMailSender()
        val sender = SmtpEmailSender(
            mailSender = mailSender,
            from = "info@gyrohealth.ir",
        )

        sender.send(
            EmailMessage(
                to = "user@example.com",
                subject = "Verify your Gyro email",
                html = "<strong>123456</strong>",
            )
        )

        val sentMessage = mailSender.sentMessage
        assertEquals("Verify your Gyro email", sentMessage.subject)
        assertEquals("info@gyrohealth.ir", sentMessage.from.single().toString())
        assertEquals("user@example.com", sentMessage.allRecipients.single().toString())
        assertTrue(sentMessage.content.toString().contains("<strong>123456</strong>"))
    }

    @Test
    fun `verification email template renders farsi rtl branded code message`() {
        val html = VerificationEmailTemplate().render(
            code = "123456",
            expiresIn = Duration.ofMinutes(10),
            purpose = VerificationPurpose.SIGNUP,
        )

        assertTrue(html.contains("""<html lang="fa" dir="rtl">"""))
        assertTrue(html.contains("جیرو"))
        assertTrue(html.contains("تایید ایمیل جیرو"))
        assertTrue(html.contains("123456"))
        assertTrue(html.contains("۱۰ دقیقه"))
        assertTrue(html.contains("اگر کد کار نکرد"))
    }

    private class CapturingJavaMailSender : JavaMailSenderImpl() {
        lateinit var sentMessage: MimeMessage

        override fun send(mimeMessage: MimeMessage) {
            sentMessage = mimeMessage
        }
    }
}
