package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.NotificationDestinationResolver
import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.domain.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.notification", name = ["email-enabled"], havingValue = "true")
class SmtpNotificationEmailAdapter(
    private val mail: JavaMailSender,
    private val destinations: NotificationDestinationResolver,
    @org.springframework.beans.factory.annotation.Value("\${gyro.email.from}") private val from: String,
) : NotificationChannelAdapter {
    override val adapterKey = "smtp-email"

    override fun reconcile(notification: RenderedNotification): AdapterResult = UNKNOWN_AFTER_SEND

    override fun deliver(notification: RenderedNotification): AdapterResult {
        val destination = destinations.resolve(notification)
            ?: return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        return try {
            val message = mail.createMimeMessage()
            val helper = MimeMessageHelper(message, "UTF-8")
            helper.setFrom(from)
            helper.setTo(destination)
            helper.setSubject(requireNotNull(notification.subject) { "SMTP notification requires a subject" })
            helper.setText(notification.htmlBody ?: notification.plainBody, notification.htmlBody != null)
            mail.send(message)
            AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS)
        } catch (exception: MailAuthenticationException) {
            TRANSIENT_FAILURE
        } catch (_: MailSendException) {
            // Spring does not expose a stable destination-specific failure contract across SMTP providers.
            // Keep ambiguous rejections provider-scoped rather than poisoning the account endpoint.
            TRANSIENT_FAILURE
        } catch (_: Exception) {
            AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        }
    }

    private companion object {
        val TRANSIENT_FAILURE = AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT)
        val UNKNOWN_AFTER_SEND = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)
    }
}
