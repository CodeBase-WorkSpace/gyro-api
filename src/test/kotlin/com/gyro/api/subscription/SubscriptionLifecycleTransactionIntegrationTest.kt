package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.outbox.OutboxEventRepository
import com.gyro.api.subscription.application.InvoiceService
import com.gyro.api.subscription.application.SubscriptionEventService
import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.application.outbox.OutboxEventWriter
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import com.gyro.api.subscription.domain.EventSourceType
import com.gyro.api.subscription.domain.UserSubscription
import com.gyro.api.subscription.infrastructure.SubscriptionEventRepository
import com.gyro.api.subscription.infrastructure.UserSubscriptionRepository
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class SubscriptionLifecycleTransactionIntegrationTest(
    @Autowired private val lifecycleService: SubscriptionLifecycleService,
    @Autowired private val invoiceService: InvoiceService,
    @Autowired private val userSubscriptionRepository: UserSubscriptionRepository,
    @Autowired private val eventRepository: SubscriptionEventRepository,
    @Autowired private val outboxEventRepository: OutboxEventRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @MockitoSpyBean
    private lateinit var outboxEventWriter: OutboxEventWriter

    @MockitoSpyBean
    private lateinit var eventService: SubscriptionEventService

    private var priceId: Long = 0

    @BeforeEach
    fun setUp() {
        val planId = seedPlan()
        priceId = seedPrice(planId)
    }

    @Test
    fun `standalone paid invoice commits subscription event and outbox atomically`() {
        val userId = createUser()
        val paidInvoice = invoiceService.createInvoice(userId, priceId).let { invoice ->
            invoiceService.markPaid(invoice.id!!)
        }

        val subscription = lifecycleService.applyPaidInvoice(paidInvoice)

        assertTrue(userSubscriptionRepository.findByUserId(userId).isPresent)
        assertTrue(
            eventRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .any { it.sourceId == paidInvoice.id.toString() },
        )
        assertTrue(
            outboxEventRepository.findAll().any {
                it.aggregateId == subscription.id.toString() && it.eventType == "subscription.FIRST_PURCHASE"
            },
        )
    }

    @Test
    fun `standalone paid invoice rolls back earlier writes when outbox creation fails`() {
        val userId = createUser()
        val paidInvoice = invoiceService.createInvoice(userId, priceId).let { invoice ->
            invoiceService.markPaid(invoice.id!!)
        }
        val outboxCountBefore = outboxEventRepository.count()
        doThrow(ForcedLifecycleFailure())
            .`when`(outboxEventWriter)
            .writeSubscriptionEvent(anyString(), anySubscriptionEventPayload())

        assertThrows(ForcedLifecycleFailure::class.java) {
            lifecycleService.applyPaidInvoice(paidInvoice)
        }

        assertFalse(userSubscriptionRepository.findByUserId(userId).isPresent)
        assertTrue(eventRepository.findByUserIdOrderByCreatedAtDesc(userId).isEmpty())
        assertEquals(outboxCountBefore, outboxEventRepository.count())
    }

    @Test
    fun `paid invoice participates in a caller transaction and rolls back with it`() {
        val userId = createUser()
        val paidInvoice = invoiceService.createInvoice(userId, priceId).let { invoice ->
            invoiceService.markPaid(invoice.id!!)
        }
        val outboxCountBefore = outboxEventRepository.count()

        assertThrows(ForcedLifecycleFailure::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                lifecycleService.applyPaidInvoice(paidInvoice)
                throw ForcedLifecycleFailure()
            }
        }

        assertFalse(userSubscriptionRepository.findByUserId(userId).isPresent)
        assertTrue(eventRepository.findByUserIdOrderByCreatedAtDesc(userId).isEmpty())
        assertEquals(outboxCountBefore, outboxEventRepository.count())
    }

    @Test
    fun `standalone optimistic retry starts a fresh transaction`() {
        val userId = createUser()
        val paidInvoice = invoiceService.createInvoice(userId, priceId).let { invoice ->
            invoiceService.markPaid(invoice.id!!)
        }
        val transactionResources = mutableListOf<Any>()
        var attempt = 0
        doAnswer { invocation ->
            transactionResources += requireNotNull(
                TransactionSynchronizationManager.getResource(entityManagerFactory),
            )
            if (attempt++ == 0) {
                throw ObjectOptimisticLockingFailureException(UserSubscription::class.java, userId)
            }
            invocation.callRealMethod()
        }.`when`(eventService).alreadyProcessedSource(
            EventSourceType.PAYPING,
            paidInvoice.id.toString(),
        )

        lifecycleService.applyPaidInvoice(paidInvoice)

        assertEquals(2, transactionResources.size)
        assertNotSame(transactionResources[0], transactionResources[1])
        assertTrue(userSubscriptionRepository.findByUserId(userId).isPresent)
    }

    @Test
    fun `paid invoice does not retry optimistic failure inside caller transaction`() {
        val userId = createUser()
        val paidInvoice = invoiceService.createInvoice(userId, priceId).let { invoice ->
            invoiceService.markPaid(invoice.id!!)
        }
        val optimisticFailure = ObjectOptimisticLockingFailureException(UserSubscription::class.java, userId)
        doThrow(optimisticFailure)
            .`when`(eventService)
            .alreadyProcessedSource(EventSourceType.PAYPING, paidInvoice.id.toString())

        assertThrows(ObjectOptimisticLockingFailureException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                lifecycleService.applyPaidInvoice(paidInvoice)
            }
        }

        verify(eventService, times(1)).alreadyProcessedSource(
            EventSourceType.PAYPING,
            paidInvoice.id.toString(),
        )
        assertFalse(userSubscriptionRepository.findByUserId(userId).isPresent)
    }

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
            "transaction-${System.nanoTime()}-${id.toString().take(8)}@example.com",
        )
        return id
    }

    private fun seedPlan(): Long = jdbcTemplate.queryForObject(
        """
        insert into subscription_plans (code, name, free, active, grace_period_days)
        values (?, 'Transaction test plan', false, true, 7)
        returning id
        """.trimIndent(),
        Long::class.java,
        "TX_${System.nanoTime()}",
    ) ?: error("Expected plan id")

    private fun seedPrice(planId: Long): Long = jdbcTemplate.queryForObject(
        """
        insert into subscription_prices (plan_id, billing_period_days, amount, currency, active, valid_from)
        values (?, 30, ?, 'IRR', true, now())
        returning id
        """.trimIndent(),
        Long::class.java,
        planId,
        BigDecimal("9.99"),
    ) ?: error("Expected price id")

    // Mockito's Java matcher returns null, so provide a non-null fallback for Kotlin's parameter check.
    private fun anySubscriptionEventPayload(): SubscriptionEventPayload {
        val matcher: SubscriptionEventPayload? = any(SubscriptionEventPayload::class.java)
        return matcher ?: SubscriptionEventPayload(
            userId = UUID(0, 0),
            transitionType = "MATCHER",
            planId = 0,
            periodStart = null,
            periodEnd = null,
            occurredAt = Instant.EPOCH,
        )
    }

    private class ForcedLifecycleFailure : RuntimeException("forced lifecycle failure")
}
