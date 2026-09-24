package com.gyro.api.auth.application

import com.gyro.api.auth.web.VerificationStartResponse
import java.util.UUID

interface AccountSecurityService {
    /** Sends an OTP step-up code to the user's verified identifier. */
    fun startStepUp(userId: UUID): VerificationStartResponse

    /** Confirms an OTP step-up code and records a short-lived "recently verified" marker. */
    fun confirmStepUp(userId: UUID, code: String)

    /** Sets a password on an account that currently has none (session-authenticated). */
    fun setPassword(userId: UUID, newPassword: String)

    /** Changes an existing password. Requires the current password or a recent OTP step-up. */
    fun changePassword(userId: UUID, currentPassword: String?, newPassword: String)

    /**
     * Removes the password (reverting to OTP-only). Requires a recent OTP step-up and refuses to
     * leave the account without at least one verified login identifier.
     */
    fun removePassword(userId: UUID)
}
