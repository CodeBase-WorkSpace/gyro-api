package com.gyro.api.auth.application

import com.gyro.api.auth.web.ConfirmPasswordResetRequest
import com.gyro.api.auth.web.StartPasswordResetRequest
import com.gyro.api.auth.web.VerificationStartResponse

interface ResetPasswordService {
    fun start(request: StartPasswordResetRequest): VerificationStartResponse

    fun confirm(request: ConfirmPasswordResetRequest)
}
