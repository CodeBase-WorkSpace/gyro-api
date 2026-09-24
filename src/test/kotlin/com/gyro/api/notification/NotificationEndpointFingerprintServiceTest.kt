package com.gyro.api.notification

import com.gyro.api.notification.application.NotificationEndpointFingerprintService
import com.gyro.api.notification.config.NotificationProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NotificationEndpointFingerprintServiceTest {
    private val service = NotificationEndpointFingerprintService(
        NotificationProperties(endpointFingerprintKey = "0123456789abcdef0123456789abcdef"),
    )

    @Test
    fun `fingerprint normalizes equivalent account destinations without retaining their value`() {
        val normalized = service.forValue("person@example.com")

        assertEquals(normalized, service.forValue(" Person@Example.com "))
        assertNotEquals(normalized, service.forValue("other@example.com"))
        assertEquals(64, normalized.length)
    }
}
