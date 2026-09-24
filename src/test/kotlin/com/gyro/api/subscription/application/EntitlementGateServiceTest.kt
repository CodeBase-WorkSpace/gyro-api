package com.gyro.api.subscription.application

import com.gyro.api.subscription.config.PremiumGatingProperties
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.infrastructure.SubscriptionFeatureRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

class EntitlementGateServiceTest {
    @Test
    fun `grace users retain backend granted features`() {
        val service = service(PremiumGatingProperties())

        assertTrue(service.hasFeatureAccess(entitlement(EntitlementStatus.GRACE_PERIOD), FEATURE))
    }

    @Test
    fun `expired users cannot use retained feature names`() {
        val service = service(PremiumGatingProperties())

        assertFalse(service.hasFeatureAccess(entitlement(EntitlementStatus.EXPIRED), FEATURE))
    }

    @Test
    fun `global kill switch bypasses status and feature restrictions`() {
        val service = service(PremiumGatingProperties(disabled = true))

        assertTrue(
            service.hasFeatureAccess(
                entitlement(EntitlementStatus.FREE, features = emptySet()),
                FEATURE,
            ),
        )
    }

    @Test
    fun `any feature access recognizes all feature holding statuses`() {
        val service = service(PremiumGatingProperties())

        assertTrue(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.ACTIVE)))
        assertTrue(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.GRACE_PERIOD)))
        assertTrue(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.ADMIN_OVERRIDE)))
    }

    @Test
    fun `any feature access rejects lapsed and featureless entitlements`() {
        val service = service(PremiumGatingProperties())

        assertFalse(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.EXPIRED)))
        assertFalse(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.CANCELED)))
        assertFalse(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.BILLED_BLOCKED)))
        assertFalse(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.ACTIVE, features = emptySet())))
    }

    @Test
    fun `global kill switch counts as retained access for downgrade notices`() {
        val service = service(PremiumGatingProperties(disabled = true))

        assertTrue(service.hasAnyFeatureAccess(entitlement(EntitlementStatus.FREE, features = emptySet())))
    }

    private fun service(properties: PremiumGatingProperties): EntitlementGateService {
        @Suppress("UNCHECKED_CAST")
        val meterProvider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MeterRegistry>
        return EntitlementGateService(
            cachedEntitlementService = Mockito.mock(CachedEntitlementService::class.java),
            subscriptionFeatureRepository = Mockito.mock(SubscriptionFeatureRepository::class.java),
            meterRegistryProvider = meterProvider,
            premiumGatingProperties = properties,
        )
    }

    private fun entitlement(
        status: EntitlementStatus,
        features: Set<String> = setOf(FEATURE),
    ) = Entitlement(
        status = status,
        planKey = "ADVANCED",
        features = features,
        currentPeriodEnd = null,
        gracePeriodEnd = null,
        cancelAtPeriodEnd = false,
        supportReasonCode = null,
    )

    private companion object {
        private const val FEATURE = "future_meal_planning"
    }
}
