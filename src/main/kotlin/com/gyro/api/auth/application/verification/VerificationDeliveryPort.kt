package com.gyro.api.auth.application.verification

interface VerificationDeliveryPort {
    val channel: VerificationChannel

    fun deliver(request: VerificationDeliveryRequest)
}

data class VerificationDeliveryRequest(
    val channel: VerificationChannel,
    val identifier: String,
    val purpose: VerificationPurpose,
    val code: String,
)

enum class VerificationChannel {
    EMAIL,
    SMS,
}

enum class VerificationPurpose {
    SIGNUP,
    LOGIN,
    PASSWORD_RESET,
    CHANGE_IDENTIFIER,
}
