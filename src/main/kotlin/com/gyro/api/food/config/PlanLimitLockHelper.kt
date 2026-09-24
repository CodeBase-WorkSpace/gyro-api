package com.gyro.api.food.config

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.*

/**
 * Acquires per-user advisory locks to serialize plan-limit-enforced creations
 * (custom foods, custom meals) and prevent concurrent count-then-insert race conditions.
 *
 * Uses PostgreSQL transaction-scoped advisory locks (`pg_advisory_xact_lock`)
 * so the lock is automatically released when the surrounding @Transactional completes,
 * whether by commit or rollback. No explicit unlock is needed.
 *
 * Each limit type uses a unique "class" id so a user's food creation does not block
 * their meal creation, and vice versa.
 */
@Component
class PlanLimitLockHelper(
    private val dsl: DSLContext,
) {
    fun lockCustomFoodCreation(ownerUserId: UUID) {
        acquireAdvisoryLock(LOCK_CLASS_CUSTOM_FOOD, ownerUserId)
    }

    fun lockCustomMealCreation(ownerUserId: UUID) {
        acquireAdvisoryLock(LOCK_CLASS_CUSTOM_MEAL, ownerUserId)
    }

    private fun acquireAdvisoryLock(lockClass: Int, ownerUserId: UUID) {
        // pg_advisory_xact_lock(int, int) — two-int32 key space.
        // Derive a stable int from the UUID so each user maps to a unique lock slot
        // within each lock class.
        val userIdKey = (ownerUserId.mostSignificantBits xor ownerUserId.leastSignificantBits).toInt()
        dsl.execute("SELECT pg_advisory_xact_lock(?, ?)", lockClass, userIdKey)
    }

    private companion object {
        private const val LOCK_CLASS_CUSTOM_FOOD = 1
        private const val LOCK_CLASS_CUSTOM_MEAL = 2
    }
}
