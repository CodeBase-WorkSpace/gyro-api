package com.gyro.api.auth.application

import com.gyro.api.auth.web.OtpLoginStartRequest
import com.gyro.api.auth.web.PasswordLoginRequest
import com.gyro.api.auth.web.RegisterRequest
import com.gyro.api.common.ratelimit.RateLimitService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

@Service
class AuthRateLimitService(
    private val rateLimitService: RateLimitService,
    @Value("\${app.rate-limit.login.per-ip-limit}")
    private val loginPerIpLimit: Long,
    @Value("\${app.rate-limit.login.per-identifier-limit}")
    private val loginPerIdentifierLimit: Long,
    @Value("\${app.rate-limit.login.window}")
    private val loginWindow: Duration,
    @Value("\${app.rate-limit.register.per-ip-limit}")
    private val registerPerIpLimit: Long,
    @Value("\${app.rate-limit.register.per-contact-limit}")
    private val registerPerContactLimit: Long,
    @Value("\${app.rate-limit.register.window}")
    private val registerWindow: Duration,
) {
    fun checkRegister(request: RegisterRequest, clientIp: String) {
        rateLimitService.check("rate:auth:register:ip:${clientIp.toRateLimitHash()}", registerPerIpLimit, registerWindow)

        request.primaryContact()?.let { contact ->
            rateLimitService.check(
                "rate:auth:register:contact:${contact.toRateLimitHash()}",
                registerPerContactLimit,
                registerWindow,
            )
        }
    }

    fun checkLogin(request: PasswordLoginRequest, clientIp: String) {
        checkLoginIdentifier(request.identifier, clientIp)
    }

    fun checkLogin(request: OtpLoginStartRequest, clientIp: String) {
        checkLoginIdentifier(request.identifier, clientIp)
    }

    /**
     * Rate limits an OTP-confirm attempt (register verify, OTP login confirm, password-reset
     * confirm, security step-up confirm). Reuses the login IP/identifier buckets so brute-forcing
     * codes is bounded even though each stored code is single-use with a short TTL.
     */
    fun checkConfirm(identifier: String, clientIp: String) {
        checkLoginIdentifier(identifier, clientIp)
    }

    /**
     * Rate limits a confirm attempt scoped to an already-authenticated user (security step-up).
     * The user id is not an email/phone, so it is hashed directly rather than normalized.
     */
    fun checkUserConfirm(userId: String, clientIp: String) {
        rateLimitService.check("rate:auth:login:ip:${clientIp.toRateLimitHash()}", loginPerIpLimit, loginWindow)
        rateLimitService.check(
            "rate:auth:login:user:${userId.toRateLimitHash()}",
            loginPerIdentifierLimit,
            loginWindow,
        )
    }

    private fun checkLoginIdentifier(identifier: String, clientIp: String) {
        val normalizedIdentifier = NormalizedIdentifier.from(identifier).value
        rateLimitService.check("rate:auth:login:ip:${clientIp.toRateLimitHash()}", loginPerIpLimit, loginWindow)
        rateLimitService.check(
            "rate:auth:login:identifier:${normalizedIdentifier.toRateLimitHash()}",
            loginPerIdentifierLimit,
            loginWindow,
        )
    }

    private fun RegisterRequest.primaryContact(): String? {
        return normalizeContact(email, phoneNumber).let { contact ->
            contact.email ?: contact.phoneNumber
        }
    }

    private fun String.toRateLimitHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
