package com.gyro.api.auth.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class StartPasswordResetRequest(
    @field:NotBlank
    val identifier: String,
)

data class ConfirmPasswordResetRequest(
    @field:NotBlank
    val identifier: String,

    @field:NotBlank
    @field:Size(min = 6, max = 6, message = "Reset code must be exactly 6 characters long.")
    val code: String,

    @field:NotBlank
    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{9,}\$",
        message = "Password must be at least 9 characters long and contain at least one digit, uppercase and lowercase character."
    )
    val newPassword: String,
)
