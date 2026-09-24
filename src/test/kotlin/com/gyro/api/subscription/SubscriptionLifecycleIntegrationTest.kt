package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.error.InvoiceStatusConflictException
import com.gyro.api.common.outbox.OutboxEventRepository
import com.gyro.api.subscription.application.InvoiceService
import com.gyro.api.subscription.application.ManualGrantService
import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.ManualGrantRepository
import com.gyro.api.subscription.infrastructure.SubscriptionEventRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class SubscriptionLifecycleIntegrationTest(
    @Autowired private val lifecycleService: SubscriptionLifecycleService,
    @Autowired private val manualGrantService: ManualGrantService,
    @Autowired private val invoiceService: InvoiceService,
    @Autowired private val userSubscriptionRepository: UserSubscriptionRepository,
    @Autowired private val eventRepository: SubscriptionEventRepository,
    @Autowired private val manualGrantRepository: ManualGrantRepository,
    @Autowired private val outboxEventRepository: OutboxEventRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private var planId: Long = 0L
    private var priceId: Long = 0L

    @BeforeEach
    fun setUp() {
        val uniqueCode = "ADVANCED_${System.nanoTime()}"
        planId = seedPlan(uniqueCode, "Advanced", false)
        seedPlanFeature(planId, "premium_schedules")
        priceId = seedPrice(planId, 30, BigDecimal("9.99"), "IRR")
    }

    // ── 1. First purchase from free ────────────────────────────────────

    @Test
    fun `first purchase from free creates subscription via invoice paid`() {
        val userId = createUser()

        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        assertEquals(InvoiceStatus.PAID, paid.status)

        val sub = lifecycleService.applyPaidInvoice(paid)

        assertNotNull(sub.id)
        assertEquals(userId, sub.userId)
        assertEquals(planId, sub.planId)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertFalse(sub.cancelAtPeriodEnd)
        assertNull(sub.gracePeriodEnd)
    }

    // ── 2. Renewal extends current paid period ─────────────────────────

    @Test
    fun `renewal extends the current paid period`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        val renewed = lifecycleService.applyPaidInvoice(paid)

        assertEquals(SubscriptionStatus.ACTIVE, renewed.status)
        assertTrue(renewed.periodEnd!!.isAfter(periodEnd))
        val expectedExtension = ChronoUnit.SECONDS.between(invoice.periodStart, invoice.periodEnd)
        val actualExtension = ChronoUnit.SECONDS.between(periodEnd, renewed.periodEnd!!)
        assertEquals(expectedExtension, actualExtension)
    }

    // ── 3. Expiration when no renewal arrives ──────────────────────────

    @Test
    fun `expiration when no renewal arrives`() {
        jdbcTemplate.update("update subscription_plans set grace_period_days = 0 where id = ?", planId)
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(60, ChronoUnit.DAYS)
        val periodEnd = now.minus(1, ChronoUnit.DAYS)
        val sub = seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.processPeriodExpiry(sub.id!!)

        val updated = userSubscriptionRepository.findById(sub.id!!).get()
        assertEquals(SubscriptionStatus.EXPIRED, updated.status)
    }

    @Test
    fun `expiry is skipped when period end is in the future`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        val sub = seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.processPeriodExpiry(sub.id!!)

        val updated = userSubscriptionRepository.findById(sub.id!!).get()
        assertEquals(SubscriptionStatus.ACTIVE, updated.status)
    }

    // ── 4. User-requested cancellation at period end ───────────────────

    @Test
    fun `user cancellation sets cancelAtPeriodEnd flag`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.cancelAtPeriodEnd(userId)

        val updated = userSubscriptionRepository.findByUserId(userId).get()
        assertTrue(updated.cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, updated.status)
        assertEquals(periodEnd, updated.periodEnd)
    }

    // ── 5. Immediate provider cancellation / refund ────────────────────

    @Test
    fun `invoice voiding marks invoice as VOID`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)
        val voided = invoiceService.voidInvoice(invoice.id!!, "customer_request")

        assertEquals(InvoiceStatus.VOID, voided.status)
    }

    // ── 6. Grace-period entry after invoice remains OPEN ───────────────

    @Test
    fun `grace period fields are set on subscription when entering grace`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(5, ChronoUnit.DAYS)
        val sub = seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        val graceEnd = now.plus(7, ChronoUnit.DAYS)
        val updated = userSubscriptionRepository.save(
            sub.update(
                status = SubscriptionStatus.GRACE_PERIOD,
                gracePeriodEnd = graceEnd,
                graceReason = "payment_failure",
            ),
        )

        assertEquals(SubscriptionStatus.GRACE_PERIOD, updated.status)
        assertEquals(graceEnd, updated.gracePeriodEnd)
        assertEquals("payment_failure", updated.graceReason)
    }

    // ── 7. Grace-period recovery after invoice marked PAID ─────────────

    @Test
    fun `renewal from grace period clears grace fields`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(5, ChronoUnit.DAYS)
        val sub = seedSubscription(
            userId, planId, SubscriptionStatus.GRACE_PERIOD, periodStart, periodEnd,
            gracePeriodEnd = now.plus(7, ChronoUnit.DAYS),
            graceReason = "payment_failure",
        )

        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        val renewed = lifecycleService.applyPaidInvoice(paid)

        assertEquals(SubscriptionStatus.ACTIVE, renewed.status)
        assertNull(renewed.gracePeriodEnd)
        assertNull(renewed.graceReason)
        assertFalse(renewed.cancelAtPeriodEnd)
    }

    // ── 8. Restore purchases for a returning user ─────────────────────

    @Test
    fun `reactivating expired subscription clears cancelAtPeriodEnd and grace fields`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(60, ChronoUnit.DAYS)
        val periodEnd = now.minus(1, ChronoUnit.DAYS)
        val sub = seedSubscription(
            userId, planId, SubscriptionStatus.EXPIRED, periodStart, periodEnd,
            cancelAtPeriodEnd = true,
        )

        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        val reactivated = lifecycleService.applyPaidInvoice(paid)

        assertEquals(SubscriptionStatus.ACTIVE, reactivated.status)
        assertFalse(reactivated.cancelAtPeriodEnd)
        assertNull(reactivated.gracePeriodEnd)
        assertEquals(planId, reactivated.planId)
    }

    // ── 9. Manual admin grants with expiry ─────────────────────────────

    @Test
    fun `manual grant creates subscription for user without one`() {
        val userId = createUser()
        val adminId = createUser()

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = "beta tester",
        )

        assertNotNull(grant.id)
        assertEquals(userId, grant.userId)
        assertEquals(planId, grant.planId)
        assertEquals(30, grant.durationDays)
        assertNotNull(grant.expiresAt)

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(planId, sub.planId)
    }

    @Test
    fun `manual grant with finite duration sets subscription period end`() {
        val userId = createUser()
        val adminId = createUser()

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 365,
            reason = ManualGrantReason.EARLY_SUPPORTER,
            grantedBy = adminId,
            reasonNote = "early supporter launch grant",
        )

        assertEquals(365, grant.durationDays)
        assertNotNull(grant.expiresAt)

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(grant.expiresAt, sub.periodEnd)
    }

    // ── 10. Manual grant revocation ────────────────────────────────────

    @Test
    fun `revoking a grant marks it as revoked`() {
        val userId = createUser()
        val adminId = createUser()

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = null,
        )

        val revokerId = createUser()
        lifecycleService.revokeGrant(grant.id!!, revokerId, "no longer needed")

        val revoked = manualGrantRepository.findById(grant.id!!).get()
        assertNotNull(revoked.revokedAt)
        assertEquals(revokerId, revoked.revokedBy)
        assertEquals("no longer needed", revoked.revokeReason)

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.EXPIRED, sub.status)
        assertTrue(!sub.periodEnd!!.isAfter(Instant.now()))

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.ADMIN_REVOKE })
        assertTrue(outboxEventRepository.findAll().any { it.eventType == "subscription.ADMIN_REVOKE" })
    }

    @Test
    fun `revoking an already revoked grant is idempotent`() {
        val userId = createUser()
        val adminId = createUser()

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = null,
        )

        val revokerId = createUser()
        lifecycleService.revokeGrant(grant.id!!, revokerId, "first revoke")
        val firstRevokedAt = manualGrantRepository.findById(grant.id!!).get().revokedAt

        lifecycleService.revokeGrant(grant.id!!, revokerId, "second revoke")
        val secondRevokedAt = manualGrantRepository.findById(grant.id!!).get().revokedAt

        assertEquals(firstRevokedAt, secondRevokedAt)
    }

    // ── 11. Manual extension after expired subscription ────────────────

    @Test
    fun `manual grant reactivates expired subscription`() {
        val userId = createUser()
        val adminId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(60, ChronoUnit.DAYS)
        val periodEnd = now.minus(1, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.EXPIRED, periodStart, periodEnd)

        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.SUPPORT_RECOVERY,
            grantedBy = adminId,
            reasonNote = "compensation for outage",
        )

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(planId, sub.planId)
        assertNotNull(grant.expiresAt)
    }

    @Test
    fun `manual grant extends active subscription period`() {
        val userId = createUser()
        val adminId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "bonus days",
        )

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertTrue(sub.periodEnd!!.isAfter(periodEnd))
    }

    @Test
    fun `manual grant extension updates grant subscription event and outbox`() {
        val userId = createUser()
        val adminId = createUser()
        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "initial grant",
        )
        val oldExpiresAt = grant.expiresAt!!

        val extended = lifecycleService.extendGrant(
            grantId = grant.id!!,
            additionalDays = 15,
            adminId = adminId,
            reason = "support extension",
        )

        assertEquals(oldExpiresAt.plus(15, ChronoUnit.DAYS), extended.expiresAt)
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(extended.expiresAt, sub.periodEnd)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        val extendEvent = events.first { it.transitionType == SubscriptionTransitionType.ADMIN_GRANT_EXTEND }
        assertEquals(adminId, extendEvent.actorId)
        assertEquals("support extension", extendEvent.reason)
        assertTrue(outboxEventRepository.findAll().any { it.eventType == "subscription.ADMIN_GRANT_EXTEND" })
    }

    @Test
    fun `manual grant can be extended twice with separate lifecycle events`() {
        val userId = createUser()
        val adminId = createUser()
        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "initial grant",
        )
        val originalExpiresAt = grant.expiresAt!!

        lifecycleService.extendGrant(grant.id!!, 10, adminId, "first extension")
        val second = lifecycleService.extendGrant(grant.id!!, 5, adminId, "second extension")

        assertEquals(originalExpiresAt.plus(15, ChronoUnit.DAYS), second.expiresAt)
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(second.expiresAt, sub.periodEnd)

        val extendEvents = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.transitionType == SubscriptionTransitionType.ADMIN_GRANT_EXTEND }
        assertEquals(2, extendEvents.size)
        assertEquals(2, extendEvents.map { it.sourceId }.toSet().size)
    }

    @Test
    fun `extending first stacked grant shifts later grants and extends subscription by added days`() {
        val userId = createUser()
        val adminId = createUser()
        val firstGrant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "first grant",
        )
        val secondGrant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "second grant",
        )
        val stackedEnd = userSubscriptionRepository.findByUserId(userId).get().periodEnd!!
        assertEquals(secondGrant.expiresAt, stackedEnd)

        lifecycleService.extendGrant(firstGrant.id!!, 10, adminId, "extend first grant")

        val updatedFirstGrant = manualGrantRepository.findById(firstGrant.id!!).get()
        val updatedSecondGrant = manualGrantRepository.findById(secondGrant.id!!).get()
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(updatedFirstGrant.expiresAt, updatedSecondGrant.periodStart)
        assertEquals(stackedEnd.plus(10, ChronoUnit.DAYS), updatedSecondGrant.expiresAt)
        assertEquals(updatedSecondGrant.expiresAt, sub.periodEnd)
    }

    @Test
    fun `revoking first stacked grant recomputes remaining access without revoked duration`() {
        val userId = createUser()
        val adminId = createUser()
        val firstGrant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "first grant",
        )
        val secondGrant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.CUSTOM,
            grantedBy = adminId,
            reasonNote = "second grant",
        )
        val stackedEnd = userSubscriptionRepository.findByUserId(userId).get().periodEnd!!
        assertEquals(secondGrant.expiresAt, stackedEnd)

        lifecycleService.revokeGrant(firstGrant.id!!, adminId, "remove first grant")

        val updatedSecondGrant = manualGrantRepository.findById(secondGrant.id!!).get()
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(updatedSecondGrant.expiresAt, sub.periodEnd)
        assertTrue(sub.periodEnd!!.isBefore(stackedEnd))
        assertEquals(30, ChronoUnit.DAYS.between(updatedSecondGrant.periodStart, updatedSecondGrant.expiresAt))
    }

    @Test
    fun `manual grant expiry removes access and records lifecycle event`() {
        val userId = createUser()
        val adminId = createUser()
        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 1,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = "short grant",
        )
        jdbcTemplate.update(
            "update manual_grants set expires_at = now() - interval '1 hour' where id = ?",
            grant.id,
        )

        lifecycleService.expireGrant(grant.id!!)

        val expiredGrant = manualGrantRepository.findById(grant.id!!).get()
        assertNotNull(expiredGrant.revokedAt)
        assertEquals("expired", expiredGrant.revokeReason)

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.EXPIRED, sub.status)
        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.PERIOD_EXPIRED })
        assertTrue(outboxEventRepository.findAll().any { it.eventType == "subscription.PERIOD_EXPIRED" })
    }

    @Test
    fun `manual grant service expiry persists lifecycle changes`() {
        val userId = createUser()
        val adminId = createUser()
        val grant = lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 1,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = "short grant",
        )
        jdbcTemplate.update(
            "update manual_grants set expires_at = now() - interval '1 hour' where id = ?",
            grant.id,
        )

        manualGrantService.expireGrants()

        val expiredGrant = manualGrantRepository.findById(grant.id!!).get()
        assertNotNull(expiredGrant.revokedAt)
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.EXPIRED, sub.status)
        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.PERIOD_EXPIRED })
    }

    @Test
    fun `lower tier manual grant cannot downgrade active paid subscription`() {
        val userId = createUser()
        val adminId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)
        val freePlanId = seedPlan("FREE_${System.nanoTime()}", "Free", true)

        assertThrows(com.gyro.api.common.error.LowerTierManualGrantException::class.java) {
            lifecycleService.grantAccess(
                userId = userId,
                planId = freePlanId,
                durationDays = 30,
                reason = ManualGrantReason.CUSTOM,
                grantedBy = adminId,
                reasonNote = "should not downgrade",
            )
        }

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(planId, sub.planId)
        assertEquals(periodEnd, sub.periodEnd)
    }

    // ── 12. Duplicate webhook events (idempotency) ─────────────────────

    @Test
    fun `duplicate invoice payment is idempotent`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)

        val first = lifecycleService.applyPaidInvoice(paid)
        val second = lifecycleService.applyPaidInvoice(paid)

        assertEquals(first.id, second.id)
        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
    }

    // ── 13. Out-of-order webhook delivery ──────────────────────────────

    @Test
    fun `out of order invoice payments are handled correctly`() {
        val userId = createUser()
        val invoice1 = invoiceService.createInvoice(userId, priceId)
        val invoice2 = invoiceService.createInvoice(userId, priceId)
        val paid2 = invoiceService.markPaid(invoice2.id!!)
        val paid1 = invoiceService.markPaid(invoice1.id!!)

        lifecycleService.applyPaidInvoice(paid2)
        lifecycleService.applyPaidInvoice(paid1)

        val sub = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
    }

    // ── 14. Invoice creation with promotion discount ───────────────────

    @Test
    fun `invoice creation records promotion code`() {
        val userId = createUser()
        seedPromotion("SUMMER2026", BigDecimal("2.00"))
        val invoice = invoiceService.createInvoice(userId, priceId, promotionCode = "SUMMER2026")

        assertEquals("SUMMER2026", invoice.promotionCode)
        assertEquals(InvoiceStatus.OPEN, invoice.status)
        assertEquals(BigDecimal("9.99"), invoice.amountDue.amount)
        assertEquals(BigDecimal("7.99"), invoice.amountAfterDiscount.amount)
        assertEquals("IRR", invoice.amountDue.currency)
    }

    // ── 15. Multiple payment attempts on one invoice ───────────────────

    @Test
    fun `marking invoice paid is idempotent`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)

        val first = invoiceService.markPaid(invoice.id!!)
        val second = invoiceService.markPaid(invoice.id!!)

        assertEquals(InvoiceStatus.PAID, first.status)
        assertEquals(InvoiceStatus.PAID, second.status)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `concurrent paid and void transitions cannot both overwrite the invoice`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val transitions = listOf(
                executor.submit<Any> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { invoiceService.markPaid(invoice.id!!) }.exceptionOrNull() ?: InvoiceStatus.PAID
                },
                executor.submit<Any> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { invoiceService.voidInvoice(invoice.id!!, "concurrent_test") }
                        .exceptionOrNull() ?: InvoiceStatus.VOID
                },
            ).map { future -> future.get(15, TimeUnit.SECONDS) }

            assertEquals(1, transitions.count { it is InvoiceStatusConflictException })
            assertEquals(1, transitions.count { it is InvoiceStatus })
            assertTrue(
                invoiceService.getInvoice(invoice.id!!).status in setOf(InvoiceStatus.PAID, InvoiceStatus.VOID),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    // ── 16. Manual payment marking invoice PAID ────────────────────────

    @Test
    fun `manual payment marking invoice PAID without PaymentAttempt`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)
        assertEquals(InvoiceStatus.OPEN, invoice.status)

        val paid = invoiceService.markPaid(invoice.id!!)
        assertEquals(InvoiceStatus.PAID, paid.status)
        assertEquals(userId, paid.userId)
        assertEquals(planId, paid.planId)
    }

    // ── Event recording ───────────────────────────────────────────────

    @Test
    fun `first purchase records FIRST_PURCHASE event`() {
        val userId = createUser()
        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        lifecycleService.applyPaidInvoice(paid)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.FIRST_PURCHASE })
        assertTrue(events.any { it.sourceType == EventSourceType.PAYPING })
    }

    @Test
    fun `renewal records RENEWAL event with before snapshot`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        val invoice = invoiceService.createInvoice(userId, priceId)
        val paid = invoiceService.markPaid(invoice.id!!)
        lifecycleService.applyPaidInvoice(paid)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        val renewalEvent = events.first { it.transitionType == SubscriptionTransitionType.RENEWAL }
        assertEquals(SubscriptionStatus.ACTIVE, renewalEvent.statusBefore)
        assertEquals(SubscriptionStatus.ACTIVE, renewalEvent.statusAfter)
    }

    @Test
    fun `cancellation records USER_CANCEL event with actor`() {
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.cancelAtPeriodEnd(userId)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        val cancelEvent = events.first { it.transitionType == SubscriptionTransitionType.USER_CANCEL }
        assertEquals(userId, cancelEvent.actorId)
        assertEquals(EventSourceType.USER, cancelEvent.sourceType)
    }

    @Test
    fun `admin grant records ADMIN_GRANT event`() {
        val userId = createUser()
        val adminId = createUser()

        lifecycleService.grantAccess(
            userId = userId,
            planId = planId,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = "testing",
        )

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.ADMIN_GRANT })
        val grantEvent = events.first { it.transitionType == SubscriptionTransitionType.ADMIN_GRANT }
        assertEquals(adminId, grantEvent.actorId)
    }

    @Test
    fun `period expiry records PERIOD_EXPIRED event`() {
        jdbcTemplate.update("update subscription_plans set grace_period_days = 0 where id = ?", planId)
        val userId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(60, ChronoUnit.DAYS)
        val periodEnd = now.minus(1, ChronoUnit.DAYS)
        val sub = seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.processPeriodExpiry(sub.id!!)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.PERIOD_EXPIRED })
        val expiryEvent = events.first { it.transitionType == SubscriptionTransitionType.PERIOD_EXPIRED }
        assertEquals(EventSourceType.SYSTEM, expiryEvent.sourceType)
    }

    @Test
    fun `billing block and unblock record correct events`() {
        val userId = createUser()
        val adminId = createUser()
        val now = Instant.now()
        val periodStart = now.minus(15, ChronoUnit.DAYS)
        val periodEnd = now.plus(15, ChronoUnit.DAYS)
        seedSubscription(userId, planId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)

        lifecycleService.blockBilling(userId, adminId, "fraud suspicion")
        val blocked = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.BILLED_BLOCKED, blocked.status)

        lifecycleService.unblockBilling(userId, adminId, "cleared")
        val unblocked = userSubscriptionRepository.findByUserId(userId).get()
        assertEquals(SubscriptionStatus.ACTIVE, unblocked.status)

        val events = eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.ADMIN_BILLING_BLOCK })
        assertTrue(events.any { it.transitionType == SubscriptionTransitionType.ADMIN_BILLING_UNBLOCK })
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
            code,
            name,
            free,
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
            planId,
            billingPeriodDays,
            amount,
            currency,
        ) ?: error("Expected price id")
    }

    private fun seedPlanFeature(planId: Long, featureKey: String) {
        jdbcTemplate.update(
            """
            insert into plan_features (plan_id, feature_key, enabled)
            values (?, ?, true)
            on conflict (plan_id, feature_key) do update set enabled = true
            """.trimIndent(),
            planId,
            featureKey,
        )
    }

    private fun seedPromotion(code: String, value: BigDecimal): Long {
        return jdbcTemplate.queryForObject(
            """
            insert into promotions (
                code, type, value, applicable_plan_id, starts_at, ends_at,
                per_user_redemption_limit, active
            )
            values (?, 'FIXED_DISCOUNT', ?, ?, now() - interval '1 day', now() + interval '30 days', 10, true)
            returning id
            """.trimIndent(),
            Long::class.java,
            code,
            value,
            planId,
        ) ?: error("Expected promotion id")
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
    ): com.gyro.api.subscription.domain.UserSubscription {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end, grace_reason
            ) values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?::timestamptz, ?)
            """.trimIndent(),
            userId,
            planId,
            status.name,
            periodStart.toString(),
            periodEnd.toString(),
            cancelAtPeriodEnd,
            gracePeriodEnd?.toString(),
            graceReason,
        )
        return userSubscriptionRepository.findByUserId(userId).get()
    }
}
