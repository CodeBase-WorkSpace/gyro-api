package com.gyro.api.notification.application

import com.gyro.api.notification.config.NotificationProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class NotificationEndpointFingerprintService(
    private val properties: NotificationProperties,
) {
    fun forValue(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.endpointFingerprintKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.trim().lowercase().toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
