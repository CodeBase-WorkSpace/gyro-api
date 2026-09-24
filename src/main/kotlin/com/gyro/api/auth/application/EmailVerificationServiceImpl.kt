package com.gyro.api.auth.application

import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryGateway
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.domain.VerificationStatus
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.auth.web.*
import com.gyro.api.common.error.AccountAlreadyVerifiedException
import com.gyro.api.common.error.InvalidVerificationCodeException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.error.VerificationCodeExpiredException
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@Service
class EmailVerificationServiceImpl(
    private val redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val verificationDeliveryGateway: VerificationDeliveryGateway,
    private val verificationCodePolicy: VerificationCodePolicy,
    private val verificationAttemptGuard: VerificationAttemptGuard,
    @Value("\${app.auth.verification-code-ttl:10m}")
    private val ttl: Duration,
    @Value("\${app.security.verification-code-pepper:\${app.security.jwt.secret}}")
    private val codePepper: String,
) : EmailVerificationService {
    private val secureRandom = SecureRandom()

    override fun startLoginOtp(request: OtpLoginStartRequest): VerificationStartResponse {
        val contact = Contact.fromIdentifier(request.identifier)
        val user = findUserByContactOrNull(contact)

        // Enumeration-safe: always return the same response and only actually send a code when
        // the account exists and the requested identifier is verified. A missing or unverified
        // account is indistinguishable to the caller from a valid one that just received a code.
        val identifierVerified = when {
            user == null -> false
            contact.isEmail -> user.emailVerificationStatus == VerificationStatus.VERIFIED
            else -> user.phoneVerificationStatus == VerificationStatus.VERIFIED
        }

        if (identifierVerified) {
            sendCode(
                contact = contact,
                key = loginOtpKey(contact),
                purpose = VerificationPurpose.LOGIN,
            )
        }

        return VerificationStartResponse(
            message = "Login code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    override fun confirmLoginOtp(request: OtpLoginConfirmRequest): GyroUser {
        val contact = Contact.fromIdentifier(request.identifier)
        validateLoginCode(loginOtpKey(contact), request.code)

        // A code is only ever stored for an existing verified identifier, so the lookup succeeds
        // here; the defensive branch keeps the failure enumeration-safe if the row disappeared.
        val user = findUserByContactOrNull(contact) ?: throw InvalidVerificationCodeException()
        redisTemplate.delete(loginOtpKey(contact))
        return user
    }

    override fun start(request: StartEmailVerificationRequest): VerificationStartResponse {
        val email = EmailNormalizer.normalize(request.email)
        val user = userRepository.findByEmail(email) ?: throw ResourceNotFoundException("User")

        if (user.emailVerificationStatus == VerificationStatus.VERIFIED) {
            throw AccountAlreadyVerifiedException()
        }

        sendStoredEmailCode(
            contact = Contact.email(email),
            key = emailVerificationKey(email),
            purpose = VerificationPurpose.CHANGE_IDENTIFIER,
        )

        return VerificationStartResponse(
            message = "Verification code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    @Transactional
    override fun confirm(request: ConfirmEmailVerificationRequest) {
        val email = EmailNormalizer.normalize(request.email)
        validateStoredEmailCode(emailVerificationKey(email), request.code)

        val user = userRepository.findByEmail(email) ?: throw ResourceNotFoundException("User")
        user.emailVerificationStatus = VerificationStatus.VERIFIED
        userRepository.save(user)

        redisTemplate.delete(emailVerificationKey(email))
    }

    override fun startSignupEmail(email: String): VerificationStartResponse {
        val normalizedEmail = EmailNormalizer.normalize(email)
        val user = userRepository.findByEmail(normalizedEmail) ?: throw ResourceNotFoundException("User")

        if (user.emailVerificationStatus == VerificationStatus.VERIFIED) {
            throw AccountAlreadyVerifiedException()
        }

        sendStoredEmailCode(
            contact = Contact.email(normalizedEmail),
            key = emailVerificationKey(normalizedEmail),
            purpose = VerificationPurpose.SIGNUP,
        )

        return VerificationStartResponse(
            message = "Signup verification code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    @Transactional
    override fun confirmSignupEmail(request: ConfirmSignupVerificationRequest): GyroUser {
        val email = request.email?.let(EmailNormalizer::normalize) ?: throw VerificationCodeExpiredException()
        validateStoredEmailCode(emailVerificationKey(email), request.code)

        val user = userRepository.findByEmail(email) ?: throw ResourceNotFoundException("User")
        user.emailVerificationStatus = VerificationStatus.VERIFIED
        val savedUser = userRepository.save(user)

        redisTemplate.delete(emailVerificationKey(email))
        return savedUser
    }

    override fun startPhone(request: StartPhoneVerificationRequest): VerificationStartResponse {
        val phoneNumber = IranianPhoneValidator.normalize(request.phoneNumber)
        val user = userRepository.findByPhoneNumber(phoneNumber) ?: throw ResourceNotFoundException("User")

        if (user.phoneVerificationStatus == VerificationStatus.VERIFIED) {
            throw AccountAlreadyVerifiedException()
        }

        sendCode(
            contact = Contact.phone(phoneNumber),
            key = phoneVerificationKey(phoneNumber),
            purpose = VerificationPurpose.CHANGE_IDENTIFIER,
        )

        return VerificationStartResponse(
            message = "Phone verification code generated.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    @Transactional
    override fun confirmPhone(request: ConfirmPhoneVerificationRequest) {
        val phoneNumber = IranianPhoneValidator.normalize(request.phoneNumber)
        validateCode(phoneVerificationKey(phoneNumber), request.code)

        val user = userRepository.findByPhoneNumber(phoneNumber) ?: throw ResourceNotFoundException("User")
        user.phoneVerificationStatus = VerificationStatus.VERIFIED
        userRepository.save(user)

        redisTemplate.delete(phoneVerificationKey(phoneNumber))
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

    /**
     * Login-OTP validation that is enumeration-safe on failure: a missing code (unknown or
     * unverified identifier, or expired/consumed code) and a wrong code both surface the same
     * [InvalidVerificationCodeException], so confirmation cannot distinguish a real verified
     * account from a nonexistent one. The bypass code only applies when a code was actually issued.
     */
    private fun validateLoginCode(key: String, rawCode: String) {
        val expectedHash = redisTemplate.opsForValue().get(key)
        if (expectedHash != null && verificationCodePolicy.acceptsBypassCode(rawCode)) {
            verificationAttemptGuard.clear(key)
            return
        }

        if (expectedHash == null || hashCode(rawCode.trim()) != expectedHash) {
            verificationAttemptGuard.registerFailure(key)
            throw InvalidVerificationCodeException()
        }
        verificationAttemptGuard.clear(key)
    }

    private fun validateStoredEmailCode(key: String, rawCode: String) {
        val stored = redisTemplate.boundHashOps<String, String>(key).entries()
        if (stored.isEmpty()) {
            throw VerificationCodeExpiredException()
        }

        val expiresAt = stored[EXPIRES_AT_FIELD]
            ?.let(Instant::parse)
            ?: throw VerificationCodeExpiredException()
        if (expiresAt.isBefore(Instant.now())) {
            redisTemplate.delete(key)
            verificationAttemptGuard.clear(key)
            throw VerificationCodeExpiredException()
        }

        val expectedHash = stored[CODE_HASH_FIELD] ?: throw VerificationCodeExpiredException()
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

    private fun sendCode(contact: Contact, key: String, purpose: VerificationPurpose) {
        val code = generateCode()
        redisTemplate.opsForValue().set(key, hashCode(code), ttl)
        verificationAttemptGuard.clear(key)

        verificationDeliveryGateway.deliver(
            channel = contact.channel,
            identifier = contact.value,
            purpose = purpose,
            code = code,
        )
    }

    private fun sendStoredEmailCode(contact: Contact, key: String, purpose: VerificationPurpose) {
        val code = generateCode()
        val expiresAt = Instant.now().plus(ttl)

        redisTemplate.boundHashOps<String, String>(key).putAll(
            mapOf(
                CODE_HASH_FIELD to hashCode(code),
                EXPIRES_AT_FIELD to expiresAt.toString(),
            )
        )
        redisTemplate.expire(key, ttl)
        verificationAttemptGuard.clear(key)

        verificationDeliveryGateway.deliver(
            channel = contact.channel,
            identifier = contact.value,
            purpose = purpose,
            code = code,
        )
    }

    private fun findUserByContact(contact: Contact): GyroUser {
        return findUserByContactOrNull(contact) ?: throw ResourceNotFoundException("User")
    }

    private fun findUserByContactOrNull(contact: Contact): GyroUser? {
        return if (contact.isEmail) {
            userRepository.findByEmail(contact.value)
        } else {
            userRepository.findByPhoneNumber(contact.value)
        }
    }

    private fun generateCode(): String {
        return secureRandom.nextInt(900_000).plus(100_000).toString()
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$codePepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun emailVerificationKey(email: String): String {
        return "verify:email:$email"
    }

    private fun phoneVerificationKey(phoneNumber: String): String {
        return "verify:phone:$phoneNumber"
    }

    private fun loginOtpKey(contact: Contact): String {
        return "otp:login:${contact.label}:${contact.value}"
    }

    private data class Contact(
        val label: String,
        val value: String,
    ) {
        val isEmail: Boolean
            get() = label == EMAIL_LABEL

        val channel: VerificationChannel
            get() = if (isEmail) VerificationChannel.EMAIL else VerificationChannel.SMS

        companion object {
            private const val EMAIL_LABEL = "email"
            private const val PHONE_LABEL = "phone"

            fun email(email: String): Contact {
                return Contact(EMAIL_LABEL, EmailNormalizer.normalize(email))
            }

            fun phone(phoneNumber: String): Contact {
                return Contact(PHONE_LABEL, IranianPhoneValidator.normalize(phoneNumber))
            }

            fun fromIdentifier(identifier: String): Contact {
                val normalizedIdentifier = NormalizedIdentifier.from(identifier)
                return if (normalizedIdentifier.isEmail) {
                    Contact(EMAIL_LABEL, normalizedIdentifier.value)
                } else {
                    Contact(PHONE_LABEL, normalizedIdentifier.value)
                }
            }
        }
    }

    private companion object {
        private const val CODE_HASH_FIELD = "codeHash"
        private const val EXPIRES_AT_FIELD = "expiresAt"
    }
}
