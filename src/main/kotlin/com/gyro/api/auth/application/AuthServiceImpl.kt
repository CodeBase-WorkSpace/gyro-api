package com.gyro.api.auth.application

import com.gyro.api.auth.domain.*
import com.gyro.api.auth.infrastructure.RefreshTokenRepository
import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.auth.web.*
import com.gyro.api.common.error.*
import com.gyro.api.common.observability.StageLog
import com.gyro.api.common.security.JwtService
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.domain.UserProfile
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
import java.util.*

@Service
class AuthServiceImpl(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val emailVerificationService: EmailVerificationService,
    private val pendingRegistrationService: PendingRegistrationService,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val timeProvider: TimeProvider,
    private val accountAuditService: AccountAuditService,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${app.auth.refresh-token-rotation-grace:30s}")
    private val refreshTokenRotationGrace: Duration,
) : AuthService {

    @Transactional
    override fun register(request: RegisterRequest): VerificationStartResponse {
        val contact = normalizeContact(request.email, request.phoneNumber)
        val email = contact.email
        val phoneNumber = contact.phoneNumber
        val contactType = contact.type

        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "service_started")
            .addKeyValue("contactType", contactType)
            .log("Registration service started.")

        if (email != null) {
            logger.atInfo()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "email_user_lookup_started")
                .addKeyValue("contactType", contactType)
                .log("Looking up existing email registration user.")
            val existingUser = userRepository.findByEmail(email)
            if (existingUser?.emailVerificationStatus == VerificationStatus.VERIFIED) {
                logger.atWarn()
                    .addKeyValue("event", REGISTER_LOG_EVENT)
                    .addKeyValue("stage", "duplicate_email_detected")
                    .addKeyValue("contactType", contactType)
                    .log("Registration rejected because email is already verified.")
                throw EmailAlreadyRegisteredException()
            }

            val existingPhoneUser = phoneNumber?.let(userRepository::findByPhoneNumber)
            if (existingPhoneUser != null && existingPhoneUser.id != existingUser?.id) {
                logger.atWarn()
                    .addKeyValue("event", REGISTER_LOG_EVENT)
                    .addKeyValue("stage", "duplicate_phone_detected")
                    .addKeyValue("contactType", contactType)
                    .log("Registration rejected because phone belongs to another user.")
                throw PhoneAlreadyRegisteredException()
            }

            existingUser ?: userRepository.save(
                GyroUser(
                    email = email,
                    phoneNumber = phoneNumber,
                    passwordHash = null,
                    emailVerificationStatus = VerificationStatus.UNVERIFIED,
                    phoneVerificationStatus = VerificationStatus.UNVERIFIED,
                )
            ).also { savedUser ->
                userProfileRepository.save(
                    UserProfile(
                        user = savedUser,
                        timezone = userPreferencesProperties.normalizedDefaultTimezone,
                        locale = userPreferencesProperties.normalizedDefaultLocale,
                    )
                )
            }

            if (existingUser != null) {
                // Reuse the existing unverified registration row and resend a fresh code.
                // Signup never sets a password, so any existing password stays untouched.
                logger.atInfo()
                    .addKeyValue("event", REGISTER_LOG_EVENT)
                    .addKeyValue("stage", "existing_unverified_email_user_reused")
                    .addKeyValue("contactType", contactType)
                    .log("Reused existing unverified email registration user.")
            } else {
                logger.atInfo()
                    .addKeyValue("event", REGISTER_LOG_EVENT)
                    .addKeyValue("stage", "unverified_email_user_created")
                    .addKeyValue("contactType", contactType)
                    .log("Created unverified email registration user.")
            }

            val response = emailVerificationService.startSignupEmail(email)
            logger.atInfo()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "email_verification_started")
                .addKeyValue("contactType", contactType)
                .log("Email signup verification started.")
            return response
        }

        if (phoneNumber != null && userRepository.existsByPhoneNumber(phoneNumber)) {
            logger.atWarn()
                .addKeyValue("event", REGISTER_LOG_EVENT)
                .addKeyValue("stage", "duplicate_phone_detected")
                .addKeyValue("contactType", contactType)
                .log("Registration rejected because phone is already verified.")
            throw PhoneAlreadyRegisteredException()
        }

        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "pending_registration_start_requested")
            .addKeyValue("contactType", contactType)
            .log("Starting pending phone registration.")
        val response = pendingRegistrationService.start(
            request = request.copy(email = email, phoneNumber = phoneNumber),
            passwordHash = null,
        )
        logger.atInfo()
            .addKeyValue("event", REGISTER_LOG_EVENT)
            .addKeyValue("stage", "pending_registration_started")
            .addKeyValue("contactType", contactType)
            .log("Pending phone registration started.")
        return response
    }

    @Transactional
    override fun verifyRegistration(request: ConfirmSignupVerificationRequest): AuthResponse {
        if (!request.email.isNullOrBlank()) {
            val user = emailVerificationService.confirmSignupEmail(request)
            recordOtpSignup(user, contactType = "email")
            publishUserRegistered(user)
            return issueTokens(user)
        }

        val confirmedRegistration = pendingRegistrationService.confirm(request)
        val pendingRegistration = confirmedRegistration.registration
        pendingRegistration.email?.let { email ->
            if (userRepository.existsByEmail(email)) {
                throw EmailAlreadyRegisteredException()
            }
        }
        pendingRegistration.phoneNumber?.let { phoneNumber ->
            if (userRepository.existsByPhoneNumber(phoneNumber)) {
                throw PhoneAlreadyRegisteredException()
            }
        }

        val user = userRepository.save(
            GyroUser(
                email = pendingRegistration.email,
                phoneNumber = pendingRegistration.phoneNumber,
                passwordHash = pendingRegistration.passwordHash,
                emailVerificationStatus = if (confirmedRegistration.verifiedEmail) {
                    VerificationStatus.VERIFIED
                } else {
                    VerificationStatus.UNVERIFIED
                },
                phoneVerificationStatus = if (confirmedRegistration.verifiedPhone) {
                    VerificationStatus.VERIFIED
                } else {
                    VerificationStatus.UNVERIFIED
                },
            )
        )
        userProfileRepository.save(
            UserProfile(
                user = user,
                timezone = userPreferencesProperties.normalizedDefaultTimezone,
                locale = userPreferencesProperties.normalizedDefaultLocale,
            )
        )
        pendingRegistrationService.complete(confirmedRegistration)
        recordOtpSignup(user, contactType = if (confirmedRegistration.verifiedEmail) "email" else "phone")
        publishUserRegistered(user)
        return issueTokens(user)
    }

    private fun publishUserRegistered(user: GyroUser) {
        eventPublisher.publishEvent(
            UserRegisteredEvent(
                userId = requireNotNull(user.id),
                email = user.email,
                phoneNumber = user.phoneNumber,
            ),
        )
    }

    override fun resendRegistrationVerification(request: SignupVerificationResendRequest): VerificationStartResponse {
        val identifier = NormalizedIdentifier.from(request.identifier)
        return if (identifier.isEmail) {
            emailVerificationService.startSignupEmail(identifier.value)
        } else {
            pendingRegistrationService.resend(identifier.value)
        }
    }

    @Transactional
    override fun loginWithPassword(request: PasswordLoginRequest): AuthResponse {
        val user = findUserByIdentifier(request.identifier)

        // A passwordless account (created via OTP signup) has no hash to match. Fail with the
        // same generic error as an unknown user or a wrong password so password login never
        // reveals whether an account exists or whether it has a password set.
        val passwordHash = user?.passwordHash
        if (user == null || passwordHash == null || !passwordEncoder.matches(request.password, passwordHash)) {
            throw InvalidCredentialsException()
        }

        ensureEnabled(user)
        ensureVerifiedForIdentifier(user, request.identifier)

        return issueTokens(user)
    }

    @Transactional
    override fun loginWithOtp(request: OtpLoginConfirmRequest): AuthResponse {
        val user = emailVerificationService.confirmLoginOtp(request)
        ensureEnabled(user)
        ensureVerifiedForIdentifier(user, request.identifier)
        val userId = requireNotNull(user.id)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.OTP_LOGIN,
            metadata = mapOf("contactType" to NormalizedIdentifier.from(request.identifier).publicType()),
        )
        return issueTokens(user)
    }

    @Transactional
    override fun refresh(request: RefreshTokenRequest): AuthResponse {
        if (!jwtService.validateRefreshToken(request.refreshToken)) {
            throw InvalidRefreshTokenException()
        }

        val token = refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken))
            ?: throw InvalidRefreshTokenException()

        val now = timeProvider.now()
        val isWithinRotationGrace = token.rotationGraceExpiresAt?.let { !it.isBefore(now) } == true
        if (token.expiresAt.isBefore(now) || (token.revokedAt != null && !isWithinRotationGrace)) {
            throw InvalidRefreshTokenException()
        }

        if (token.revokedAt == null) {
            token.revokedAt = now
            token.rotationGraceExpiresAt = now.plus(refreshTokenRotationGrace)
        }

        ensureEnabled(token.user)

        return issueTokens(token.user, token.familyId)
    }

    @Transactional
    override fun logout(refreshToken: String) {
        refreshTokenRepository.findByTokenHash(hashToken(refreshToken))?.let { token ->
            val userId = requireNotNull(token.user.id)
            val revokedSessionCount = refreshTokenRepository.revokeAllByFamilyId(
                familyId = token.familyId,
                revokedAt = timeProvider.now(),
            )
            accountAuditService.record(
                actorUserId = userId,
                targetUserId = userId,
                eventType = AccountAuditEventType.LOGOUT,
                metadata = mapOf("sessionRevoked" to (revokedSessionCount > 0)),
            )
        }
    }

    private fun issueTokens(user: GyroUser, familyId: UUID = UUID.randomUUID()): AuthResponse {
        val userId = requireNotNull(user.id).toString()
        val accessToken = jwtService.generateAccessToken(userId, user.role)
        val refreshToken = jwtService.generateRefreshToken(userId)

        refreshTokenRepository.save(
            RefreshToken(
                user = user,
                tokenHash = hashToken(refreshToken),
                familyId = familyId,
                expiresAt = timeProvider.now().plus(jwtService.refreshTokenExpiration),
            )
        )

        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessExpiresInSeconds = jwtService.accessTokenValidityMs / 1000,
        )
    }

    private fun recordOtpSignup(user: GyroUser, contactType: String) {
        val userId = requireNotNull(user.id)
        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.OTP_SIGNUP,
            metadata = mapOf("contactType" to contactType),
        )
    }

    private fun ensureEnabled(user: GyroUser) {
        if (user.status == UserStatus.DISABLED || user.status == UserStatus.DEACTIVATED) {
            StageLog.warn(
                logger = logger,
                event = ACCOUNT_STATE_LOG_EVENT,
                stage = "enabled_check",
                outcome = "rejected",
                fields = mapOf(
                    "role" to user.role.name,
                    "accountStatus" to user.status.name,
                    "errorCode" to ApiErrorCode.ACCOUNT_DISABLED.name,
                ),
            )
            throw AccountDisabledException()
        }
    }

    private fun ensureVerifiedForIdentifier(user: GyroUser, identifier: String) {
        val normalizedIdentifier = NormalizedIdentifier.from(identifier)
        val verified = if (normalizedIdentifier.isEmail) {
            user.emailVerificationStatus == VerificationStatus.VERIFIED
        } else {
            user.phoneVerificationStatus == VerificationStatus.VERIFIED
        }

        if (!verified) {
            StageLog.warn(
                logger = logger,
                event = ACCOUNT_STATE_LOG_EVENT,
                stage = "verification_check",
                outcome = "rejected",
                fields = mapOf(
                    "contactType" to normalizedIdentifier.publicType(),
                    "role" to user.role.name,
                    "errorCode" to ApiErrorCode.ACCOUNT_NOT_VERIFIED.name,
                ),
            )
            throw AccountNotVerifiedException()
        }
    }

    private fun findUserByIdentifier(identifier: String): GyroUser? {
        val normalizedIdentifier = NormalizedIdentifier.from(identifier)

        return if (normalizedIdentifier.isEmail) {
            userRepository.findByEmail(normalizedIdentifier.value)
        } else {
            userRepository.findByPhoneNumber(normalizedIdentifier.value)
        }
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private companion object {
        private const val REGISTER_LOG_EVENT = "auth_register"
        private const val ACCOUNT_STATE_LOG_EVENT = "account_state"
        private val logger = LoggerFactory.getLogger(AuthServiceImpl::class.java)
    }
}
