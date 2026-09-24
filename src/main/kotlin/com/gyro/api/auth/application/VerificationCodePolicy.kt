package com.gyro.api.auth.application

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class VerificationCodePolicy(
    private val environment: Environment,
) {
    fun acceptsBypassCode(rawCode: String): Boolean {
        return rawCode.trim() == LOCAL_STAGING_BYPASS_CODE &&
            environment.activeProfiles.any { it == DEV_PROFILE || it == STAGING_PROFILE }
    }

    companion object {
        const val LOCAL_STAGING_BYPASS_CODE = "111000"
        private const val DEV_PROFILE = "dev"
        private const val STAGING_PROFILE = "staging"
    }
}
