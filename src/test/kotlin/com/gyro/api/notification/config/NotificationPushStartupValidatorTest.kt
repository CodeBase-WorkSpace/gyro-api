package com.gyro.api.notification.config

import nl.martijndwars.webpush.Utils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.BigIntegers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Base64

class NotificationPushStartupValidatorTest {
    private val validator = NotificationPushStartupValidator()

    @Test
    fun `rejects the development subscription key in production`() {
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(),
            MockEnvironment().apply { setActiveProfiles("prod") },
        )

        assertThrows<IllegalArgumentException> { runner.run(DefaultApplicationArguments()) }
    }

    @Test
    fun `requires complete VAPID configuration when Web Push is enabled`() {
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(webPushEnabled = true),
            MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertThrows<IllegalArgumentException> { runner.run(DefaultApplicationArguments()) }
    }

    @Test
    fun `allows disabled Web Push outside production`() {
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(),
            MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertDoesNotThrow { runner.run(DefaultApplicationArguments()) }
    }

    @Test
    fun `accepts a valid VAPID key pair and public contact subject`() {
        val (publicKey, privateKey) = vapidKeyPair()
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(
                webPushEnabled = true,
                webPushPublicKey = publicKey,
                webPushPrivateKey = privateKey,
                webPushSubject = "mailto:info@gyrohealth.ir",
            ),
            MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertDoesNotThrow { runner.run(DefaultApplicationArguments()) }
    }

    @Test
    fun `rejects mismatched VAPID keys before accepting traffic`() {
        val (publicKey, _) = vapidKeyPair()
        val (_, differentPrivateKey) = vapidKeyPair()
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(
                webPushEnabled = true,
                webPushPublicKey = publicKey,
                webPushPrivateKey = differentPrivateKey,
            ),
            MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertThrows<IllegalArgumentException> { runner.run(DefaultApplicationArguments()) }
    }

    @Test
    fun `rejects local-only VAPID contact subjects`() {
        val (publicKey, privateKey) = vapidKeyPair()
        val runner = validator.validateNotificationPushConfiguration(
            NotificationProperties(
                webPushEnabled = true,
                webPushPublicKey = publicKey,
                webPushPrivateKey = privateKey,
                webPushSubject = "mailto:notifications@gyro.local",
            ),
            MockEnvironment().apply { setActiveProfiles("staging") },
        )

        assertThrows<IllegalArgumentException> { runner.run(DefaultApplicationArguments()) }
    }

    private fun vapidKeyPair(): Pair<String, String> {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
        val pair = generator.generateKeyPair()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val publicKey = encoder.encodeToString(Utils.encode(pair.public as ECPublicKey))
        val privateKey = encoder.encodeToString(
            BigIntegers.asUnsignedByteArray(32, (pair.private as ECPrivateKey).d),
        )
        return publicKey to privateKey
    }
}
