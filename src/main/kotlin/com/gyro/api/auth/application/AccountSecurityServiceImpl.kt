package com.gyro.api.auth.application

import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryGateway
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.auth.web.VerificationStartResponse
import com.gyro.api.common.error.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.*

@Service
class AccountSecurityServiceImpl(
    private val redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val verificationDeliveryGateway: VerificationDeliveryGateway,
    private val verificationCodePolicy: VerificationCodePolicy,
    private val verificationAttemptGuard: VerificationAttemptGuard,
    private val recentVerificationService: RecentVerificationService,
    private val accountAuditService: AccountAuditService,
    @Value("\${app.auth.verification-code-ttl:10m}")
    private val ttl: Duration,
    @Value("\${app.security.verification-code-pepper:\${app.security.jwt.secret}}")
    private val codePepper: String,
) : AccountSecurityService {
    private val secureRandom = SecureRandom()

    override fun startStepUp(userId: UUID): VerificationStartResponse {
        val user = loadUser(userId)
        val target = verifiedTarget(user) ?: throw AccountNotVerifiedException()

        val code = generateCode()
        val key = stepUpKey(userId)
        redisTemplate.opsForValue().set(key, hashCode(code), ttl)
        verificationAttemptGuard.clear(key)

        // Delivered with the LOGIN template; the elevated-action semantics live in the marker,
        // not the delivery copy, so no dedicated step-up template is required in production.
        verificationDeliveryGateway.deliver(
            channel = target.channel,
            identifier = target.identifier,
            purpose = VerificationPurpose.LOGIN,
            code = code,
        )

        return VerificationStartResponse(
            message = "Verification code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    override fun confirmStepUp(userId: UUID, code: String) {
        val key = stepUpKey(userId)
        val expectedHash = redisTemplate.opsForValue().get(key) ?: throw VerificationCodeExpiredException()

        if (!verificationCodePolicy.acceptsBypassCode(code)) {
            if (hashCode(code.trim()) != expectedHash) {
                verificationAttemptGuard.registerFailure(key)
                throw InvalidVerificationCodeException()
            }
        }

        redisTemplate.delete(key)
        verificationAttemptGuard.clear(key)
        recentVerificationService.markVerified(userId)
    }

    @Transactional
    override fun setPassword(userId: UUID, newPassword: String) {
        val user = loadUser(userId)
        if (user.passwordHash != null) {
            throw PasswordAlreadySetException()
        }
        // Establishing a first password is credential-persisting, so it requires a recent OTP
        // step-up (atomically consumed) rather than a bare session, matching change/remove.
        if (!recentVerificationService.consumeIfPresent(userId)) {
            throw RecentVerificationRequiredException()
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PASSWORD_SET,
        )
    }

    @Transactional
    override fun changePassword(userId: UUID, currentPassword: String?, newPassword: String) {
        val user = loadUser(userId)
        val currentHash = user.passwordHash

        val viaCurrentPassword = when {
            currentPassword != null && currentHash != null &&
                passwordEncoder.matches(currentPassword, currentHash) -> true
            // A supplied-but-wrong current password (or none set) fails the same generic way.
            currentPassword != null -> throw InvalidCredentialsException()
            // Atomically consume the step-up marker so it authorises exactly one change.
            recentVerificationService.consumeIfPresent(userId) -> false
            else -> throw RecentVerificationRequiredException()
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PASSWORD_CHANGED,
            metadata = mapOf("viaStepUp" to !viaCurrentPassword),
        )
    }

    @Transactional
    override fun removePassword(userId: UUID) {
        val user = loadUser(userId)

        // Validate the login-identifier invariant first so a failed removal does not burn the
        // step-up marker, then atomically consume the marker to authorise this single removal.
        if (!hasVerifiedIdentifier(user)) {
            throw NoRemainingLoginIdentifierException()
        }
        if (!recentVerificationService.consumeIfPresent(userId)) {
            throw RecentVerificationRequiredException()
        }

        user.passwordHash = null
        userRepository.save(user)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PASSWORD_REMOVED,
        )
    }

    private fun loadUser(userId: UUID): GyroUser {
        return userRepository.findById(userId).orElseThrow { ResourceNotFoundException("User") }
    }

    private fun hasVerifiedIdentifier(user: GyroUser): Boolean {
        return user.emailVerificationStatus == VerificationStatus.VERIFIED ||
            user.phoneVerificationStatus == VerificationStatus.VERIFIED
    }

    private fun verifiedTarget(user: GyroUser): StepUpTarget? {
        val email = user.email
        if (email != null && user.emailVerificationStatus == VerificationStatus.VERIFIED) {
            return StepUpTarget(VerificationChannel.EMAIL, email)
        }
        val phone = user.phoneNumber
        if (phone != null && user.phoneVerificationStatus == VerificationStatus.VERIFIED) {
            return StepUpTarget(VerificationChannel.SMS, phone)
        }
        return null
    }

    private fun generateCode(): String {
        return secureRandom.nextInt(900_000).plus(100_000).toString()
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$codePepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun stepUpKey(userId: UUID): String = "step-up:$userId"

    private data class StepUpTarget(
        val channel: VerificationChannel,
        val identifier: String,
    )
}
