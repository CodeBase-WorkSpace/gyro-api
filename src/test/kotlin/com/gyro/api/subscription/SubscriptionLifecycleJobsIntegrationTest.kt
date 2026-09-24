package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.outbox.*
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.*
import com.gyro.api.subscription.application.job.OutboxPublisherJob
import com.gyro.api.subscription.application.job.RenewalReminderJob
import com.gyro.api.subscription.application.job.StalePaymentAttemptCleanupJob
import com.gyro.api.subscription.application.outbox.RenewalReminderRecordedConsumer
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.PaymentAttemptRepository
import com.gyro.api.subscription.infrastructure.StalePaymentAttemptRepository
import com.gyro.api.subscription.infrastructure.SubscriptionRenewalReminderRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.*

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(properties = ["app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.security.verification-code-pepper=test-pepper", "app.rate-limit.enabled=false", "app.billing.lifecycle.jobs-enabled=false", "spring.data.redis.host=localhost", "spring.data.redis.port=6379", "spring.data.redis.timeout=2s"])
class SubscriptionLifecycleJobsIntegrationTest(
    @Autowired private val lifecycle: SubscriptionLifecycleService,
    @Autowired private val subscriptions: UserSubscriptionRepository,
    @Autowired private val attempts: PaymentAttemptRepository,
    @Autowired private val staleAttempts: StalePaymentAttemptRepository,
    @Autowired private val reminders: RenewalReminderService,
    @Autowired private val reminderRepository: SubscriptionRenewalReminderRepository,
    @Autowired private val outbox: OutboxEventRepository,
    @Autowired private val cleanup: OutboxCleanupService,
    @Autowired private val time: TimeProvider,
    @Autowired private val metrics: LifecycleObservability,
    @Autowired private val deliveryService: OutboxDeliveryService,
    @Autowired private val invalidator: EntitlementCacheInvalidator,
    @Autowired private val reminderConsumer: RenewalReminderRecordedConsumer,
    @Autowired private val configuredPublisher: OutboxPublisherJob,
    @Autowired private val configuredStaleJob: StalePaymentAttemptCleanupJob,
    @Autowired private val configuredReminderJob: RenewalReminderJob,
    @Value("\${app.billing.lifecycle.jobs-enabled}") private val configuredJobsEnabled: Boolean,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val createdUsers = mutableListOf<UUID>()
    private val createdPlans = mutableListOf<Long>()

    @Test
    fun `lifecycle job beans bind with background scheduling disabled in tests`() {
        assertFalse(configuredJobsEnabled)
        assertNotNull(configuredPublisher)
        assertNotNull(configuredStaleJob)
        assertNotNull(configuredReminderJob)
    }

    @AfterEach fun tearDown() {
        createdUsers.forEach { user ->
            jdbc.update("delete from outbox_event_consumptions where event_id in (select id from outbox_events where payload::text like ?)", "%$user%")
            jdbc.update("delete from outbox_events where payload::text like ?", "%$user%")
            jdbc.update("delete from subscription_renewal_reminders where subscription_id in (select id from user_subscriptions where user_id = ?)", user)
            jdbc.update("delete from subscription_events where user_id = ?", user)
            jdbc.update("delete from user_subscriptions where user_id = ?", user)
            jdbc.update("delete from payment_attempts where invoice_id in (select id from invoices where user_id = ?)", user)
            jdbc.update("delete from invoices where user_id = ?", user)
            jdbc.update("delete from users where id = ?", user)
        }
        createdPlans.forEach { jdbc.update("delete from subscription_plans where id = ?", it) }
    }

    @Test fun `expiry enters grace, cancelled expiry bypasses grace, and grace exit is idempotent`() {
        val gracePlan = plan(7)
        val paidUser = user(); val cancelledUser = user(); val now = time.now()
        val graceSubscription = subscription(paidUser, gracePlan, SubscriptionStatus.ACTIVE, now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)))
        val cancelledSubscription = subscription(cancelledUser, gracePlan, SubscriptionStatus.ACTIVE, now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)), cancelled = true)

        lifecycle.processPeriodExpiry(graceSubscription.id!!)
        lifecycle.processPeriodExpiry(cancelledSubscription.id!!)
        val inGrace = subscriptions.findById(graceSubscription.id!!).get()
        assertEquals(SubscriptionStatus.GRACE_PERIOD, inGrace.status)
        assertEquals(graceSubscription.periodEnd!!.plus(Duration.ofDays(7)), inGrace.gracePeriodEnd)
        assertEquals(SubscriptionStatus.EXPIRED, subscriptions.findById(cancelledSubscription.id!!).get().status)
        lifecycle.processPeriodExpiry(graceSubscription.id!!)
        assertEquals(1, jdbc.queryForObject("select count(*) from subscription_events where user_id = ? and transition_type = 'GRACE_ENTRY'", Int::class.java, paidUser))

        jdbc.update("update user_subscriptions set grace_period_end = now() - interval '1 second' where id = ?", graceSubscription.id)
        lifecycle.processGraceExit(graceSubscription.id!!)
        lifecycle.processGraceExit(graceSubscription.id!!)
        assertEquals(SubscriptionStatus.EXPIRED, subscriptions.findById(graceSubscription.id!!).get().status)
        assertEquals(1, jdbc.queryForObject("select count(*) from subscription_events where user_id = ? and transition_type = 'GRACE_EXIT_FAILURE'", Int::class.java, paidUser))
    }

    @Test fun `stale cleanup changes only old pending attempts`() {
        val plan = plan(0); val owner = user(); val invoiceId = invoice(owner, plan)
        val oldPending = attempt(invoiceId, PaymentAttemptStatus.PENDING, time.now().minus(Duration.ofHours(2)))
        val verifyPending = attempt(invoiceId, PaymentAttemptStatus.VERIFY_PENDING, time.now().minus(Duration.ofHours(2)))
        val recentPending = attempt(invoiceId, PaymentAttemptStatus.PENDING, time.now().minus(Duration.ofMinutes(5)))
        val now = time.now()
        staleAttempts.markStaleBefore(now.minus(Duration.ofHours(1)), now, batchSize = 100)
        assertEquals(PaymentAttemptStatus.STALE, attempts.findById(oldPending.id!!).get().status)
        assertEquals(PaymentAttemptStatus.VERIFY_PENDING, attempts.findById(verifyPending.id!!).get().status)
        assertEquals(PaymentAttemptStatus.PENDING, attempts.findById(recentPending.id!!).get().status)
    }

    @Test fun `renewal reminders are unique per period and outbox unsupported events fail visibly`() {
        val plan = plan(7); val owner = user(); val sub = subscription(owner, plan, SubscriptionStatus.ACTIVE, time.now(), time.now().plus(Duration.ofDays(2)))
        assertEquals(RenewalReminderRecordResult.CREATED, reminders.record(sub))
        assertEquals(RenewalReminderRecordResult.DUPLICATE, reminders.record(sub))
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from subscription_renewal_reminders where subscription_id = ?",
                Int::class.java,
                sub.id
            )
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from outbox_events where event_type = 'subscription.RENEWAL_REMINDER' and payload::text like ?",
                Int::class.java,
                "%$owner%"
            )
        )
        val reminderEvent = outbox.findAll()
            .single { it.eventType == "subscription.RENEWAL_REMINDER" && it.payload.contains(owner.toString()) }
        OutboxPublisherJob(outbox, deliveryService, listOf(reminderConsumer), time, metrics, true, 100, 5).run()
        assertEquals(OutboxStatus.PUBLISHED, outbox.findById(reminderEvent.id!!).get().status)
        assertTrue(hasConsumption(reminderEvent.id!!, reminderConsumer.consumerName))

        val unsupported = outbox.save(OutboxEvent(eventType = "subscription.UNKNOWN", aggregateType = "UserSubscription", aggregateId = sub.id.toString(), payload = "{\"userId\":\"$owner\"}"))
        OutboxPublisherJob(outbox, deliveryService, emptyList(), time, metrics, true, 100, 5).run()
        assertEquals(OutboxStatus.FAILED, outbox.findById(unsupported.id!!).get().status)
        assertTrue(outbox.findById(unsupported.id!!).get().lastError!!.contains("No outbox consumer"))
    }

    @Test
    fun `invalid subscription payload records typed terminal failure`() {
        val owner = user()
        val invalidPayload = outbox.save(
            OutboxEvent(
                eventType = "subscription.FIRST_PURCHASE",
                aggregateType = "UserSubscription",
                aggregateId = "1",
                payload = "{\"userId\":\"$owner\",\"transitionType\":\"FIRST_PURCHASE\",\"planId\":\"not-a-long\",\"occurredAt\":\"2026-07-10T00:00:00Z\"}"
            )
        )

        OutboxPublisherJob(outbox, deliveryService, listOf(invalidator), time, metrics, true, 100, 5).run()

        val failed = outbox.findById(invalidPayload.id!!).get()
        assertEquals(OutboxStatus.FAILED, failed.status)
        assertEquals(5, failed.retryCount)
        assertTrue(failed.lastError!!.startsWith("InvalidOutboxPayloadException:"))
        assertFalse(hasConsumption(invalidPayload.id!!, invalidator.consumerName))
    }

    @Test fun `publisher records ledger retries failures and cleanup keeps failed events`() {
        val owner = user(); val event = outbox.save(OutboxEvent(eventType = "test.EVENT", aggregateType = "Test", aggregateId = "1", payload = "{\"userId\":\"$owner\"}"))
        val success = object : OutboxConsumer {
            override val consumerName = "test-success";
            override fun supports(eventType: String) = true;
            override fun consume(event: OutboxEvent) = Unit
        }
        OutboxPublisherJob(outbox, deliveryService, listOf(success), time, metrics, true, 100, 5).run()
        assertEquals(OutboxStatus.PUBLISHED, outbox.findById(event.id!!).get().status)
        assertTrue(hasConsumption(event.id!!, "test-success"))

        val failing = outbox.save(OutboxEvent(eventType = "test.FAIL", aggregateType = "Test", aggregateId = "2", payload = "{}"))
        val failureConsumer = object : OutboxConsumer { override val consumerName = "test-failure"; override fun supports(eventType: String) = true; override fun consume(event: OutboxEvent) { error("expected") } }
        val publisher =
            OutboxPublisherJob(outbox, deliveryService, listOf(failureConsumer), time, metrics, true, 100, 2)
        publisher.run(); assertEquals(1, outbox.findById(failing.id!!).get().retryCount)
        val retryable = outbox.findById(failing.id!!).get()
        retryable.nextRetryAt = null
        outbox.save(retryable)
        publisher.run()
        assertEquals(OutboxStatus.FAILED, outbox.findById(failing.id!!).get().status)
        jdbc.update("update outbox_events set created_at = now() - interval '8 days' where id = ?", event.id)
        cleanup.cleanup(time.now().minus(Duration.ofDays(7)))
        assertFalse(outbox.existsById(event.id!!)); assertTrue(outbox.existsById(failing.id!!))
    }

    private fun hasConsumption(eventId: Long, consumerName: String): Boolean =
        jdbc.queryForObject(
            "select count(*) from outbox_event_consumptions where event_id = ? and consumer_name = ?",
            Int::class.java,
            eventId,
            consumerName,
        ) == 1

    private fun user() = UUID.randomUUID().also { id -> createdUsers += id; jdbc.update("insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at) values (?, ?, 'hash', 'USER', 'VERIFIED', 'VERIFIED', 'ACTIVE', now(), now())", id, "jobs-$id@example.com") }
    private fun plan(grace: Int) = (jdbc.queryForObject("insert into subscription_plans (code, name, free, active, grace_period_days) values (?, 'Jobs', false, true, ?) returning id", Long::class.java, "JOBS_${System.nanoTime()}", grace)!!).also { createdPlans += it }
    private fun subscription(user: UUID, plan: Long, status: SubscriptionStatus, start: Instant, end: Instant, cancelled: Boolean = false): UserSubscription { jdbc.update("insert into user_subscriptions (user_id, plan_id, status, period_start, period_end, cancel_at_period_end) values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?)", user, plan, status.name, start.toString(), end.toString(), cancelled); return subscriptions.findByUserId(user).get() }
    private fun invoice(user: UUID, plan: Long): UUID = UUID.randomUUID().also { id -> jdbc.update("insert into invoices (id, user_id, plan_id, period_start, period_end, amount_due, currency, amount_after_discount, discount_currency, status, manual, created_at, updated_at) values (?, ?, ?, now(), now() + interval '30 days', 1, 'IRR', 1, 'IRR', 'OPEN', false, now(), now())", id, user, plan) }
    private fun attempt(invoice: UUID, status: PaymentAttemptStatus, createdAt: Instant) = attempts.save(PaymentAttempt(invoiceId = invoice, provider = PaymentProvider.PAYPING, clientRefId = UUID.randomUUID().toString(), amount = Money(BigDecimal.ONE, "IRR"), status = status, createdAt = createdAt, updatedAt = createdAt))
}
