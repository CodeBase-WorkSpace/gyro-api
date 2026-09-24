package com.gyro.api.auth.application

import java.util.UUID

/**
 * Published after a signup verification materializes a user. Listeners run
 * after the signup transaction commits, so side effects (like the trial grant)
 * can never break or roll back registration.
 */
data class UserRegisteredEvent(
    val userId: UUID,
    val email: String?,
    val phoneNumber: String?,
) {
    val primaryIdentifier: String? get() = email ?: phoneNumber
}
