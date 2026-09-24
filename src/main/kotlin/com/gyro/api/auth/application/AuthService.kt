package com.gyro.api.auth.application

import com.gyro.api.auth.web.*

interface AuthService {

    fun register(request: RegisterRequest): VerificationStartResponse

    fun verifyRegistration(request: ConfirmSignupVerificationRequest): AuthResponse

    fun resendRegistrationVerification(request: SignupVerificationResendRequest): VerificationStartResponse

    fun loginWithPassword(request: PasswordLoginRequest): AuthResponse

    fun loginWithOtp(request: OtpLoginConfirmRequest): AuthResponse

    fun refresh(request: RefreshTokenRequest): AuthResponse

    fun logout(refreshToken: String)
}
