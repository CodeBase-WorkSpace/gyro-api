package com.gyro.api.auth.application

import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryGateway
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.web.ConfirmSignupVerificationRequest
import com.gyro.api.auth.web.RegisterRequest
import com.gyro.api.auth.web.VerificationStartResponse
import com.gyro.api.common.error.InvalidVerificationCodeException
import com.gyro.api.common.error.VerificationCodeExpiredException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@Service
class PendingRegistrationService(
    private val redisTemplate: StringRedisTemplate,
    private val verificationDeliveryGateway: VerificationDeliveryGateway,
    private val verificationCodePolicy: VerificationCodePolicy,
    private val verificationAttemptGuard: VerificationAttemptGuard,
    @Value("\${app.auth.verification-code-ttl:10m}")
    private val ttl: Duration,
    @Value("\${app.security.verification-code-pepper:\${app.security.jwt.secret}}")
    private val codePepper: String,
) {
    private val secureRandom = SecureRandom()

    fun start(request: RegisterRequest, passwordHash: String?): VerificationStartResponse {
        val contact = request.toContact()
        val code = generateCode()
        val pending = PendingRegistration(
            email = request.email?.takeIf { it.isNotBlank() }?.let(EmailNormalizer::normalize),
            phoneNumber = request.phoneNumber?.takeIf { it.isNotBlank() }?.let(IranianPhoneValidator::normalize),
            passwordHash = passwordHash,
            codeHash = hashCode(code),
        )
        val key = pendingRegistrationKey(contact)

        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "pending_redis_write_started")
            .addKeyValue("contactType", contact.label)
            .addKeyValue("ttlSeconds", ttl.seconds)
            .log("Writing pending registration to Redis.")
        redisTemplate.boundHashOps<String, String>(key).putAll(pending.toRedisHash())
        redisTemplate.expire(key, ttl)
        redisTemplate.opsForZSet().add(PENDING_REGISTRATION_INDEX_KEY, key, expiresAtEpochMillis())
        verificationAttemptGuard.clear(key)
        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "pending_redis_write_completed")
            .addKeyValue("contactType", contact.label)
            .log("Pending registration written to Redis.")

        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "verification_delivery_requested")
            .addKeyValue("contactType", contact.label)
            .addKeyValue("channel", contact.channel.name)
            .addKeyValue("purpose", VerificationPurpose.SIGNUP.name)
            .log("Requesting signup verification delivery.")
        verificationDeliveryGateway.deliver(
            channel = contact.channel,
            identifier = contact.value,
            purpose = VerificationPurpose.SIGNUP,
            code = code,
        )
        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "verification_delivery_completed")
            .addKeyValue("contactType", contact.label)
            .addKeyValue("channel", contact.channel.name)
            .addKeyValue("purpose", VerificationPurpose.SIGNUP.name)
            .log("Signup verification delivery completed.")

        return VerificationStartResponse(
            message = "Signup verification code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    fun confirm(request: ConfirmSignupVerificationRequest): ConfirmedPendingRegistration {
        val contact = request.toContact()
        val key = pendingRegistrationKey(contact)
        val pendingHash = redisTemplate.boundHashOps<String, String>(key).entries()
        if (pendingHash.isEmpty()) {
            throw VerificationCodeExpiredException()
        }
        val pending = PendingRegistration.fromRedisHash(pendingHash)

        if (!verificationCodePolicy.acceptsBypassCode(request.code)) {
            if (hashCode(request.code.trim()) != pending.codeHash) {
                verificationAttemptGuard.registerFailure(key)
                throw InvalidVerificationCodeException()
            }
        }
        verificationAttemptGuard.clear(key)

        return ConfirmedPendingRegistration(
            registration = pending,
            verifiedContact = contact.label,
            redisKey = key,
        )
    }

    fun resend(identifier: String): VerificationStartResponse {
        val contact = Contact.fromIdentifier(identifier)
        val key = pendingRegistrationKey(contact)
        val pendingHash = redisTemplate.boundHashOps<String, String>(key).entries()
        if (pendingHash.isEmpty()) {
            throw VerificationCodeExpiredException()
        }

        val code = generateCode()
        redisTemplate.boundHashOps<String, String>(key).put(PendingRegistration.CODE_HASH_FIELD, hashCode(code))
        redisTemplate.expire(key, ttl)
        redisTemplate.opsForZSet().add(PENDING_REGISTRATION_INDEX_KEY, key, expiresAtEpochMillis())
        verificationAttemptGuard.clear(key)
        verificationDeliveryGateway.deliver(
            channel = contact.channel,
            identifier = contact.value,
            purpose = VerificationPurpose.SIGNUP,
            code = code,
        )

        return VerificationStartResponse(
            message = "Signup verification code sent.",
            otpExpireInSeconds = ttl.seconds.toInt(),
        )
    }

    fun complete(confirmedRegistration: ConfirmedPendingRegistration) {
        redisTemplate.delete(confirmedRegistration.redisKey)
        redisTemplate.opsForZSet().remove(PENDING_REGISTRATION_INDEX_KEY, confirmedRegistration.redisKey)
    }

    @Scheduled(cron = "\${app.auth.pending-registration-cleanup-cron}")
    fun cleanupExpiredPendingRegistrations() {
        val now = Instant.now().toEpochMilli().toDouble()
        val expiredKeys = redisTemplate.opsForZSet()
            .rangeByScore(PENDING_REGISTRATION_INDEX_KEY, Double.NEGATIVE_INFINITY, now)
            .orEmpty()

        if (expiredKeys.isEmpty()) return

        redisTemplate.delete(expiredKeys)
        redisTemplate.opsForZSet().removeRangeByScore(PENDING_REGISTRATION_INDEX_KEY, Double.NEGATIVE_INFINITY, now)
        logger.info("Cleaned {} expired pending registration record(s).", expiredKeys.size)
    }

    private fun generateCode(): String {
        return secureRandom.nextInt(900_000).plus(100_000).toString()
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$codePepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun expiresAtEpochMillis(): Double {
        return Instant.now().plus(ttl).toEpochMilli().toDouble()
    }

    private fun pendingRegistrationKey(contact: Contact): String {
        return "pending-register:${contact.label}:${contact.value}"
    }

    private fun RegisterRequest.toContact(): Contact {
        return email?.takeIf { it.isNotBlank() }?.let { Contact.email(it) }
            ?: phoneNumber?.takeIf { it.isNotBlank() }?.let { Contact.phone(it) }
            ?: throw VerificationCodeExpiredException()
    }

    private fun ConfirmSignupVerificationRequest.toContact(): Contact {
        return email?.takeIf { it.isNotBlank() }?.let { Contact.email(it) }
            ?: phoneNumber?.takeIf { it.isNotBlank() }?.let { Contact.phone(it) }
            ?: throw VerificationCodeExpiredException()
    }

    data class PendingRegistration(
        val email: String? = null,
        val phoneNumber: String? = null,
        val passwordHash: String? = null,
        val codeHash: String = "",
    ) {
        fun toRedisHash(): Map<String, String> {
            return buildMap {
                email?.let { put(EMAIL_FIELD, it) }
                phoneNumber?.let { put(PHONE_NUMBER_FIELD, it) }
                // Passwordless signups have no hash to persist; only write it when present.
                passwordHash?.let { put(PASSWORD_HASH_FIELD, it) }
                put(CODE_HASH_FIELD, codeHash)
            }
        }

        companion object {
            private const val EMAIL_FIELD = "email"
            private const val PHONE_NUMBER_FIELD = "phoneNumber"
            private const val PASSWORD_HASH_FIELD = "passwordHash"
            const val CODE_HASH_FIELD = "codeHash"

            fun fromRedisHash(hash: Map<String, String>): PendingRegistration {
                return PendingRegistration(
                    email = hash[EMAIL_FIELD],
                    phoneNumber = hash[PHONE_NUMBER_FIELD],
                    passwordHash = hash[PASSWORD_HASH_FIELD],
                    codeHash = hash[CODE_HASH_FIELD].orEmpty(),
                )
            }
        }
    }

    data class ConfirmedPendingRegistration(
        val registration: PendingRegistration,
        val verifiedContact: String,
        val redisKey: String,
    ) {
        val verifiedEmail: Boolean
            get() = verifiedContact == EMAIL_LABEL

        val verifiedPhone: Boolean
            get() = verifiedContact == PHONE_LABEL
    }

    private data class Contact(
        val label: String,
        val value: String,
    ) {
        val channel: VerificationChannel
            get() = if (label == EMAIL_LABEL) VerificationChannel.EMAIL else VerificationChannel.SMS

        companion object {
            fun fromIdentifier(identifier: String): Contact {
                val normalizedIdentifier = NormalizedIdentifier.from(identifier)
                return if (normalizedIdentifier.isEmail) {
                    Contact(EMAIL_LABEL, normalizedIdentifier.value)
                } else {
                    Contact(PHONE_LABEL, normalizedIdentifier.value)
                }
            }

            fun email(email: String): Contact {
                return Contact(EMAIL_LABEL, EmailNormalizer.normalize(email))
            }

            fun phone(phoneNumber: String): Contact {
                return Contact(PHONE_LABEL, IranianPhoneValidator.normalize(phoneNumber))
            }
        }
    }

    companion object {
        private const val EMAIL_LABEL = "email"
        private const val PHONE_LABEL = "phone"
        private const val REGISTER_LOG_EVENT = "auth_register"
        private const val PENDING_REGISTRATION_INDEX_KEY = "pending-register:index"
        private val logger = LoggerFactory.getLogger(PendingRegistrationService::class.java)
    }
}
