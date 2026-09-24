package com.gyro.api.auth.web

import com.gyro.api.auth.application.AccountSecurityService
import com.gyro.api.auth.application.AuthRateLimitService
import com.gyro.api.auth.application.AuthService
import com.gyro.api.auth.application.EmailVerificationService
import com.gyro.api.auth.application.ResetPasswordService
import com.gyro.api.common.idempotency.service.IdempotencyService
import com.gyro.api.common.request.ClientIpProperties
import com.gyro.api.common.request.TrustedClientIpResolver
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

class AuthClientIpRateLimitTest {
    private val rateLimits = Mockito.mock(AuthRateLimitService::class.java)
    private val authService = Mockito.mock(AuthService::class.java)
    private val accountSecurityService = Mockito.mock(AccountSecurityService::class.java)
    private val clientIps = TrustedClientIpResolver(ClientIpProperties(listOf("172.16.0.0/12")))
    private val authController = AuthController(
        authService,
        Mockito.mock(EmailVerificationService::class.java),
        Mockito.mock(ResetPasswordService::class.java),
        rateLimits,
        Mockito.mock(IdempotencyService::class.java),
        clientIps,
    )
    private val accountSecurityController = AccountSecurityController(accountSecurityService, rateLimits, clientIps)

    @Test
    fun `password login rate limit ignores spoofed forwarding headers from a direct caller`() {
        val login = PasswordLoginRequest("person@example.com", "irrelevant")

        authController.loginWithPassword(login, requestFrom("198.51.100.20"))

        Mockito.verify(rateLimits).checkLogin(login, "198.51.100.20")
    }

    @Test
    fun `password login rate limit uses overwritten client address from a trusted proxy`() {
        val login = PasswordLoginRequest("person@example.com", "irrelevant")

        authController.loginWithPassword(login, requestFrom("172.20.0.4"))

        Mockito.verify(rateLimits).checkLogin(login, "203.0.113.12")
    }

    @Test
    fun `step up rate limit ignores spoofed forwarding headers from a direct caller`() {
        val userId = UUID.randomUUID().toString()

        accountSecurityController.confirmStepUp(userId, StepUpConfirmRequest("123456"), requestFrom("198.51.100.20"))

        Mockito.verify(rateLimits).checkUserConfirm(userId, "198.51.100.20")
    }

    @Test
    fun `step up rate limit uses overwritten client address from a trusted proxy`() {
        val userId = UUID.randomUUID().toString()

        accountSecurityController.confirmStepUp(userId, StepUpConfirmRequest("123456"), requestFrom("172.20.0.4"))

        Mockito.verify(rateLimits).checkUserConfirm(userId, "203.0.113.12")
    }

    private fun requestFrom(peer: String) = MockHttpServletRequest().apply {
        remoteAddr = peer
        addHeader("X-Forwarded-For", "203.0.113.99")
        addHeader(TrustedClientIpResolver.CLIENT_IP_HEADER, "203.0.113.12")
    }
}
