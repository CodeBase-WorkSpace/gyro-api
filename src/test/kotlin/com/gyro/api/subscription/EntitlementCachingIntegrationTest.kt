package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.EntitlementCacheInvalidator
import com.gyro.api.subscription.application.EntitlementCacheService
import com.gyro.api.subscription.application.EntitlementService
import com.gyro.api.subscription.application.job.OutboxPublisherJob
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.domain.SubscriptionStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class EntitlementCachingIntegrationTest(
    @Autowired private val cachedEntitlementService: CachedEntitlementService,
    @Autowired private val entitlementService: EntitlementService,
    @Autowired private val cacheService: EntitlementCacheService,
    @Autowired private val outboxPublisherJob: OutboxPublisherJob,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private var planId: Long = 0L
    private var priceId: Long = 0L

    @BeforeEach
    fun setUp() {
        val uniqueCode = "ADVANCED_${System.nanoTime()}"
        planId = seedPlan(uniqueCode, "Advanced", false)
        priceId = seedPrice(planId, 30, BigDecimal("19.99"), "IRR")
        seedPlanFeatures(planId)
    }

    private fun seedPlanFeatures(planId: Long) {
        val features = listOf("premium_schedules", "advanced_analytics", "data_export", "future_meal_planning", "higher_limits")
        for (feature in features) {
            jdbcTemplate.update(
                "INSERT INTO plan_features (plan_id, feature_key, enabled) VALUES (?, ?, true) ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = true",
                planId, feature,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE scope LIKE 'checkout:%'")
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id::text FROM user_subscriptions WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM subscription_events WHERE user_id IN (SELECT user_id FROM user_subscriptions WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM payment_attempts WHERE invoice_id IN (SELECT id FROM invoices WHERE plan_id = ?)", planId)
        jdbcTemplate.update("DELETE FROM invoices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM user_subscriptions WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM manual_grants WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM manual_grants WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code LIKE 'FREE_GRANT_%')")
        jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code LIKE 'FREE_GRANT_%')")
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE id = ?", planId)
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE code LIKE 'FREE_GRANT_%'")
    }

    // ── 1. Entitlement is cached after first computation ───────────────

    @Nested
    inner class `Entitlement is cached after first computation` {

        @Test
        fun `first request computes from subscription and stores in Redis`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val first = cachedEntitlementService.getEntitlement(userId)

            assertEquals(EntitlementStatus.ACTIVE, first.status)
            assertTrue(first.features.contains("premium_schedules"))
            assertNotNull(first.currentPeriodEnd)

            val cacheKey = cacheService.keyFor(userId)
            val cached = redisTemplate.opsForValue().get(cacheKey)
            assertNotNull(cached, "Entitlement should be cached in Redis")
            assertTrue(cached!!.contains("ACTIVE"))
        }

        @Test
        fun `second request returns cached value`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val first = cachedEntitlementService.getEntitlement(userId)
            val second = cachedEntitlementService.getEntitlement(userId)

            assertEquals(first.status, second.status)
            assertEquals(first.planKey, second.planKey)
            assertEquals(first.features, second.features)
        }
    }

    // ── 2. Lifecycle events invalidate entitlement cache ───────────────

    @Test
    fun `expired subscription exposes no premium features`() {
        val userId = createUser()
        val now = Instant.now()
        seedSubscription(
            userId,
            planId,
            SubscriptionStatus.EXPIRED,
            now.minus(60, ChronoUnit.DAYS),
            now.minus(30, ChronoUnit.DAYS)
        )

        val entitlement = entitlementService.compute(userId)

        assertEquals(EntitlementStatus.EXPIRED, entitlement.status)
        assertTrue(entitlement.features.isEmpty())
    }

    @Test
    fun `period ending at current instant is expired`() {
        val userId = createUser()
        val now = Instant.now()
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(30, ChronoUnit.DAYS), now)

        val entitlement = entitlementService.compute(userId)

        assertEquals(EntitlementStatus.EXPIRED, entitlement.status)
        assertTrue(entitlement.features.isEmpty())
    }

    @Test
    fun `inactive feature definitions fail closed for active subscriptions`() {
        val userId = createUser()
        val now = Instant.now()
        seedSubscription(
            userId,
            planId,
            SubscriptionStatus.ACTIVE,
            now.minus(15, ChronoUnit.DAYS),
            now.plus(15, ChronoUnit.DAYS)
        )

        try {
            jdbcTemplate.update("UPDATE subscription_features SET active = false WHERE key = 'premium_schedules'")

            val entitlement = entitlementService.compute(userId)

            assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
            assertFalse(entitlement.features.contains("premium_schedules"))
            assertTrue(entitlement.features.contains("advanced_analytics"))
        } finally {
            jdbcTemplate.update("UPDATE subscription_features SET active = true WHERE key = 'premium_schedules'")
        }
    }

    @Test
    fun `multiple active manual grants resolve strongest entitlement`() {
        val userId = createUser()
        val lowerPlanId = seedPlan("FREE_GRANT_${System.nanoTime()}", "Free Grant", true)
        seedManualGrant(userId, planId, 30, Instant.now().plus(30, ChronoUnit.DAYS))
        seedManualGrant(userId, lowerPlanId, 30, Instant.now().plus(30, ChronoUnit.DAYS))

        val entitlement = entitlementService.compute(userId)

        assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
        assertEquals(
            jdbcTemplate.queryForObject(
                "select code from subscription_plans where id = ?",
                String::class.java,
                planId
            ), entitlement.planKey
        )
        assertTrue(entitlement.features.contains("premium_schedules"))
    }

    @Nested
    inner class `Lifecycle events invalidate entitlement cache` {

        @Test
        fun `invalidation forces recomputation with latest state`() {
            val userId = createUser()
            val now = Instant.now()

            // Given: User has no subscription (FREE)
            val free = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.FREE, free.status)

            // When: Subscription is created and cache invalidated
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now, now.plus(30, ChronoUnit.DAYS))
            cachedEntitlementService.invalidate(userId)

            // Then: Next request returns ACTIVE
            val active = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.ACTIVE, active.status)
        }

        @Test
        fun `invalidation after renewal reflects new period end`() {
            val userId = createUser()
            val now = Instant.now()
            val periodEnd = now.plus(15, ChronoUnit.DAYS)
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), periodEnd)

            cachedEntitlementService.getEntitlement(userId)

            val newPeriodEnd = periodEnd.plus(30, ChronoUnit.DAYS)
            jdbcTemplate.update(
                "UPDATE user_subscriptions SET period_end = ?::timestamptz WHERE user_id = ?",
                newPeriodEnd.toString(), userId,
            )
            cachedEntitlementService.invalidate(userId)

            val renewed = cachedEntitlementService.getEntitlement(userId)
            assertEquals(newPeriodEnd, renewed.currentPeriodEnd)
        }

        @Test
        fun `invalidation after expiration reflects EXPIRED status`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

            cachedEntitlementService.getEntitlement(userId)

            jdbcTemplate.update("UPDATE user_subscriptions SET status = 'EXPIRED' WHERE user_id = ?", userId)
            cachedEntitlementService.invalidate(userId)

            val expired = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.EXPIRED, expired.status)
        }

        @Test
        fun `invalidation after deletion returns FREE`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            cachedEntitlementService.getEntitlement(userId)

            jdbcTemplate.update("DELETE FROM user_subscriptions WHERE user_id = ?", userId)
            cachedEntitlementService.invalidate(userId)

            val free = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.FREE, free.status)
        }
    }

    // ── 3. Redis unavailable fallback behavior ─────────────────────────

    @Nested
    inner class `Redis unavailable fallback behavior` {

        @Test
        fun `entitlement computed from database when cache read fails`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            // When: Entitlement is requested (cache miss on first request)
            val result = cachedEntitlementService.getEntitlement(userId)

            // Then: Computed from database
            assertEquals(EntitlementStatus.ACTIVE, result.status)
            assertTrue(result.features.contains("premium_schedules"))
        }

        @Test
        fun `cache write failure does not break request`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            // When: Request entitlement
            val result = cachedEntitlementService.getEntitlement(userId)

            // Then: Entitlement is returned successfully
            assertEquals(EntitlementStatus.ACTIVE, result.status)
        }
    }

    // ── 4. Cache TTL expiration ───────────────────────────────────────

    @Nested
    inner class `Cache TTL expiration` {

        @Test
        fun `expired cache entry is ignored and recomputed`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            // Given: Cache is populated
            cachedEntitlementService.getEntitlement(userId)
            val cacheKey = cacheService.keyFor(userId)
            assertNotNull(redisTemplate.opsForValue().get(cacheKey))

            // When: Cache entry expires
            redisTemplate.delete(cacheKey)

            // Then: Next request recomputes and repopulates
            val result = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.ACTIVE, result.status)
            assertNotNull(redisTemplate.opsForValue().get(cacheKey))
        }
    }

    // ── 5. Cache isolation between users ──────────────────────────────

    @Nested
    inner class `Cache isolation between users` {

        @Test
        fun `different users have separate cache entries`() {
            val userA = createUser()
            val userB = createUser()
            val now = Instant.now()

            seedSubscription(userA, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val entitlementA = cachedEntitlementService.getEntitlement(userA)
            val entitlementB = cachedEntitlementService.getEntitlement(userB)

            assertEquals(EntitlementStatus.ACTIVE, entitlementA.status)
            assertEquals(EntitlementStatus.FREE, entitlementB.status)
            assertTrue(cacheService.keyFor(userA) != cacheService.keyFor(userB))
        }

        @Test
        fun `invalidating one user does not affect another`() {
            val userA = createUser()
            val userB = createUser()
            val now = Instant.now()

            seedSubscription(userA, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))
            seedSubscription(userB, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            cachedEntitlementService.getEntitlement(userA)
            cachedEntitlementService.getEntitlement(userB)

            cachedEntitlementService.invalidate(userA)

            val keyB = cacheService.keyFor(userB)
            assertNotNull(redisTemplate.opsForValue().get(keyB))
        }
    }

    // ── 6. Different subscription states ──────────────────────────────

    @Nested
    inner class `Different subscription states produce different entitlements` {

        @Test
        fun `active subscription produces ACTIVE entitlement`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val entitlement = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
        }

        @Test
        fun `expired subscription produces EXPIRED entitlement`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.EXPIRED, now.minus(60, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

            val entitlement = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.EXPIRED, entitlement.status)
        }

        @Test
        fun `no subscription produces FREE entitlement`() {
            val userId = createUser()

            val entitlement = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.FREE, entitlement.status)
            assertEquals("FREE", entitlement.planKey)
            assertTrue(entitlement.features.isEmpty())
        }

        @Test
        fun `grace period produces GRACE_PERIOD entitlement`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(
                userId, planId, SubscriptionStatus.GRACE_PERIOD,
                now.minus(15, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS),
                gracePeriodEnd = now.plus(7, ChronoUnit.DAYS),
            )

            val entitlement = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.GRACE_PERIOD, entitlement.status)
            assertEquals("PAYMENT_PAST_DUE", entitlement.supportReasonCode)
        }

        @Test
        fun `billed blocked produces BILLED_BLOCKED entitlement`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.BILLED_BLOCKED, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val entitlement = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.BILLED_BLOCKED, entitlement.status)
            assertEquals("BILLING_BLOCKED", entitlement.supportReasonCode)
        }
    }

    // ── 7. Cache does not bypass authorization ─────────────────────────

    @Nested
    inner class `Entitlement cache does not bypass authorization` {

        @Test
        fun `expired subscription always returns EXPIRED regardless of cache`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            val active = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.ACTIVE, active.status)

            jdbcTemplate.update(
                "UPDATE user_subscriptions SET status = 'EXPIRED', period_end = ?::timestamptz WHERE user_id = ?",
                now.minus(1, ChronoUnit.DAYS).toString(), userId,
            )
            cachedEntitlementService.invalidate(userId)

            val expired = cachedEntitlementService.getEntitlement(userId)
            assertEquals(EntitlementStatus.EXPIRED, expired.status)
        }
    }

    // ── 8. Invalidator does not reprocess events ──────────────────────

    @Nested
    inner class `Invalidator does not reprocess events` {

        @Test
        fun `event is processed only once across multiple poll cycles`() {
            val userId = createUser()
            val now = Instant.now()
            seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, now.minus(15, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS))

            // Given: An outbox event exists for this user
            val eventId = jdbcTemplate.queryForObject(
                """
                insert into outbox_events (event_type, aggregate_type, aggregate_id, payload, status)
                values (
                    'subscription.FIRST_PURCHASE',
                    'UserSubscription',
                    ?,
                    '{"userId":"${userId}","transitionType":"FIRST_PURCHASE","planId":${planId},"periodStart":null,"periodEnd":null,"occurredAt":"${now}"}',
                    'PENDING'
                )
                returning id
                """.trimIndent(),
                Long::class.java,
                userId.toString(),
            )!!

            // And: Cache is populated
            cachedEntitlementService.getEntitlement(userId)
            assertNotNull(redisTemplate.opsForValue().get(cacheService.keyFor(userId)))

            // When: The real outbox publishing entry point runs
            outboxPublisherJob.run()

            // Then: Cache was invalidated
            assertEquals(null, redisTemplate.opsForValue().get(cacheService.keyFor(userId)))

            // Then: Event was consumed
            val consumptionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event_consumptions WHERE event_id = ? AND consumer_name = ?",
                Int::class.java,
                eventId, EntitlementCacheInvalidator.CONSUMER_NAME,
            )!!
            assertEquals(1, consumptionCount)

            // When: The publisher runs again
            outboxPublisherJob.run()

            // Then: Consumption count is still 1 (not duplicated)
            val secondCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event_consumptions WHERE event_id = ? AND consumer_name = ?",
                Int::class.java,
                eventId, EntitlementCacheInvalidator.CONSUMER_NAME,
            )!!
            assertEquals(1, secondCount, "Event should not be processed again")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role,
                email_verification_status, phone_verification_status,
                status, created_at, updated_at
            ) values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "user-${System.nanoTime()}-${id.toString().take(8)}@example.com",
        )
        return id
    }

    private fun seedPlan(code: String, name: String, free: Boolean): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_plans (code, name, free, active, grace_period_days)
            values (?, ?, ?, true, 7)
            returning id
            """.trimIndent(),
            Long::class.java,
            code, name, free,
        ) ?: error("Expected plan id")
    }

    private fun seedPrice(planId: Long, billingPeriodDays: Int, amount: BigDecimal, currency: String): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
            values (?, ?, ?, ?, true, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            planId, billingPeriodDays, amount, currency,
        ) ?: error("Expected price id")
    }

    private fun seedSubscription(
        userId: UUID,
        planId: Long,
        status: SubscriptionStatus,
        periodStart: Instant,
        periodEnd: Instant,
        cancelAtPeriodEnd: Boolean = false,
        gracePeriodEnd: Instant? = null,
        graceReason: String? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end, grace_reason
            ) values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?::timestamptz, ?)
            """.trimIndent(),
            userId, planId, status.name,
            periodStart.toString(), periodEnd.toString(),
            cancelAtPeriodEnd, gracePeriodEnd?.toString(), graceReason,
        )
    }

    private fun seedManualGrant(userId: UUID, planId: Long, durationDays: Int, expiresAt: Instant) {
        jdbcTemplate.update(
            """
            insert into manual_grants (
                id, user_id, plan_id, duration_days, expires_at,
                reason, reason_note, granted_by, created_at
            ) values (?, ?, ?, ?, ?::timestamptz, 'TESTER_ACCESS', 'test grant', ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            planId,
            durationDays,
            expiresAt.toString(),
            createUser(),
        )
    }
}
