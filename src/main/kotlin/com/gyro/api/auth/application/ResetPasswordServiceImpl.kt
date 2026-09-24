package com.gyro.api.auth.application

import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.web.ConfirmPasswordResetRequest
import com.gyro.api.auth.web.StartPasswordResetRequest
import com.gyro.api.auth.web.VerificationStartResponse
import com.gyro.api.auth.infrastructure.RefreshTokenRepository
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.auth.application.ResetPasswordService
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryGateway
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.common.error.InvalidVerificationCodeException
import com.gyro.api.common.error.VerificationCodeExpiredException
import com.gyro.api.common.time.TimeProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@Service
class ResetPasswordServiceImpl(
    private val redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val verificationDeliveryGateway: VerificationDeliveryGateway,
    private val verificationCodePolicy: VerificationCodePolicy,
    private val verificationAttemptGuard: VerificationAttemptGuard,
    private val timeProvider: TimeProvider,
    private val accountAuditService: AccountAuditService,
    @Value("\${app.auth.password-reset-code-ttl:10m}")
    private val ttl: Duration,
    @Value("\${app.security.verification-code-pepper:\${app.security.jwt.secret}}")
    private val codePepper: String,
) : ResetPasswordService {
    private val secureRandom = SecureRandom()

    override fun start(request: StartPasswordResetRequest): VerificationStartResponse {
        val normalizedIdentifier = NormalizedIdentifier.from(request.identifier)
        val user = findUserByIdentifier(normalizedIdentifier)

        if (user != null) {
            val code = generateCode()
            val key = resetPasswordKey(normalizedIdentifier.value)
            redisTemplate.opsForValue().set(key, hashCode(code), ttl)
            verificationAttemptGuard.clear(key)
            sendResetCode(user, normalizedIdentifier, code)
            accountAuditService.record(
                actorUserId = requireNotNull(user.id),
                targetUserId = requireNotNull(user.id),
                eventType = AccountAuditEventType.PASSWORD_RESET_REQUESTED,
                metadata = mapOf("identifierType" to normalizedIdentifier.publicType()),
            )
        }

        return VerificationStartResponse(
            message = "If an account exists, reset instructions have been sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    @Transactional
    override fun confirm(request: ConfirmPasswordResetRequest) {
        val normalizedIdentifier = NormalizedIdentifier.from(request.identifier)
        val user = findUserByIdentifier(normalizedIdentifier) ?: throw InvalidVerificationCodeException()
        validateCode(resetPasswordKey(normalizedIdentifier.value), request.code)

        user.passwordHash = requireNotNull(passwordEncoder.encode(request.newPassword))
        userRepository.save(user)

        val now = timeProvider.now()
        val activeTokens = refreshTokenRepository.findAllByUserAndRevokedAtIsNull(user)
        activeTokens.forEach { token ->
            token.revokedAt = now
        }

        redisTemplate.delete(resetPasswordKey(normalizedIdentifier.value))
        val userId = requireNotNull(user.id)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PASSWORD_RESET_COMPLETED,
            metadata = mapOf(
                "identifierType" to normalizedIdentifier.publicType(),
                "revokedSessionCount" to activeTokens.size,
            ),
        )
    }

    private fun sendResetCode(user: GyroUser, normalizedIdentifier: NormalizedIdentifier, code: String) {
        if (normalizedIdentifier.isEmail) {
            val email = user.email ?: return
            verificationDeliveryGateway.deliver(
                channel = VerificationChannel.EMAIL,
                identifier = email,
                purpose = VerificationPurpose.PASSWORD_RESET,
                code = code,
            )
        } else {
            val phoneNumber = user.phoneNumber ?: return
            verificationDeliveryGateway.deliver(
                channel = VerificationChannel.SMS,
                identifier = phoneNumber,
                purpose = VerificationPurpose.PASSWORD_RESET,
                code = code,
            )
        }
    }

    private fun validateCode(key: String, rawCode: String) {
        val expectedHash = redisTemplate.opsForValue().get(key) ?: throw VerificationCodeExpiredException()
        if (verificationCodePolicy.acceptsBypassCode(rawCode)) {
            verificationAttemptGuard.clear(key)
            return
        }

        if (hashCode(rawCode.trim()) != expectedHash) {
            verificationAttemptGuard.registerFailure(key)
            throw InvalidVerificationCodeException()
        }
        verificationAttemptGuard.clear(key)
    }

    private fun findUserByIdentifier(identifier: NormalizedIdentifier): GyroUser? {
        return if (identifier.isEmail) {
            userRepository.findByEmail(identifier.value)
        } else {
            userRepository.findByPhoneNumber(identifier.value)
        }
    }

    private fun generateCode(): String {
        return secureRandom.nextInt(900_000).plus(100_000).toString()
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$codePepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun resetPasswordKey(identifier: String): String {
        return "password-reset:$identifier"
    }

}
