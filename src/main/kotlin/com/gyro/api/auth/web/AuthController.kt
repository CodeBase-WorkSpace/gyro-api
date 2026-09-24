package com.gyro.api.auth.web

import com.gyro.api.auth.application.AuthRateLimitService
import com.gyro.api.auth.application.AuthService
import com.gyro.api.auth.application.EmailVerificationService
import com.gyro.api.auth.application.ResetPasswordService
import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.request.TrustedClientIpResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("\${app.api.base-path}/auth")
class AuthController(
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val resetPasswordService: ResetPasswordService,
    private val authRateLimitService: AuthRateLimitService,
    private val idempotencyService: IdempotencyService,
    private val clientIps: TrustedClientIpResolver,
) {

    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<VerificationStartResponse> {
        val contactType = request.safeContactType()
        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "controller_received")
            .addKeyValue("contactType", contactType)
            .addKeyValue("idempotencyKeyPresent", !idempotencyKey.isNullOrBlank())
            .log("Registration request received.")

        try {
            authRateLimitService.checkRegister(request, clientIps.resolve(servletRequest))
            logger.atInfo()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "rate_limit_passed")
                .addKeyValue("contactType", contactType)
                .log("Registration rate limit check passed.")

            val result = idempotencyService.execute(
                scope = REGISTER_IDEMPOTENCY_SCOPE,
                ownerUserId = null,
                idempotencyKey = idempotencyKey,
                request = request,
                responseType = VerificationStartResponse::class.java,
                responseStatus = HttpStatus.ACCEPTED.value(),
            ) {
                authService.register(request)
            }

            logger.atInfo()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "controller_completed")
                .addKeyValue("contactType", contactType)
                .addKeyValue("responseStatus", result.responseStatus)
                .log("Registration request completed.")

            return ResponseEntity.status(result.responseStatus).body(result.body)
        } catch (ex: Exception) {
            logger.atError()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "controller_failed")
                .addKeyValue("contactType", contactType)
                .addKeyValue("exception", ex::class.simpleName)
                .log("Registration request failed.", ex)
            throw ex
        }
    }

    @PostMapping("/register/verify")
    fun verifyRegistration(
        @Valid @RequestBody request: ConfirmSignupVerificationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_VERIFY_LOG_EVENT,
            stage = "registration_verify",
            fields = authFields(contactType = request.safeContactType()),
        ) {
            authRateLimitService.checkConfirm(request.verifyIdentifier(), clientIps.resolve(servletRequest))
            ResponseEntity.ok(authService.verifyRegistration(request))
        }
    }

    @PostMapping("/register/resend")
    fun resendRegistrationVerification(
        @Valid @RequestBody request: SignupVerificationResendRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<VerificationStartResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_VERIFY_LOG_EVENT,
            stage = "registration_resend",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            authRateLimitService.checkLogin(
                OtpLoginStartRequest(identifier = request.identifier),
                clientIps.resolve(servletRequest),
            )
            ResponseEntity.ok(authService.resendRegistrationVerification(request))
        }
    }

    @PostMapping("/login/password")
    fun loginWithPassword(
        @Valid @RequestBody request: PasswordLoginRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_PASSWORD_LOGIN_LOG_EVENT,
            stage = "password_login",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            authRateLimitService.checkLogin(request, clientIps.resolve(servletRequest))
            ResponseEntity.ok(authService.loginWithPassword(request))
        }
    }

    @PostMapping("/login/otp/start")
    fun startLoginOtp(
        @Valid @RequestBody request: OtpLoginStartRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<VerificationStartResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_OTP_LOGIN_LOG_EVENT,
            stage = "otp_login_start",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            authRateLimitService.checkLogin(request, clientIps.resolve(servletRequest))
            ResponseEntity.ok(emailVerificationService.startLoginOtp(request))
        }
    }

    @PostMapping("/login/otp/confirm")
    fun confirmLoginOtp(
        @Valid @RequestBody request: OtpLoginConfirmRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_OTP_LOGIN_LOG_EVENT,
            stage = "otp_login_confirm",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            authRateLimitService.checkConfirm(request.identifier, clientIps.resolve(servletRequest))
            ResponseEntity.ok(authService.loginWithOtp(request))
        }
    }

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest,
    ): ResponseEntity<AuthResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_SESSION_LOG_EVENT,
            stage = "refresh",
            fields = authFields(contactType = "token", role = "token"),
        ) {
            ResponseEntity.ok(authService.refresh(request))
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
    ) {
        StageLog.around(
            logger = logger,
            event = AUTH_SESSION_LOG_EVENT,
            stage = "logout",
            fields = authFields(contactType = "token", role = "token"),
        ) {
            authService.logout(request.refreshToken)
        }
    }

    @PostMapping("/password-reset/start")
    fun startPasswordReset(
        @Valid @RequestBody request: StartPasswordResetRequest,
    ): ResponseEntity<VerificationStartResponse> {
        return StageLog.around(
            logger = logger,
            event = AUTH_PASSWORD_RESET_LOG_EVENT,
            stage = "password_reset_start",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            ResponseEntity.ok(resetPasswordService.start(request))
        }
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(
        @Valid @RequestBody request: ConfirmPasswordResetRequest,
        servletRequest: HttpServletRequest,
    ) {
        StageLog.around(
            logger = logger,
            event = AUTH_PASSWORD_RESET_LOG_EVENT,
            stage = "password_reset_confirm",
            fields = authFields(contactType = request.identifier.safeIdentifierContactType()),
        ) {
            authRateLimitService.checkConfirm(request.identifier, clientIps.resolve(servletRequest))
            resetPasswordService.confirm(request)
        }
    }

    private fun authFields(
        contactType: String,
        role: String = "anonymous",
    ): Map<String, Any?> {
        return mapOf(
            "contactType" to contactType,
            "role" to role,
        )
    }

    companion object {
        private const val REGISTER_IDEMPOTENCY_SCOPE = "auth:register"
        private const val REGISTER_LOG_EVENT = "auth_register"
        private const val AUTH_VERIFY_LOG_EVENT = "auth_verify"
        private const val AUTH_PASSWORD_LOGIN_LOG_EVENT = "auth_password_login"
        private const val AUTH_OTP_LOGIN_LOG_EVENT = "auth_otp_login"
        private const val AUTH_PASSWORD_RESET_LOG_EVENT = "auth_password_reset"
        private const val AUTH_SESSION_LOG_EVENT = "auth_session"
        private val logger = LoggerFactory.getLogger(AuthController::class.java)
    }
}

private fun RegisterRequest.safeContactType(): String {
    return when {
        !email.isNullOrBlank() && !phoneNumber.isNullOrBlank() -> "email_and_phone"
        !email.isNullOrBlank() -> "email"
        !phoneNumber.isNullOrBlank() -> "phone"
        else -> "missing"
    }
}

private fun ConfirmSignupVerificationRequest.safeContactType(): String {
    return when {
        !email.isNullOrBlank() -> "email"
        !phoneNumber.isNullOrBlank() -> "phone"
        else -> "missing"
    }
}

private fun ConfirmSignupVerificationRequest.verifyIdentifier(): String {
    return email?.takeIf { it.isNotBlank() } ?: phoneNumber?.takeIf { it.isNotBlank() }.orEmpty()
}

private fun String.safeIdentifierContactType(): String {
    return when {
        isBlank() -> "missing"
        contains("@") -> "email"
        else -> "phone"
    }
}
