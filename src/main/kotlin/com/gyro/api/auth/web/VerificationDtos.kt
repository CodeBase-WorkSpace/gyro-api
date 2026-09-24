package com.gyro.api.auth.web

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class StartEmailVerificationRequest(
    @field:NotBlank(message = "Email is required")
    @field:Size(max = 320)
    val email: String
)
data class ConfirmEmailVerificationRequest(
    @field:NotBlank(message = "Email is required")
    @field:Size(max = 320)
    val email: String,
    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Verification code must be exactly 6 characters long.")
    val code: String
)

data class StartPhoneVerificationRequest(
    @field:NotBlank(message = "Phone number is not valid")
    val phoneNumber: String
)
data class ConfirmPhoneVerificationRequest(
    @field:NotBlank(message = "Phone number is not valid")
    val phoneNumber: String,
    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Verification code must be exactly 6 characters long.")
    val code: String
)

data class ConfirmSignupVerificationRequest(
    @field:Size(max = 320)
    val email: String? = null,

    @field:Size(max = 32)
    val phoneNumber: String? = null,

    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Verification code must be exactly 6 characters long.")
    val code: String,
) {
    @get:AssertTrue(message = "Provide either email or phone number, not both.")
    val singleContactProvided: Boolean
        get() = !email.isNullOrBlank() xor !phoneNumber.isNullOrBlank()
}

data class VerificationStartResponse(
    val message: String,
    val otpExpireInSeconds: Int? = null,
)
