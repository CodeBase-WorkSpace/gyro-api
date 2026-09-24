package com.gyro.api.subscription.application.trial

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.TrialAlreadyRedeemedException
import com.gyro.api.common.error.TrialNotAvailableException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.config.TrialProperties
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.domain.ManualGrantReason
import com.gyro.api.subscription.domain.TrialRedemption
import com.gyro.api.subscription.domain.TrialRedemptionSource
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.TrialRedemptionRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TrialStatus(
    val active: Boolean,
    val eligible: Boolean,
    val expiresAt: Instant?,
)

data class RedeemedTrialPeriod(
    val grantedAt: Instant,
    val expiresAt: Instant,
)

/**
 * Self-serve reverse trial: a time-boxed ADVANCED manual grant redeemable
 * once per account and once per normalized contact identifier. New signups
 * are granted automatically (via [TrialSignupListener], backed by
 * [TrialReconciliationJob] for missed events); existing FREE users claim
 * through the billing endpoint. Expiry is passive -- the grant-backed
 * subscription row lapses through the normal lifecycle jobs, which also
 * invalidate the entitlement cache.
 */
@Service
class TrialService(
    private val userRepository: UserRepository,
    private val trialRedemptionRepository: TrialRedemptionRepository,
    private val userSubscriptionRepository: UserSubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val lifecycleService: SubscriptionLifecycleService,
    private val cachedEntitlementService: CachedEntitlementService,
    private val timeProvider: TimeProvider,
    private val properties: TrialProperties,
) {
    @Transactional(readOnly = true)
    fun redeemedTrialPeriod(userId: UUID): RedeemedTrialPeriod? =
        trialRedemptionRepository.findByUserId(userId).orElse(null)?.let {
            RedeemedTrialPeriod(grantedAt = it.grantedAt, expiresAt = it.expiresAt)
        }

    /**
     * Signup hook; runs after the signup transaction commits (see
     * [TrialSignupListener]). REQUIRES_NEW is essential: an AFTER_COMMIT
     * listener still has the completed transaction's resources bound, so a
     * joining transaction would never flush its writes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun grantSignupTrial(userId: UUID, identifier: String?): TrialRedemption {
        if (identifier.isNullOrBlank()) throw TrialNotAvailableException()
        return redeem(userId, identifier, TrialRedemptionSource.SIGNUP)
    }

    @Transactional
    fun claimTrial(userId: UUID, identifier: String?): TrialRedemption {
        if (identifier.isNullOrBlank()) throw TrialNotAvailableException()
        return redeem(userId, identifier, TrialRedemptionSource.EXISTING_USER)
    }

    /** Trial state derived from the already-computed entitlement. */
    fun statusFor(userId: UUID, entitlement: Entitlement): TrialStatus {
        if (entitlement.status == EntitlementStatus.FREE) {
            val eligible = properties.enabled && isEligibleFreeAccount(userId)
            return TrialStatus(active = false, eligible = eligible, expiresAt = null)
        }

        val redemption = trialRedemptionRepository.findByUserId(userId).orElse(null)
            ?: return TrialStatus(active = false, eligible = false, expiresAt = null)

        if (entitlement.status != EntitlementStatus.ACTIVE) {
            return TrialStatus(active = false, eligible = false, expiresAt = redemption.expiresAt)
        }

        // Trial-flavored only while access is active and the entitlement period
        // does not extend past the redemption window. A mid-trial purchase
        // pushes currentPeriodEnd beyond expiresAt and the banner disappears.
        // Deliberately an inequality, not exact timestamp equality, so period
        // normalization or precision differences cannot misclassify an active trial.
        val now = timeProvider.now()
        val active = redemption.expiresAt.isAfter(now) &&
            entitlement.currentPeriodEnd != null &&
            !entitlement.currentPeriodEnd.isAfter(redemption.expiresAt)
        return TrialStatus(active = active, eligible = false, expiresAt = redemption.expiresAt)
    }

    private fun isEligibleFreeAccount(userId: UUID): Boolean {
        val user = userRepository.findById(userId).orElse(null) ?: return false
        val identifier = user.email ?: user.phoneNumber
        if (identifier.isNullOrBlank()) return false

        return !trialRedemptionRepository.existsByUserIdOrIdentifierHash(
            userId,
            hashIdentifier(identifier),
        )
    }

    private fun redeem(userId: UUID, identifier: String, source: TrialRedemptionSource): TrialRedemption {
        if (!properties.enabled) throw TrialNotAvailableException()

        val identifierHash = hashIdentifier(identifier)
        if (trialRedemptionRepository.existsByUserId(userId) ||
            trialRedemptionRepository.existsByIdentifierHash(identifierHash)
        ) {
            throw TrialAlreadyRedeemedException()
        }
        if (userSubscriptionRepository.findByUserId(userId).isPresent) {
            // Any subscription row -- active, lapsed, or a previous grant --
            // means this account is past the trial stage.
            throw TrialNotAvailableException()
        }

        val plan = planRepository.findByCode(properties.planKey)
            ?: throw TrialNotAvailableException()

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = requireNotNull(plan.id),
            durationDays = properties.days,
            reason = ManualGrantReason.TRIAL,
            grantedBy = userId,
            reasonNote = "Self-serve ${properties.days}-day trial ($source)",
        )

        val redemption = try {
            trialRedemptionRepository.save(
                TrialRedemption(
                    userId = userId,
                    identifierHash = identifierHash,
                    manualGrantId = requireNotNull(grant.id),
                    grantedAt = timeProvider.now(),
                    expiresAt = requireNotNull(grant.expiresAt),
                    source = source,
                ),
            )
        } catch (exception: DataIntegrityViolationException) {
            // A concurrent redemption won the unique index; surface it as
            // already-redeemed and let the transaction roll the grant back.
            throw TrialAlreadyRedeemedException()
        }

        // The outbox consumer also invalidates, but asynchronously; evict now
        // so the very next entitlement read reflects the trial.
        cachedEntitlementService.invalidate(userId)
        return redemption
    }

    /**
     * Keyed HMAC-SHA-256 over the normalized identifier. The pepper keeps a
     * leaked table from being reversible by offline enumeration (phone
     * numbers especially); the value is still pseudonymous, not anonymous.
     */
    fun hashIdentifier(identifier: String): String {
        val normalized = identifier.trim().lowercase()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.identifierPepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
