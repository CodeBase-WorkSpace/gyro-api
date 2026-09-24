package com.gyro.api.notification.config

import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import java.security.Security

@Configuration
class NotificationPushStartupValidator {
    @Bean
    fun validateNotificationPushConfiguration(properties: NotificationProperties, environment: Environment): ApplicationRunner = ApplicationRunner {
        if (environment.activeProfiles.contains("prod")) {
            require(properties.pushSubscriptionEncryptionKey != NotificationProperties.DEFAULT_PUSH_SUBSCRIPTION_ENCRYPTION_KEY) {
                "NOTIFICATION_PUSH_SUBSCRIPTION_ENCRYPTION_KEY must be configured in production."
            }
        }
        if (properties.webPushEnabled) {
            validateWebPushConfiguration(properties)
        }
    }

    private fun validateWebPushConfiguration(properties: NotificationProperties) {
        val encodedPublicKey = requireNotNull(properties.webPushPublicKey?.takeIf(String::isNotBlank)) {
            "NOTIFICATION_WEB_PUSH_PUBLIC_KEY is required when Web Push is enabled."
        }
        val encodedPrivateKey = requireNotNull(properties.webPushPrivateKey?.takeIf(String::isNotBlank)) {
            "NOTIFICATION_WEB_PUSH_PRIVATE_KEY is required when Web Push is enabled."
        }
        require(isValidSubject(properties.webPushSubject)) {
            "NOTIFICATION_WEB_PUSH_SUBJECT must be a public mailto: address or HTTPS URL."
        }

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val publicKey = runCatching { Utils.loadPublicKey(encodedPublicKey) }
            .getOrElse { throw IllegalArgumentException("NOTIFICATION_WEB_PUSH_PUBLIC_KEY is not a valid P-256 public key.", it) }
        val privateKey = runCatching { Utils.loadPrivateKey(encodedPrivateKey) }
            .getOrElse { throw IllegalArgumentException("NOTIFICATION_WEB_PUSH_PRIVATE_KEY is not a valid P-256 private key.", it) }
        require(Utils.verifyKeyPair(privateKey, publicKey)) {
            "NOTIFICATION_WEB_PUSH_PUBLIC_KEY and NOTIFICATION_WEB_PUSH_PRIVATE_KEY do not form a key pair."
        }
    }

    private fun isValidSubject(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "mailto" -> {
                val address = uri.schemeSpecificPart
                val domain = address.substringAfterLast('@', missingDelimiterValue = "").lowercase()
                address.count { it == '@' } == 1 && domain.contains('.') && !domain.endsWith(".local")
            }
            "https" -> uri.host?.contains('.') == true && uri.userInfo == null
            else -> false
        }
    }
}
