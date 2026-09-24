package com.gyro.api.auth.application.verification

interface VerificationDeliveryGateway {
    fun deliver(
        channel: VerificationChannel,
        identifier: String,
        purpose: VerificationPurpose,
        code: String,
    )
}
