package com.gyro.api.auth.web

import com.gyro.api.auth.application.AccountSecurityService
import com.gyro.api.auth.application.AuthRateLimitService
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.request.TrustedClientIpResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/users/me")
class AccountSecurityController(
    private val accountSecurityService: AccountSecurityService,
    private val authRateLimitService: AuthRateLimitService,
    private val clientIps: TrustedClientIpResolver,
) {

    @PostMapping("/security/step-up/start")
    fun startStepUp(
        @AuthenticationPrincipal userId: String,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<VerificationStartResponse> {
        return StageLog.around(
            logger = logger,
            event = ACCOUNT_SECURITY_LOG_EVENT,
            stage = "step_up_start",
        ) {
            authRateLimitService.checkUserConfirm(userId, clientIps.resolve(servletRequest))
            ResponseEntity.ok(accountSecurityService.startStepUp(UUID.fromString(userId)))
        }
    }

    @PostMapping("/security/step-up/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmStepUp(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: StepUpConfirmRequest,
        servletRequest: HttpServletRequest,
    ) {
        StageLog.around(
            logger = logger,
            event = ACCOUNT_SECURITY_LOG_EVENT,
            stage = "step_up_confirm",
        ) {
            authRateLimitService.checkUserConfirm(userId, clientIps.resolve(servletRequest))
            accountSecurityService.confirmStepUp(UUID.fromString(userId), request.code)
        }
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setPassword(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: SetPasswordRequest,
    ) {
        StageLog.around(
            logger = logger,
            event = ACCOUNT_SECURITY_LOG_EVENT,
            stage = "password_set",
        ) {
            accountSecurityService.setPassword(UUID.fromString(userId), request.newPassword)
        }
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: ChangePasswordRequest,
    ) {
        StageLog.around(
            logger = logger,
            event = ACCOUNT_SECURITY_LOG_EVENT,
            stage = "password_change",
        ) {
            accountSecurityService.changePassword(
                userId = UUID.fromString(userId),
                currentPassword = request.currentPassword,
                newPassword = request.newPassword,
            )
        }
    }

    @DeleteMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removePassword(
        @AuthenticationPrincipal userId: String,
    ) {
        StageLog.around(
            logger = logger,
            event = ACCOUNT_SECURITY_LOG_EVENT,
            stage = "password_remove",
        ) {
            accountSecurityService.removePassword(UUID.fromString(userId))
        }
    }

    companion object {
        private const val ACCOUNT_SECURITY_LOG_EVENT = "account_security"
        private val logger = LoggerFactory.getLogger(AccountSecurityController::class.java)
    }
}
