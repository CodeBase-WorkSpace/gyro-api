package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.Entitlement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Computes entitlement with Redis caching.
 * Falls back to direct computation if cache is unavailable.
 */
@Service
class CachedEntitlementService(
    private val entitlementService: EntitlementService,
    private val cacheService: EntitlementCacheService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getEntitlement(userId: UUID): Entitlement {
        // Try cache first
        val cached = cacheService.get(userId)
        if (cached != null) {
            log.debug("Entitlement cache hit for userId={}", userId)
            return cached
        }

        // Compute from source of truth
        log.debug("Entitlement cache miss for userId={}", userId)
        val entitlement = entitlementService.compute(userId)

        // Store in cache (fail-open: don't fail if cache write fails)
        cacheService.put(userId, entitlement)

        return entitlement
    }

    fun invalidate(userId: UUID) {
        cacheService.invalidate(userId)
    }
}
