package com.gyro.api.auth.web

import jakarta.validation.constraints.*

data class RegisterRequest(
    @field:Size(max = 320)
    val email: String? = null,

    @field:Size(max = 32)
    val phoneNumber: String? = null,
) {
    @get:AssertTrue(message = "Provide either email or phone number, not both.")
    val singleContactProvided: Boolean
        get() = !email.isNullOrBlank() xor !phoneNumber.isNullOrBlank()
}

data class PasswordLoginRequest(
    @field:NotBlank
    val identifier: String,

    @field:NotBlank
    val password: String,
)

data class OtpLoginStartRequest(
    @field:NotBlank
    val identifier: String,
)

data class SignupVerificationResendRequest(
    @field:NotBlank
    val identifier: String,
)

data class OtpLoginConfirmRequest(
    @field:NotBlank
    val identifier: String,

    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Verification code must be exactly 6 characters long.")
    val code: String,
)

data class RefreshTokenRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class LogoutRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val accessExpiresInSeconds: Long,
)
