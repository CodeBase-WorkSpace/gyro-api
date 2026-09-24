package com.gyro.api.auth.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** Same policy as registration/password-reset: min 9 chars with lower, upper, and digit. */
private const val PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{9,}\$"
private const val PASSWORD_MESSAGE =
    "Password must be at least 9 characters long and contain at least one digit, uppercase and lowercase character."

data class SetPasswordRequest(
    @field:NotBlank
    @field:Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    val newPassword: String,
)

data class ChangePasswordRequest(
    val currentPassword: String? = null,

    @field:NotBlank
    @field:Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    val newPassword: String,
)

data class StepUpConfirmRequest(
    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Verification code must be exactly 6 characters long.")
    val code: String,
)
