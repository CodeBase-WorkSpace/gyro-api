package com.gyro.api.auth.application

import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.web.*

interface EmailVerificationService {
    fun startLoginOtp(request: OtpLoginStartRequest): VerificationStartResponse

    fun confirmLoginOtp(request: OtpLoginConfirmRequest): GyroUser

    fun start(request: StartEmailVerificationRequest): VerificationStartResponse

    fun confirm(request: ConfirmEmailVerificationRequest)

    fun startSignupEmail(email: String): VerificationStartResponse

    fun confirmSignupEmail(request: ConfirmSignupVerificationRequest): GyroUser

    fun startPhone(request: StartPhoneVerificationRequest): VerificationStartResponse

    fun confirmPhone(request: ConfirmPhoneVerificationRequest)
}
