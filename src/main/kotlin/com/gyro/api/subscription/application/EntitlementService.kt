package com.gyro.api.subscription.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.config.PremiumGatingProperties
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.ManualGrantRepository
import com.gyro.api.subscription.infrastructure.PlanFeatureRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

@Service
class EntitlementService(
    private val userSubscriptionRepository: UserSubscriptionRepository,
    private val manualGrantRepository: ManualGrantRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val planFeatureRepository: PlanFeatureRepository,
    private val timeProvider: TimeProvider,
    private val premiumGatingProperties: PremiumGatingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val FREE_PLAN_KEY = "FREE"
    }

    fun compute(userId: UUID): Entitlement {
        val now = timeProvider.now()

        // 1. Check paid subscription first
        val subscription = userSubscriptionRepository.findByUserId(userId).orElse(null)

        // 2. Check active manual grants
        val activeGrants = manualGrantRepository.findActiveByUserId(userId, now)

        // 3. Determine the best source of access
        val subscriptionActive = subscription != null && isSubscriptionActive(subscription, now)
        val grantActive = activeGrants.isNotEmpty()

        return when {
            // Active subscription takes priority
            subscriptionActive -> computeFromSubscription(subscription, now)

            // Active manual grant without subscription
            grantActive -> computeFromGrant(selectBestGrant(activeGrants)!!, now)

            // No active subscription or grant — check if there was a subscription at all
            subscription != null -> computeFromSubscription(subscription, now)

            // No subscription and no active grants — FREE
            else -> freeEntitlement()
        }
    }

    private fun computeFromSubscription(
        subscription: UserSubscription,
        now: java.time.Instant,
    ): Entitlement {
        val plan = planRepository.findById(subscription.planId).orElse(null)
        val planKey = plan?.code ?: FREE_PLAN_KEY
        val status = resolveStatus(subscription, now)

        val features = if (plan != null && status.allowsFeatures()) {
            planFeatureRepository.findActiveFeatureMappingsByPlanId(plan.id!!)
                .filter { it.enabled }
                .map { it.featureKey }
                .toSet()
        } else {
            emptySet()
        }

        return Entitlement(
            status = status,
            planKey = planKey,
            features = features,
            currentPeriodEnd = subscription.periodEnd,
            gracePeriodEnd = subscription.gracePeriodEnd,
            cancelAtPeriodEnd = subscription.cancelAtPeriodEnd,
            supportReasonCode = resolveSupportReasonCode(status, subscription),
            source = EntitlementSource.SUBSCRIPTION,
            premiumGatingDisabled = premiumGatingProperties.disabled,
        )
    }

    private fun computeFromGrant(
        grant: ManualGrant,
        now: java.time.Instant,
    ): Entitlement {
        val plan = planRepository.findById(grant.planId).orElse(null)
        val planKey = plan?.code ?: FREE_PLAN_KEY

        val features = if (plan != null) {
            planFeatureRepository.findActiveFeatureMappingsByPlanId(plan.id!!)
                .filter { it.enabled }
                .map { it.featureKey }
                .toSet()
        } else {
            emptySet()
        }

        return Entitlement(
            status = EntitlementStatus.ACTIVE,
            planKey = planKey,
            features = features,
            currentPeriodEnd = grant.expiresAt,
            gracePeriodEnd = null,
            cancelAtPeriodEnd = false,
            supportReasonCode = null,
            source = EntitlementSource.MANUAL_GRANT,
            premiumGatingDisabled = premiumGatingProperties.disabled,
        )
    }

    private fun freeEntitlement(): Entitlement {
        return Entitlement(
            status = EntitlementStatus.FREE,
            planKey = FREE_PLAN_KEY,
            features = emptySet(),
            currentPeriodEnd = null,
            gracePeriodEnd = null,
            cancelAtPeriodEnd = false,
            supportReasonCode = null,
            source = EntitlementSource.FREE,
            premiumGatingDisabled = premiumGatingProperties.disabled,
        )
    }

    private fun isSubscriptionActive(
        subscription: UserSubscription,
        now: java.time.Instant,
    ): Boolean {
        return when (subscription.status) {
            SubscriptionStatus.ACTIVE -> {
                subscription.periodEnd == null || subscription.periodEnd!!.isAfter(now)
            }

            SubscriptionStatus.GRACE_PERIOD -> {
                subscription.gracePeriodEnd != null && subscription.gracePeriodEnd!!.isAfter(now)
            }
            else -> false
        }
    }

    private fun resolveStatus(
        subscription: UserSubscription,
        now: java.time.Instant,
    ): EntitlementStatus {
        return when (subscription.status) {
            SubscriptionStatus.ACTIVE -> {
                if (subscription.periodEnd != null && !subscription.periodEnd!!.isAfter(now)) {
                    EntitlementStatus.EXPIRED
                } else {
                    EntitlementStatus.ACTIVE
                }
            }

            SubscriptionStatus.GRACE_PERIOD -> {
                if (subscription.gracePeriodEnd != null && !subscription.gracePeriodEnd!!.isAfter(now)) {
                    EntitlementStatus.EXPIRED
                } else {
                    EntitlementStatus.GRACE_PERIOD
                }
            }

            SubscriptionStatus.EXPIRED -> EntitlementStatus.EXPIRED
            SubscriptionStatus.CANCELED -> EntitlementStatus.CANCELED
            SubscriptionStatus.BILLED_BLOCKED -> EntitlementStatus.BILLED_BLOCKED
        }
    }

    private fun resolveSupportReasonCode(
        status: EntitlementStatus,
        subscription: UserSubscription,
    ): String? {
        return when {
            status == EntitlementStatus.BILLED_BLOCKED -> "BILLING_BLOCKED"
            status == EntitlementStatus.GRACE_PERIOD -> "PAYMENT_PAST_DUE"
            status == EntitlementStatus.CANCELED -> "USER_CANCELLED"
            status == EntitlementStatus.EXPIRED && subscription.cancelAtPeriodEnd -> "USER_CANCELLED"
            else -> null
        }
    }

    private fun selectBestGrant(grants: List<ManualGrant>): ManualGrant? {
        return grants.maxWithOrNull(
            compareBy<ManualGrant> { planRank(it.planId) }
                .thenBy { it.expiresAt ?: java.time.Instant.MAX }
                .thenBy { it.createdAt }
                .thenBy { it.id.toString() },
        )
    }

    private fun planRank(planId: Long): Int {
        val plan = planRepository.findById(planId).orElse(null) ?: return 0
        val featureCount = planFeatureRepository.findEnabledByPlanId(planId).size
        return if (plan.free) featureCount else 1_000 + featureCount
    }

    private fun EntitlementStatus.allowsFeatures(): Boolean {
        return this == EntitlementStatus.ACTIVE || this == EntitlementStatus.GRACE_PERIOD
    }
}
