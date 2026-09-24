package com.gyro.api.subscription.application

import com.gyro.api.common.error.BillingBlockedException
import com.gyro.api.common.error.EntitlementGracePeriodException
import com.gyro.api.common.error.FeatureDisabledException
import com.gyro.api.common.error.SubscriptionExpiredException
import com.gyro.api.common.error.SubscriptionRequiredException
import com.gyro.api.common.observability.StageLog
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.config.PremiumGatingProperties
import com.gyro.api.subscription.infrastructure.SubscriptionFeatureRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EntitlementGateService(
    private val cachedEntitlementService: CachedEntitlementService,
    private val subscriptionFeatureRepository: SubscriptionFeatureRepository,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    private val premiumGatingProperties: PremiumGatingProperties,
) {
    fun entitlementFor(userId: UUID): Entitlement {
        return StageLog.around(
            logger = logger,
            event = ENTITLEMENT_EVENT,
            stage = "calculate",
        ) {
            cachedEntitlementService.getEntitlement(userId)
        }
    }

    fun hasFeatureAccess(userId: UUID, featureKey: String): Boolean {
        return hasFeatureAccess(entitlementFor(userId), featureKey)
    }

    fun hasFeatureAccess(entitlement: Entitlement, featureKey: String): Boolean {
        if (entitlement.premiumGatingDisabled || premiumGatingProperties.disabled) {
            return true
        }
        return entitlement.status in FEATURE_HOLDING_STATUSES && featureKey in entitlement.features
    }

    fun hasAnyFeatureAccess(entitlement: Entitlement): Boolean {
        if (entitlement.premiumGatingDisabled || premiumGatingProperties.disabled) {
            return true
        }
        return entitlement.status in FEATURE_HOLDING_STATUSES && entitlement.features.isNotEmpty()
    }

    fun requireFeature(
        userId: UUID,
        featureKey: String,
        routeTemplate: String,
    ): Entitlement {
        val entitlement = entitlementFor(userId)
        if (premiumGatingProperties.disabled) {
            return entitlement
        }
        if (!subscriptionFeatureRepository.existsByKeyAndActiveTrue(featureKey)) {
            recordDenial(featureKey, "feature_disabled", routeTemplate)
            throw FeatureDisabledException(featureKey)
        }

        if (hasFeatureAccess(entitlement, featureKey)) {
            StageLog.info(
                logger = logger,
                event = ENTITLEMENT_EVENT,
                stage = "premium_gate",
                outcome = "allowed",
                fields = mapOf(
                    "featureKey" to featureKey,
                    "statusClass" to entitlement.status.name,
                    "routeTemplate" to routeTemplate,
                ),
            )
            return entitlement
        }

        deny(featureKey, routeTemplate, entitlement)
    }

    private fun deny(
        featureKey: String,
        routeTemplate: String,
        entitlement: Entitlement,
    ): Nothing {
        val statusClass = entitlement.status.name
        recordDenial(featureKey, statusClass, routeTemplate)
        StageLog.warn(
            logger = logger,
            event = ENTITLEMENT_EVENT,
            stage = "premium_gate",
            outcome = "denied",
            fields = mapOf(
                "featureKey" to featureKey,
                "statusClass" to statusClass,
                "routeTemplate" to routeTemplate,
            ),
        )

        when (entitlement.status) {
            EntitlementStatus.GRACE_PERIOD -> throw EntitlementGracePeriodException(featureKey)
            EntitlementStatus.EXPIRED,
            EntitlementStatus.CANCELED,
            -> throw SubscriptionExpiredException()
            EntitlementStatus.BILLED_BLOCKED -> throw BillingBlockedException()
            EntitlementStatus.FREE,
            EntitlementStatus.ACTIVE,
            EntitlementStatus.ADMIN_OVERRIDE,
            -> throw SubscriptionRequiredException()
        }
    }

    private fun recordDenial(
        featureKey: String,
        statusClass: String,
        routeTemplate: String,
    ) {
        val registry = meterRegistryProvider.ifAvailable ?: return
        Counter.builder("gyro.subscription.entitlement.denials")
            .tag("feature_key", featureKey)
            .tag("status_class", statusClass)
            .tag("route_template", routeTemplate)
            .register(registry)
            .increment()
    }

    private companion object {
        private const val ENTITLEMENT_EVENT = "subscription_entitlement"
        private val FEATURE_HOLDING_STATUSES = setOf(
            EntitlementStatus.ACTIVE,
            EntitlementStatus.GRACE_PERIOD,
            EntitlementStatus.ADMIN_OVERRIDE,
        )
        private val logger = LoggerFactory.getLogger(EntitlementGateService::class.java)
    }
}
