package com.gyro.api.auth.domain

enum class UserStatus {
    ACTIVE,
    DISABLED,
    PENDING_VERIFICATION,
    DEACTIVATED,
    DELETION_IN_PROGRESS,
    DELETED,
}
