package com.gyro.api.notification.config

import com.gyro.api.notification.domain.NotificationChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals

class NotificationDeliveryStartupValidatorTest {
    private fun run(properties: NotificationProperties) =
        NotificationDeliveryStartupValidator(properties, MockEnvironment()).run(DefaultApplicationArguments())

    @Test
    fun `requires bot token and chat id when Telegram is enabled`() {
        assertThrows<IllegalArgumentException> { run(NotificationProperties(telegramEnabled = true)) }
        assertThrows<IllegalArgumentException> {
            run(NotificationProperties(telegramEnabled = true, telegramBotToken = "token"))
        }
        assertDoesNotThrow {
            run(NotificationProperties(telegramEnabled = true, telegramBotToken = "token", telegramChatId = "@channel"))
        }
    }

    @Test
    fun `requires complete Telegram transport and linking configuration`() {
        val transport = NotificationProperties(
            telegramEnabled = true,
            telegramBotToken = "token",
            telegramChatId = "@channel",
        )

        assertThrows<IllegalArgumentException> {
            run(transport.copy(telegramLinkingEnabled = true))
        }
        assertThrows<IllegalArgumentException> {
            run(transport.copy(
                telegramLinkingEnabled = true,
                telegramBotUsername = "gyro_bot",
                telegramWebhookSecret = "too-short",
            ))
        }
        assertThrows<IllegalArgumentException> {
            run(transport.copy(
                telegramLinkingEnabled = true,
                telegramBotUsername = "gyro_bot",
                telegramWebhookSecret = "test-webhook-secret",
                telegramWebhookUrl = "http://localhost/webhook",
            ))
        }
        assertDoesNotThrow {
            run(transport.copy(
                telegramLinkingEnabled = true,
                telegramBotUsername = "gyro_bot",
                telegramWebhookSecret = "test-webhook-secret",
                telegramWebhookUrl = "https://api.example.com/api/v1/integrations/telegram/webhook",
            ))
        }
    }

    @Test
    fun `Telegram master switch disables linking validation`() {
        assertDoesNotThrow {
            run(NotificationProperties(telegramEnabled = false, telegramLinkingEnabled = true))
        }
    }

    @Test
    fun `requires proxy host and port when notification egress proxy is configured`() {
        val base = NotificationProperties(
            egressProxyType = NotificationEgressProxyType.HTTP,
        )
        assertThrows<IllegalArgumentException> { run(base) }
        assertDoesNotThrow { run(base.copy(egressProxyHost = "proxy.local", egressProxyPort = 1080)) }
    }

    @Test
    fun `proxy credentials must be configured as a complete pair`() {
        assertThrows<IllegalArgumentException> {
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.HTTP,
                egressProxyUsername = "user",
            )
        }
        assertDoesNotThrow {
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.HTTP,
                egressProxyUsername = "user",
                egressProxyPassword = "password",
            )
        }
    }

    @Test
    fun `proxy credentials are rejected when proxying is disabled`() {
        assertThrows<IllegalArgumentException> {
            NotificationProperties(
                egressProxyUsername = "user",
                egressProxyPassword = "password",
            )
        }
    }

    @Test
    fun `SOCKS proxy accepts only unauthenticated configuration`() {
        assertDoesNotThrow {
            NotificationProperties(egressProxyType = NotificationEgressProxyType.SOCKS)
        }
        assertThrows<IllegalArgumentException> {
            NotificationProperties(
                egressProxyType = NotificationEgressProxyType.SOCKS,
                egressProxyUsername = "user",
                egressProxyPassword = "password",
            )
        }
    }

    @Test
    fun `single API claims Telegram only when its adapter is enabled`() {
        assertEquals(
            setOf(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH),
            NotificationProperties().deliveryChannels,
        )
        assertEquals(
            setOf(NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.PUSH, NotificationChannel.TELEGRAM),
            NotificationProperties(telegramEnabled = true).deliveryChannels,
        )
    }
}
