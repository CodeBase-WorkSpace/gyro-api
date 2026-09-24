package com.gyro.api.notification.application

import com.gyro.api.common.outbox.OutboxEvent
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.EntitlementService
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import com.gyro.api.subscription.config.PremiumGatingProperties
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementSource
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.infrastructure.SubscriptionFeatureRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class PremiumAccessDowngradeNoticeConsumerTest {
    private val objectMapper = Mockito.mock(ObjectMapper::class.java)
    private val createdRequests = mutableListOf<NotificationRequest>()
    private val notificationService = Mockito.mock(NotificationService::class.java) { invocation ->
        if (invocation.method.name == "create") {
            createdRequests += invocation.arguments.single() as NotificationRequest
            UUID.randomUUID()
        } else {
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val entitlementService = Mockito.mock(EntitlementService::class.java)
    private val consumer = PremiumAccessDowngradeNoticeConsumer(
        objectMapper,
        notificationService,
        entitlementService,
        entitlementGateService(),
    )

    @Test
    fun `supports every premium access lapse event`() {
        assertTrue(consumer.supports("subscription.PERIOD_EXPIRED"))
        assertTrue(consumer.supports("subscription.GRACE_EXIT_FAILURE"))
        assertTrue(consumer.supports("subscription.MANUAL_GRANT_EXPIRED"))
    }

    @Test
    fun `uses the same idempotency key when an expiry event is retried`() {
        val userId = UUID.randomUUID()
        val periodEnd = Instant.parse("2026-07-20T10:00:00Z")
        val payload = payload(userId, "PERIOD_EXPIRED", periodEnd)
        stubPayload(payload)
        stubEntitlement(userId, entitlement(EntitlementStatus.EXPIRED))
        consumer.consume(event(101, "subscription.PERIOD_EXPIRED"))
        consumer.consume(event(102, "subscription.PERIOD_EXPIRED"))

        val requests = capturedRequests(2)
        assertEquals(requests[0].idempotencyKey, requests[1].idempotencyKey)
        assertEquals("premium-access-downgrade:$userId:${periodEnd.epochSecond}", requests[0].idempotencyKey)
        assertEquals(NotificationType.PREMIUM_ACCESS_DOWNGRADED, requests[0].type)
    }

    @Test
    fun `uses occurrence time as the failed grace boundary`() {
        val occurredAt = Instant.parse("2026-07-21T08:30:00Z")
        val userId = UUID.randomUUID()
        val payload = payload(userId, "GRACE_EXIT_FAILURE", Instant.parse("2026-07-30T00:00:00Z"), occurredAt)
        stubPayload(payload)
        stubEntitlement(userId, entitlement(EntitlementStatus.EXPIRED))
        consumer.consume(event(201, "subscription.GRACE_EXIT_FAILURE"))

        assertTrue(capturedRequests(1).single().idempotencyKey.endsWith(":${occurredAt.epochSecond}"))
    }

    @Test
    fun `manual grant expiry creates the general downgrade notice`() {
        val periodEnd = Instant.parse("2026-07-22T12:00:00Z")
        val userId = UUID.randomUUID()
        val payload = payload(userId, "MANUAL_GRANT_EXPIRED", periodEnd)
        stubPayload(payload)
        stubEntitlement(userId, entitlement(EntitlementStatus.FREE))
        consumer.consume(event(301, "subscription.MANUAL_GRANT_EXPIRED"))

        val request = capturedRequests(1).single()
        assertEquals(NotificationType.PREMIUM_ACCESS_DOWNGRADED, request.type)
        assertTrue(request.idempotencyKey.endsWith(":${periodEnd.epochSecond}"))
    }

    @Test
    fun `trial grant expiry is ignored after the user purchases advanced`() {
        val userId = UUID.randomUUID()
        stubPayload(payload(userId, "MANUAL_GRANT_EXPIRED", Instant.parse("2026-07-22T12:00:00Z")))
        stubEntitlement(
            userId,
            entitlement(EntitlementStatus.ACTIVE, EntitlementSource.SUBSCRIPTION, setOf(PREMIUM_SCHEDULES)),
        )

        consumer.consume(event(401, "subscription.MANUAL_GRANT_EXPIRED"))

        assertEquals(0, createdRequests.size)
    }

    @Test
    fun `old period expiry is ignored after resubscription`() {
        val userId = UUID.randomUUID()
        stubPayload(payload(userId, "PERIOD_EXPIRED", Instant.parse("2026-07-23T12:00:00Z")))
        stubEntitlement(
            userId,
            entitlement(EntitlementStatus.ACTIVE, EntitlementSource.SUBSCRIPTION, setOf(FUTURE_MEAL_PLANNING)),
        )

        consumer.consume(event(402, "subscription.PERIOD_EXPIRED"))

        assertEquals(0, createdRequests.size)
    }

    @Test
    fun `expired grant is ignored when another grant still provides premium access`() {
        val userId = UUID.randomUUID()
        stubPayload(payload(userId, "MANUAL_GRANT_EXPIRED", Instant.parse("2026-07-24T12:00:00Z")))
        stubEntitlement(
            userId,
            entitlement(EntitlementStatus.ACTIVE, EntitlementSource.MANUAL_GRANT, setOf(HIGHER_LIMITS)),
        )

        consumer.consume(event(403, "subscription.MANUAL_GRANT_EXPIRED"))

        assertEquals(0, createdRequests.size)
    }

    @Test
    fun `grace period suppresses the downgrade notice`() {
        val userId = UUID.randomUUID()
        stubPayload(payload(userId, "PERIOD_EXPIRED", Instant.parse("2026-07-25T12:00:00Z")))
        stubEntitlement(
            userId,
            entitlement(EntitlementStatus.GRACE_PERIOD, EntitlementSource.SUBSCRIPTION, setOf(PREMIUM_SCHEDULES)),
        )

        consumer.consume(event(404, "subscription.PERIOD_EXPIRED"))

        assertEquals(0, createdRequests.size)
    }

    @Test
    fun `admin override suppresses the downgrade notice`() {
        val userId = UUID.randomUUID()
        stubPayload(payload(userId, "PERIOD_EXPIRED", Instant.parse("2026-07-26T12:00:00Z")))
        stubEntitlement(
            userId,
            entitlement(EntitlementStatus.ADMIN_OVERRIDE, EntitlementSource.MANUAL_GRANT, setOf(PREMIUM_SCHEDULES)),
        )

        consumer.consume(event(405, "subscription.PERIOD_EXPIRED"))

        assertEquals(0, createdRequests.size)
    }

    private fun stubPayload(payload: SubscriptionEventPayload) {
        Mockito.`when`(objectMapper.readValue(anyString(), eq(SubscriptionEventPayload::class.java)))
            .thenReturn(payload)
    }

    private fun stubEntitlement(userId: UUID, entitlement: Entitlement) {
        Mockito.`when`(entitlementService.compute(userId)).thenReturn(entitlement)
    }

    private fun capturedRequests(expectedInvocations: Int): List<NotificationRequest> {
        assertEquals(expectedInvocations, createdRequests.size)
        return createdRequests
    }

    private fun payload(
        userId: UUID,
        transitionType: String,
        periodEnd: Instant,
        occurredAt: Instant = periodEnd,
    ) = SubscriptionEventPayload(
        userId = userId,
        transitionType = transitionType,
        planId = 2,
        periodStart = periodEnd.minusSeconds(86_400),
        periodEnd = periodEnd,
        occurredAt = occurredAt,
    )

    private fun event(id: Long, type: String) = OutboxEvent(
        id = id,
        eventType = type,
        aggregateType = "UserSubscription",
        aggregateId = "subscription-1",
        payload = "{}",
    )

    private fun entitlement(
        status: EntitlementStatus,
        source: EntitlementSource = EntitlementSource.FREE,
        features: Set<String> = emptySet(),
    ) = Entitlement(
        status = status,
        planKey = if (features.isEmpty()) "FREE" else "ADVANCED",
        features = features,
        currentPeriodEnd = null,
        gracePeriodEnd = null,
        cancelAtPeriodEnd = false,
        supportReasonCode = null,
        source = source,
    )

    private fun entitlementGateService(): EntitlementGateService {
        @Suppress("UNCHECKED_CAST")
        val meterProvider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MeterRegistry>
        return EntitlementGateService(
            cachedEntitlementService = Mockito.mock(CachedEntitlementService::class.java),
            subscriptionFeatureRepository = Mockito.mock(SubscriptionFeatureRepository::class.java),
            meterRegistryProvider = meterProvider,
            premiumGatingProperties = PremiumGatingProperties(),
        )
    }

    private companion object {
        private const val PREMIUM_SCHEDULES = "premium_schedules"
        private const val FUTURE_MEAL_PLANNING = "future_meal_planning"
        private const val HIGHER_LIMITS = "higher_limits"
    }
}
