package com.gyro.api.notification.config

import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class NotificationConfigurationResourceTest {
    @Test
    fun `common application configuration maps Web Push environment variables`() {
        val configuration = ClassPathResource("application.yaml").inputStream.bufferedReader().use { it.readText() }

        listOf(
            "push-subscription-encryption-key: \${NOTIFICATION_PUSH_SUBSCRIPTION_ENCRYPTION_KEY:",
            "web-push-enabled: \${NOTIFICATION_WEB_PUSH_ENABLED:false}",
            "web-push-public-key: \${NOTIFICATION_WEB_PUSH_PUBLIC_KEY:}",
            "web-push-private-key: \${NOTIFICATION_WEB_PUSH_PRIVATE_KEY:}",
            "web-push-subject: \${NOTIFICATION_WEB_PUSH_SUBJECT:",
            "web-push-allowed-endpoint-hosts: \${NOTIFICATION_WEB_PUSH_ALLOWED_ENDPOINT_HOSTS:",
            "web-push-max-subscriptions-per-user: \${NOTIFICATION_WEB_PUSH_MAX_SUBSCRIPTIONS_PER_USER:10}",
            "web-push-timeout: \${NOTIFICATION_WEB_PUSH_TIMEOUT:10s}",
            "egress-proxy-type: \${NOTIFICATION_EGRESS_PROXY_TYPE:NONE}",
            "egress-proxy-host: \${NOTIFICATION_EGRESS_PROXY_HOST:}",
            "egress-proxy-port: \${NOTIFICATION_EGRESS_PROXY_PORT:0}",
            "egress-proxy-username: \${NOTIFICATION_EGRESS_PROXY_USERNAME:}",
            "egress-proxy-password: \${NOTIFICATION_EGRESS_PROXY_PASSWORD:}",
            "telegram-webhook-url: \${NOTIFICATION_TELEGRAM_WEBHOOK_URL:}",
            "subscribe-per-user-limit: \${RATE_LIMIT_WEB_PUSH_SUBSCRIBE_PER_USER:20}",
            "test-per-user-limit: \${RATE_LIMIT_WEB_PUSH_TEST_PER_USER:5}",
            "window: \${RATE_LIMIT_WEB_PUSH_WINDOW:15m}",
        ).forEach { expectedMapping -> assertContains(configuration, expectedMapping) }

        assertFalse("NOTIFICATION_DELIVERY_CHANNELS" in configuration)
        assertFalse("NOTIFICATION_TELEGRAM_PROXY_" in configuration)
    }
}
