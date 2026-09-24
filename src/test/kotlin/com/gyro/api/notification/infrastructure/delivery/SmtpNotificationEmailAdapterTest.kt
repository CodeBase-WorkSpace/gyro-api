package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.NotificationDestinationResolver
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.RenderedNotification
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtpNotificationEmailAdapterTest {
    @Test
    fun `authentication failure is transient`() {
        val mail = configuredMailSender()
        val notification = notification()
        Mockito.doThrow(MailAuthenticationException("SMTP authentication failed", RuntimeException("denied")))
            .`when`(mail).send(Mockito.any(MimeMessage::class.java))

        val result = adapter(mail, notification).deliver(notification)

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `SMTP send timeout is transient`() {
        val mail = configuredMailSender()
        val notification = notification()
        Mockito.doThrow(MailSendException("SMTP connection timed out"))
            .`when`(mail).send(Mockito.any(MimeMessage::class.java))

        val result = adapter(mail, notification).deliver(notification)

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `malformed sender configuration is permanent`() {
        val mail = configuredMailSender()
        val notification = notification()

        val result = adapter(mail, notification, from = "").deliver(notification)

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_PERMANENT, result.classification)
        Mockito.verify(mail, Mockito.never()).send(Mockito.any(MimeMessage::class.java))
    }

    @Test
    fun `Persian receipt content is sent as UTF-8 HTML`() {
        val mail = configuredMailSender()
        val sent = AtomicReference<MimeMessage>()
        Mockito.doAnswer { invocation ->
            sent.set(invocation.getArgument(0))
            null
        }.`when`(mail).send(Mockito.any(MimeMessage::class.java))
        val rendered = notification(
            subject = "رسید پرداخت جیرو",
            plainBody = "پرداخت شما با موفقیت ثبت شد.",
            htmlBody = "<html lang=\"fa\" dir=\"rtl\"><body>پرداخت شما با موفقیت ثبت شد.</body></html>",
        )

        val result = adapter(mail, rendered).deliver(rendered)

        assertEquals(AdapterOutcome.SUCCESS, result.outcome)
        val message = sent.get()
        message.saveChanges()
        assertEquals("رسید پرداخت جیرو", message.subject)
        assertTrue((message.content as String).contains("پرداخت شما با موفقیت ثبت شد."))
        assertTrue(message.contentType.contains("UTF-8", ignoreCase = true))
    }

    private fun configuredMailSender(): JavaMailSender = Mockito.mock(JavaMailSender::class.java).also { mail ->
        Mockito.`when`(mail.createMimeMessage()).thenReturn(MimeMessage(Session.getInstance(Properties())))
    }

    private fun adapter(
        mail: JavaMailSender,
        notification: RenderedNotification,
        from: String = "notifications@gyro.app",
    ): SmtpNotificationEmailAdapter {
        val destinations = Mockito.mock(NotificationDestinationResolver::class.java)
        Mockito.`when`(destinations.resolve(notification)).thenReturn("customer@example.com")
        return SmtpNotificationEmailAdapter(mail, destinations, from)
    }

    private fun notification(
        subject: String = "Payment receipt",
        plainBody: String = "Payment received",
        htmlBody: String? = null,
    ) = RenderedNotification(
        intentId = UUID.randomUUID(),
        deliveryId = UUID.randomUUID(),
        type = NotificationType.PAYMENT_VERIFIED,
        channel = NotificationChannel.EMAIL,
        endpointReference = "account:email",
        providerRequestId = "provider-request",
        subject = subject,
        plainBody = plainBody,
        htmlBody = htmlBody,
    )
}
