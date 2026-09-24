package com.gyro.api.notification

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.common.outbox.*
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.application.CoreProbeOutboxPayload
import com.gyro.api.notification.application.NotificationOutboxConsumer
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.application.ProductSmsQuotaReservationService
import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.application.delivery.NotificationContentPurgeService
import com.gyro.api.notification.application.delivery.NotificationDeliveryWorker
import com.gyro.api.notification.application.delivery.NotificationDeliveryClaimService
import com.gyro.api.notification.application.delivery.NotificationProcessingFailureHandler
import com.gyro.api.notification.application.delivery.NotificationPostSendProcessingException
import com.gyro.api.notification.application.delivery.NotificationOutcomeService
import com.gyro.api.notification.application.template.NotificationTemplateCatalog
import com.gyro.api.notification.application.template.NotificationTemplateSynchronizer
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import com.gyro.api.notification.infrastructure.persistence.*
import com.gyro.api.subscription.application.LifecycleObservability
import com.gyro.api.subscription.application.job.OutboxPublisherJob
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationOutboxWriter
import com.gyro.api.subscription.application.outbox.PaymentVerifiedNotificationPayload
import com.gyro.api.subscription.domain.Money
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

@Import(TestcontainersConfiguration::class, NotificationAdapterTestConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.security.verification-code-pepper=test-pepper",
        "app.rate-limit.enabled=false",
        "app.billing.lifecycle.jobs-enabled=false",
        "app.notification.jobs-enabled=false",
        "app.notification.worker-identity=notification-core-test",
        "app.notification.product-sms-daily-global-cap=10",
        "app.notification.product-sms-mandatory-user-daily-cap=5",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.timeout=2s",
    ],
)
class NotificationCoreIntegrationTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val outboxWriter: OutboxWriter,
    @Autowired private val outboxEvents: OutboxEventRepository,
    @Autowired private val outboxDelivery: OutboxDeliveryService,
    @Autowired private val notificationConsumer: NotificationOutboxConsumer,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val intents: NotificationIntentRepository,
    @Autowired private val deliveries: NotificationDeliveryRepository,
    @Autowired private val attempts: NotificationAttemptRepository,
    @Autowired private val worker: NotificationDeliveryWorker,
    @Autowired private val claimService: NotificationDeliveryClaimService,
    @Autowired private val processingFailureHandler: NotificationProcessingFailureHandler,
    @Autowired private val attemptStore: NotificationAttemptStore,
    @Autowired private val contentPurgeService: NotificationContentPurgeService,
    @Autowired private val lifecycleMetrics: LifecycleObservability,
    @Autowired private val time: TimeProvider,
    @Autowired private val templateSynchronizer: NotificationTemplateSynchronizer,
    @Autowired private val templateCatalog: NotificationTemplateCatalog,
    @Autowired private val controlledAdapter: ControlledNotificationAdapter,
    @Autowired private val queueRepository: NotificationDeliveryQueueRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val meterRegistries: ObjectProvider<MeterRegistry>,
    @Autowired private val smsQuotaReservations: ProductSmsQuotaReservationService,
    @Autowired private val outcomes: NotificationOutcomeService,
    @Autowired private val notificationMetrics: NotificationMetrics,
) {
    private val users = mutableSetOf<UUID>()
    private val eventIds = mutableSetOf<Long>()

    @AfterEach
    fun tearDown() {
        controlledAdapter.reset()
        jdbc.update("delete from notification_sms_daily_quotas")
        users.forEach { userId ->
            jdbc.update("delete from notification_intents where user_id = ?", userId)
        }
        eventIds.forEach { eventId ->
            jdbc.update("delete from outbox_event_consumptions where event_id = ?", eventId)
            jdbc.update("delete from outbox_events where id = ?", eventId)
        }
        users.forEach { userId -> jdbc.update("delete from users where id = ?", userId) }
        templateCatalog.definitions.forEach { definition ->
            jdbc.update(
                "update notification_templates set content_hash = ? where template_key = ? and version = ? and channel = ? and locale = ?",
                definition.contentHash(), definition.key, definition.version, definition.channel.name, definition.locale,
            )
        }
    }

    @Test
    fun `durable probe creates one terminal notification despite outbox replay`() {
        val userId = createUser()
        val now = time.now()
        val event = outboxWriter.write(
            OutboxWriteRequest(
                eventType = NotificationOutboxConsumer.CORE_PROBE_EVENT,
                aggregateType = "NotificationCoreProbe",
                aggregateId = userId.toString(),
                payload = CoreProbeOutboxPayload(
                    recipientUserId = userId,
                    idempotencyKey = "core-probe:$userId",
                    occurredAt = now,
                    scheduledAt = now,
                    expiresAt = now.plus(Duration.ofMinutes(10)),
                    requestId = "probe-${UUID.randomUUID()}",
                ),
            ),
        ).also { eventIds += requireNotNull(it.id) }
        val publisher = publisher()

        publisher.run()
        val published = outboxEvents.findById(requireNotNull(event.id)).orElseThrow()
        assertEquals(OutboxStatus.PUBLISHED, published.status)

        published.status = OutboxStatus.PENDING
        published.publishedAt = null
        outboxEvents.saveAndFlush(published)
        publisher.run()

        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_event_consumptions where event_id = ? and consumer_name = ?", Int::class.java, event.id, notificationConsumer.consumerName))
        val intent = intents.findAllByUserId(userId).single()
        val delivery = deliveries.findAllByIntentId(intent.id).single()
        assertEquals(NotificationIntentStatus.ROUTED, intent.status)
        assertEquals(0, attempts.countByDeliveryId(delivery.id))

        assertEquals(1, worker.processBatch())
        assertEquals(0, worker.processBatch())

        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(delivery.id).orElseThrow().status)
        assertEquals(NotificationIntentStatus.COMPLETED, intents.findById(intent.id).orElseThrow().status)
        assertEquals(1, attempts.countByDeliveryId(delivery.id))
        assertEquals(AdapterOutcome.SUCCESS, attempts.findAllByDeliveryId(delivery.id).single().outcome)
    }

    @Test
    fun `SMS global quota is exact under concurrent reservations`() {
        val accepted = reserveSmsQuotaConcurrently((0 until 100).map { UUID.randomUUID() })

        assertEquals(10, accepted)
        assertEquals(
            10,
            jdbc.queryForObject(
                "select used_count from notification_sms_daily_quotas where quota_key = 'global'",
                Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from notification_sms_daily_quotas where used_count < 0 or (quota_key like 'user:%' and used_count > 5)",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `SMS user quota is exact under concurrent reservations`() {
        val userId = UUID.randomUUID()
        val accepted = reserveSmsQuotaConcurrently(List(100) { userId })

        assertEquals(5, accepted)
        assertEquals(
            5,
            jdbc.queryForObject(
                "select used_count from notification_sms_daily_quotas where quota_key = ?",
                Int::class.java,
                "user:$userId",
            ),
        )
        assertEquals(
            5,
            jdbc.queryForObject(
                "select used_count from notification_sms_daily_quotas where quota_key = 'global'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `failed mandatory receipts are exposed through alertable gauges`() {
        val userId = createUser()
        val now = time.now()
        val intentId = notificationService.create(
            NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.PAYMENT_VERIFIED,
                templateData = mapOf(
                    "amount" to TemplateVariableValue.Number(java.math.BigDecimal("125000")),
                    "currency" to TemplateVariableValue.Text("IRR"),
                ),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofHours(24)),
                idempotencyKey = "failed-payment-receipt-gauge:$userId",
                requestId = "payment-receipt-gauge-$userId",
                sourceType = "INTEGRATION_TEST",
                sourceReference = userId.toString(),
            ),
        )
        val delivery = deliveries.findAllByIntentId(intentId).single()
        delivery.status = NotificationDeliveryStatus.PERMANENT_FAILURE
        delivery.reason = NotificationReason.PROVIDER_PERMANENT
        deliveries.saveAndFlush(delivery)
        outcomes.recompute(intentId)

        notificationMetrics.refreshQueue()

        val registry = meterRegistries.ifAvailable ?: error("MeterRegistry is required")
        assertEquals(1.0, registry.get("gyro.notifications.mandatory_receipts.failed").gauge().value())
        assertTrue(registry.get("gyro.notifications.mandatory_receipts.oldest_failure_age_seconds").gauge().value() >= 0)
    }

    @Test
    fun `verified payment routes to email when both verified endpoints are available`() {
        val userId = createUser()
        jdbc.update(
            "update users set phone_number = ?, phone_verification_status = 'VERIFIED' where id = ?",
            "+989123456789", userId,
        )
        val now = time.now()
        val event = outboxWriter.write(
            OutboxWriteRequest(
                eventType = PaymentVerifiedNotificationOutboxWriter.PAYMENT_VERIFIED_EVENT,
                aggregateType = "PaymentAttempt",
                aggregateId = UUID.randomUUID().toString(),
                payload = PaymentVerifiedNotificationPayload(
                    userId = userId,
                    paymentAttemptId = UUID.randomUUID(),
                    amount = Money(java.math.BigDecimal("125000"), "IRR"),
                    occurredAt = now,
                    requestId = "payment-${UUID.randomUUID()}",
                ),
            ),
        ).also { eventIds += requireNotNull(it.id) }

        publisher().run()

        val intent = intents.findAllByUserId(userId).single { it.type == NotificationType.PAYMENT_VERIFIED }
        val delivery = deliveries.findAllByIntentId(intent.id).single()
        assertEquals(NotificationChannel.EMAIL, delivery.channel)
        assertEquals("account:email", delivery.endpointReference)
        assertEquals("smtp-email", delivery.adapterKey)
        assertTrue(delivery.renderedPlainBody!!.contains("125000 IRR"))

        assertEquals(1, worker.processBatch())
        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(delivery.id).orElseThrow().status)
    }

    @Test
    fun `verified payment routes to SMS when email is unavailable`() {
        val userId = createUser()
        jdbc.update(
            "update users set email_verification_status = 'UNVERIFIED', phone_number = ?, phone_verification_status = 'VERIFIED' where id = ?",
            "+989123456789", userId,
        )
        val now = time.now()
        val paymentAttemptId = UUID.randomUUID()
        val event = outboxWriter.write(
            OutboxWriteRequest(
                eventType = PaymentVerifiedNotificationOutboxWriter.PAYMENT_VERIFIED_EVENT,
                aggregateType = "PaymentAttempt",
                aggregateId = paymentAttemptId.toString(),
                payload = PaymentVerifiedNotificationPayload(userId, paymentAttemptId, Money(java.math.BigDecimal("125000"), "IRR"), now, "payment-${UUID.randomUUID()}"),
            ),
        ).also { eventIds += requireNotNull(it.id) }

        publisher().run()

        val intent = intents.findAllByUserId(userId).single { it.type == NotificationType.PAYMENT_VERIFIED }
        val delivery = deliveries.findAllByIntentId(intent.id).single()
        assertEquals(NotificationChannel.SMS, delivery.channel)
        assertEquals("account:phone", delivery.endpointReference)
        assertEquals("sms-ir", delivery.adapterKey)
        assertEquals(1, worker.processBatch())
        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(delivery.id).orElseThrow().status)
    }

    @Test
    fun `expired claim is recovered and completed once`() {
        val userId = createUser()
        val now = time.now()
        val intentId = notificationService.create(
            com.gyro.api.notification.domain.NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.CORE_PROBE,
                templateData = emptyMap(),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofMinutes(10)),
                idempotencyKey = "claim-recovery:$userId",
                requestId = "claim-${UUID.randomUUID()}",
                sourceType = "INTEGRATION_TEST",
                sourceReference = UUID.randomUUID().toString(),
            ),
        )
        val delivery = deliveries.findAllByIntentId(intentId).single()
        jdbc.update(
            """
            update notification_deliveries set status = 'CLAIMED', claim_owner = 'dead-worker', claimed_at = ?,
                claim_expires_at = ?, claim_token = gen_random_uuid(), updated_at = ? where id = ?
            """.trimIndent(),
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.minusSeconds(120)),
            delivery.id,
        )

        assertEquals(1, worker.processBatch())

        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(delivery.id).orElseThrow().status)
        assertEquals(1, attempts.countByDeliveryId(delivery.id))
    }

    @Test
    fun `Telegram deliveries stay unclaimed when the adapter is disabled`() {
        val delivery = createDirectProbe()
        jdbc.update(
            "update notification_deliveries set channel = 'TELEGRAM' where id = ?",
            delivery.id,
        )

        // The single API process adds TELEGRAM to its claim set only when the adapter is enabled.
        assertNull(claimService.claimNext())

        jdbc.update(
            "update notification_deliveries set channel = 'EMAIL' where id = ?",
            delivery.id,
        )
        val claim = claimService.claimNext() ?: error("Expected the EMAIL delivery to be claimable")
        assertEquals(delivery.id, claim.deliveryId)
    }

    @Test
    fun `enabled single API channel set claims an existing Telegram delivery`() {
        val delivery = createDirectProbe()
        jdbc.update(
            "update notification_deliveries set channel = 'TELEGRAM' where id = ?",
            delivery.id,
        )
        val enabledProperties = NotificationProperties(telegramEnabled = true)
        val now = time.now()

        val claims = queueRepository.claimDueDeliveries(
            now = now,
            batchSize = 1,
            owner = "telegram-enabled-api",
            claimExpiresAt = now.plus(enabledProperties.claimDuration),
            channels = enabledProperties.deliveryChannels.map { it.name }.toSet(),
        )

        assertEquals(listOf(delivery.id), claims.map { it.deliveryId })
    }

    @Test
    fun `concurrent claimers reserve disjoint ordered batches`() {
        val now = time.now()
        val orderedIds = (0 until 8).map { index ->
            val delivery = createDirectProbe()
            jdbc.update(
                "update notification_deliveries set due_at = ?, next_attempt_at = null where id = ?",
                Timestamp.from(now.minusSeconds((800 - index).toLong())),
                delivery.id,
            )
            delivery.id
        }
        val rank = orderedIds.withIndex().associate { (index, id) -> id to index }
        val transactionTemplate = TransactionTemplate(transactionManager)
        val claimedBeforeCommit = CountDownLatch(2)
        val releaseTransactions = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        fun claim(owner: String) = executor.submit<List<UUID>> {
            transactionTemplate.execute {
                val claimed = queueRepository.claimDueDeliveries(
                    now = now,
                    batchSize = 4,
                    owner = owner,
                    claimExpiresAt = now.plusSeconds(60),
                    channels = NotificationChannel.entries.map { it.name }.toSet(),
                ).map { it.deliveryId }
                claimedBeforeCommit.countDown()
                check(releaseTransactions.await(10, TimeUnit.SECONDS))
                claimed
            } ?: emptyList()
        }

        val first = claim("claim-test-worker-1")
        val second = claim("claim-test-worker-2")
        try {
            assertTrue(
                claimedBeforeCommit.await(10, TimeUnit.SECONDS),
                "Both transactions should claim a batch before either transaction commits",
            )
        } finally {
            releaseTransactions.countDown()
        }

        try {
            val firstBatch = first.get(10, TimeUnit.SECONDS)
            val secondBatch = second.get(10, TimeUnit.SECONDS)

            assertEquals(4, firstBatch.size)
            assertEquals(4, secondBatch.size)
            assertTrue(firstBatch.toSet().intersect(secondBatch.toSet()).isEmpty())
            assertEquals(orderedIds.toSet(), (firstBatch + secondBatch).toSet())
            assertEquals(firstBatch.sortedBy { rank.getValue(it) }, firstBatch)
            assertEquals(secondBatch.sortedBy { rank.getValue(it) }, secondBatch)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent notification creation returns one intent and one delivery`() {
        val userId = createUser()
        val now = time.now()
        val request = NotificationRequest(
            recipientUserId = userId,
            type = NotificationType.CORE_PROBE,
            templateData = emptyMap(),
            occurredAt = now,
            scheduledAt = now,
            expiresAt = now.plus(Duration.ofMinutes(10)),
            idempotencyKey = "concurrent-create:$userId",
            requestId = "concurrent-${UUID.randomUUID()}",
            sourceType = "INTEGRATION_TEST",
            sourceReference = UUID.randomUUID().toString(),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                executor.submit<UUID> {
                    check(start.await(10, TimeUnit.SECONDS))
                    notificationService.create(request)
                }
            }
            start.countDown()
            val ids = futures.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, ids.toSet().size)
            assertEquals(1, intents.findAllByUserId(userId).size)
            assertEquals(1, deliveries.findAllByIntentId(ids.first()).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `provider success survives stale finalization without a second provider delivery`() {
        val delivery = createDirectProbe()
        jdbc.update("update notification_deliveries set adapter_key = ? where id = ?", controlledAdapter.adapterKey, delivery.id)
        val providerAccepted = CountDownLatch(1)
        val releaseFirstWorker = CountDownLatch(1)
        controlledAdapter.blockAfterAccept(providerAccepted, releaseFirstWorker)
        val executor = Executors.newSingleThreadExecutor()
        val firstWorker = executor.submit<Int> { worker.processBatch() }

        try {
            assertTrue(providerAccepted.await(10, TimeUnit.SECONDS))
            jdbc.update(
                "update notification_deliveries set claim_expires_at = ? where id = ?",
                Timestamp.from(time.now().minusSeconds(1)),
                delivery.id,
            )

            assertEquals(1, worker.processBatch())
            releaseFirstWorker.countDown()
            assertEquals(1, firstWorker.get(10, TimeUnit.SECONDS))

            val completed = deliveries.findById(delivery.id).orElseThrow()
            assertEquals(NotificationDeliveryStatus.DELIVERED, completed.status)
            assertEquals(1, controlledAdapter.providerDeliveries.get())
            assertEquals(1, attempts.countByDeliveryId(delivery.id))
            assertEquals(1, attempts.findAllByDeliveryId(delivery.id).single().attemptNumber)
            assertEquals("gyro-notification-${delivery.id}", completed.providerRequestId)
        } finally {
            releaseFirstWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `retention purge removes content while preserving tombstoned delivery evidence`() {
        val delivery = createDirectProbe()
        assertEquals(1, worker.processBatch())
        jdbc.update(
            "update users set email = null, status = 'DELETED', deactivated_at = now(), updated_at = now() where id = ?",
            intents.findById(delivery.intentId).orElseThrow().userId,
        )
        jdbc.update(
            "update notification_deliveries set content_purge_at = ? where id = ?",
            Timestamp.from(time.now().minusSeconds(1)),
            delivery.id,
        )

        assertEquals(1, contentPurgeService.purgeBatch())

        val content = jdbc.queryForMap(
            "select rendered_subject, rendered_plain_body, rendered_html_body, content_purged_at from notification_deliveries where id = ?",
            delivery.id,
        )
        assertNull(content["rendered_subject"])
        assertNull(content["rendered_plain_body"])
        assertNull(content["rendered_html_body"])
        assertNotNull(content["content_purged_at"])
        assertNull(jdbc.queryForMap("select template_data from notification_intents where id = ?", delivery.intentId)["template_data"])
        assertEquals(1, attempts.countByDeliveryId(delivery.id))
        assertTrue(deliveries.existsById(delivery.id))
        assertTrue(intents.existsById(delivery.intentId))
    }

    @Test
    fun `concurrent outbox delivery invokes a consumer once`() {
        val event = createTestOutboxEvent()
        val invocations = AtomicInteger()
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val secondDeliveryStarted = CountDownLatch(1)
        val consumer = object : OutboxConsumer {
            override val consumerName = "concurrent-delivery-test"
            override fun supports(eventType: String) = true
            override fun consume(event: OutboxEvent) {
                invocations.incrementAndGet()
                consumerEntered.countDown()
                check(releaseConsumer.await(10, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        val first = executor.submit { outboxDelivery.deliver(event, consumer) }

        try {
            assertTrue(consumerEntered.await(10, TimeUnit.SECONDS))
            val second = executor.submit {
                secondDeliveryStarted.countDown()
                outboxDelivery.deliver(event, consumer)
            }
            assertTrue(secondDeliveryStarted.await(10, TimeUnit.SECONDS))

            try {
                assertThrows(TimeoutException::class.java) {
                    second.get(250, TimeUnit.MILLISECONDS)
                }
                assertEquals(1, invocations.get())
            } finally {
                releaseConsumer.countDown()
            }

            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertEquals(1, invocations.get())
            assertEquals(
                1,
                jdbc.queryForObject(
                    "select count(*) from outbox_event_consumptions where event_id = ? and consumer_name = ?",
                    Int::class.java,
                    event.id,
                    consumer.consumerName,
                ),
            )
        } finally {
            releaseConsumer.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed outbox delivery rolls back its reservation`() {
        val event = createTestOutboxEvent()
        val consumerName = "rollback-delivery-test"
        val failingConsumer = object : OutboxConsumer {
            override val consumerName = consumerName
            override fun supports(eventType: String) = true
            override fun consume(event: OutboxEvent) = throw IllegalStateException("consumer failed")
        }

        assertThrows(IllegalStateException::class.java) {
            outboxDelivery.deliver(event, failingConsumer)
        }
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from outbox_event_consumptions where event_id = ? and consumer_name = ?",
                Int::class.java,
                event.id,
                consumerName,
            ),
        )

        val successfulCalls = AtomicInteger()
        outboxDelivery.deliver(
            event,
            object : OutboxConsumer {
                override val consumerName = consumerName
                override fun supports(eventType: String) = true
                override fun consume(event: OutboxEvent) {
                    successfulCalls.incrementAndGet()
                }
            },
        )

        assertEquals(1, successfulCalls.get())
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from outbox_event_consumptions where event_id = ? and consumer_name = ?",
                Int::class.java,
                event.id,
                consumerName,
            ),
        )
    }

    @Test
    fun `template synchronization is idempotent and rejects immutable hash drift`() {
        val definition = templateCatalog.definitions.single { it.key == "notification-core-probe" }

        templateSynchronizer.sync()
        templateSynchronizer.sync()
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from notification_templates where template_key = ? and version = ? and channel = ? and locale = ?",
                Int::class.java,
                definition.key,
                definition.version,
                definition.channel.name,
                definition.locale,
            ),
        )

        jdbc.update(
            "update notification_templates set content_hash = ? where template_key = ? and version = ? and channel = ? and locale = ?",
            "0".repeat(64),
            definition.key,
            definition.version,
            definition.channel.name,
            definition.locale,
        )
        val exception = assertThrows(IllegalStateException::class.java) { templateSynchronizer.sync() }
        assertTrue(exception.message!!.contains("template drift"))
    }

    @Test
    fun `transient adapter exhaustion becomes a bounded dead letter outcome`() {
        val delivery = createDirectProbe()
        controlledAdapter.result.set(AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT))
        jdbc.update("update notification_deliveries set adapter_key = ? where id = ?", controlledAdapter.adapterKey, delivery.id)

        assertEquals(1, worker.processBatch())

        val failed = deliveries.findById(delivery.id).orElseThrow()
        assertEquals(NotificationDeliveryStatus.DEAD_LETTER, failed.status)
        assertEquals(NotificationReason.RETRY_EXHAUSTED, failed.reason)
        assertEquals(NotificationIntentStatus.FAILED, intents.findById(failed.intentId).orElseThrow().status)
        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, attempts.findAllByDeliveryId(delivery.id).single().outcome)
    }

    @Test
    fun `invalid endpoint result becomes permanent failure without retry`() {
        val delivery = createDirectProbe()
        controlledAdapter.result.set(AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID))
        jdbc.update("update notification_deliveries set adapter_key = ? where id = ?", controlledAdapter.adapterKey, delivery.id)

        assertEquals(1, worker.processBatch())

        val failed = deliveries.findById(delivery.id).orElseThrow()
        assertEquals(NotificationDeliveryStatus.PERMANENT_FAILURE, failed.status)
        assertEquals(NotificationReason.ENDPOINT_INVALID, failed.reason)
        assertEquals(1, attempts.countByDeliveryId(delivery.id))
    }

    @Test
    fun `adapter exceptions retain uncertainty and emit bounded operational metrics`() {
        val delivery = createDirectProbe()
        controlledAdapter.exception.set(IllegalStateException("sanitized-test-failure"))
        jdbc.update("update notification_deliveries set adapter_key = ? where id = ?", controlledAdapter.adapterKey, delivery.id)

        assertEquals(1, worker.processBatch())

        val attempt = attempts.findAllByDeliveryId(delivery.id).single()
        assertEquals(AdapterOutcome.UNKNOWN_AFTER_SEND, attempt.outcome)
        assertEquals(AdapterClassification.UNKNOWN_AFTER_SEND, attempt.classification)
        val registry = meterRegistries.ifAvailable ?: error("MeterRegistry is required")
        assertTrue(
            registry.get("gyro.notifications.adapter.exceptions")
                .tag("adapter", controlledAdapter.adapterKey)
                .tag("operation", AdapterOperation.DELIVER.name)
                .tag("exception", "ILLEGAL_STATE")
                .counter()
                .count() >= 1,
        )
    }

    @Test
    fun `worker capacity gauges expose batch and lease timing`() {
        notificationMetrics.deliveryBatchCompleted(7, Duration.ofMillis(1_250))

        val registry = meterRegistries.ifAvailable ?: error("MeterRegistry is required")
        assertEquals(7.0, registry.get("gyro.notifications.worker.batch.last_size").gauge().value())
        assertEquals(1.25, registry.get("gyro.notifications.worker.batch.last_duration_seconds").gauge().value())
        assertEquals(5.0, registry.get("gyro.notifications.worker.poll_delay_seconds").gauge().value())
        assertEquals(60.0, registry.get("gyro.notifications.worker.claim_duration_seconds").gauge().value())
    }

    @Test
    fun `queue snapshot excludes expired work and reports terminalization backlog separately`() {
        val delivery = createDirectProbe()
        val now = time.now()
        jdbc.update(
            "update notification_deliveries set due_at = ?, expires_at = ? where id = ?",
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(60)),
            delivery.id,
        )

        val snapshot = queueRepository.snapshot(now)

        assertEquals(0, snapshot.dueCount)
        assertNull(snapshot.oldestDueAt)
        assertEquals(1, snapshot.expiredActiveCount)
    }

    @Test
    fun `permanent poison delivery is dead lettered without blocking later work`() {
        val poison = createDirectProbe()
        val healthy = createDirectProbe()
        jdbc.update(
            "update notification_deliveries set adapter_key = 'missing-adapter', due_at = ? where id = ?",
            Timestamp.from(time.now().minusSeconds(60)),
            poison.id,
        )

        assertEquals(2, worker.processBatch())

        val failed = deliveries.findById(poison.id).orElseThrow()
        assertEquals(NotificationDeliveryStatus.DEAD_LETTER, failed.status)
        assertEquals(NotificationReason.PROCESSING_FAILURE, failed.reason)
        val failureAttempt = attempts.findAllByDeliveryId(poison.id).single()
        assertNotNull(failureAttempt.completedAt)
        assertEquals(AdapterOutcome.UNKNOWN_FAILURE, failureAttempt.outcome)
        assertEquals(AdapterClassification.INTERNAL_PROCESSING_FAILURE, failureAttempt.classification)
        assertEquals(NotificationIntentStatus.FAILED, intents.findById(poison.intentId).orElseThrow().status)
        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(healthy.id).orElseThrow().status)
        assertEquals(0, worker.processBatch())
    }

    @Test
    fun `transient internal failure is durably rescheduled with a completed evidence attempt`() {
        val delivery = createDirectProbe()
        val claim = claimService.claimNext() ?: error("Expected a claimed delivery")

        processingFailureHandler.handle(claim, DataAccessResourceFailureException("database unavailable"))

        val rescheduled = deliveries.findById(delivery.id).orElseThrow()
        assertEquals(NotificationDeliveryStatus.RETRY_SCHEDULED, rescheduled.status)
        assertEquals(NotificationReason.PROCESSING_FAILURE, rescheduled.reason)
        assertNotNull(rescheduled.nextAttemptAt)
        assertNull(rescheduled.claimToken)
        val attempt = attempts.findAllByDeliveryId(delivery.id).single()
        assertNotNull(attempt.completedAt)
        assertEquals(AdapterOutcome.UNKNOWN_FAILURE, attempt.outcome)
        assertEquals(AdapterClassification.INTERNAL_PROCESSING_FAILURE, attempt.classification)
        assertEquals(NotificationIntentStatus.ROUTED, intents.findById(delivery.intentId).orElseThrow().status)
    }

    @Test
    fun `transient post-send failure preserves incomplete attempt for reconciliation`() {
        val delivery = createDirectProbe()
        val claim = claimService.claimNext() ?: error("Expected a claimed delivery")
        TransactionTemplate(transactionManager).execute {
            attemptStore.begin(claim, time.now()) ?: error("Expected an attempt context")
        }

        processingFailureHandler.handle(
            claim,
            NotificationPostSendProcessingException(DataAccessResourceFailureException("commit unavailable")),
        )

        val incomplete = attempts.findAllByDeliveryId(delivery.id).single()
        assertNull(incomplete.completedAt)
        assertEquals(0, deliveries.findById(delivery.id).orElseThrow().attemptCount)
        jdbc.update(
            "update notification_deliveries set next_attempt_at = ? where id = ?",
            Timestamp.from(time.now().minusSeconds(1)),
            delivery.id,
        )

        assertEquals(1, worker.processBatch())
        assertEquals(NotificationDeliveryStatus.DELIVERED, deliveries.findById(delivery.id).orElseThrow().status)
        val reconciled = attempts.findAllByDeliveryId(delivery.id).single()
        assertNotNull(reconciled.completedAt)
        assertEquals(AdapterOutcome.SUCCESS, reconciled.outcome)
        assertEquals(1, reconciled.attemptNumber)
    }

    @Test
    fun `expired due delivery terminalizes without invoking an adapter`() {
        val delivery = createDirectProbe()
        val now = time.now()
        jdbc.update(
            "update notification_deliveries set due_at = ?, expires_at = ? where id = ?",
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(60)),
            delivery.id,
        )

        assertEquals(0, worker.processBatch())

        assertEquals(NotificationDeliveryStatus.EXPIRED, deliveries.findById(delivery.id).orElseThrow().status)
        assertEquals(NotificationIntentStatus.EXPIRED, intents.findById(delivery.intentId).orElseThrow().status)
        assertEquals(0, attempts.countByDeliveryId(delivery.id))
    }

    private fun publisher() = OutboxPublisherJob(
        outboxEvents,
        outboxDelivery,
        listOf(notificationConsumer),
        time,
        lifecycleMetrics,
        true,
        100,
        5,
    )

    private fun createTestOutboxEvent(): OutboxEvent = outboxWriter.write(
        OutboxWriteRequest(
            eventType = "TEST_CONCURRENT_DELIVERY",
            aggregateType = "IntegrationTest",
            aggregateId = UUID.randomUUID().toString(),
            payload = mapOf("probe" to UUID.randomUUID().toString()),
        ),
    ).also { eventIds += requireNotNull(it.id) }

    private fun createDirectProbe(): NotificationDeliveryEntity {
        val userId = createUser()
        val now = time.now()
        val intentId = notificationService.create(
            com.gyro.api.notification.domain.NotificationRequest(
                recipientUserId = userId,
                type = NotificationType.CORE_PROBE,
                templateData = emptyMap(),
                occurredAt = now,
                scheduledAt = now,
                expiresAt = now.plus(Duration.ofMinutes(10)),
                idempotencyKey = "direct:${UUID.randomUUID()}",
                requestId = "direct-${UUID.randomUUID()}",
                sourceType = "INTEGRATION_TEST",
                sourceReference = UUID.randomUUID().toString(),
            ),
        )
        val delivery = deliveries.findAllByIntentId(intentId).single()
        // Direct delivery tests replace the production `log-only` adapter with controlled adapters.
        // Use a verified account endpoint so preflight reaches those adapters rather than rejecting
        // the internal probe endpoint, which is deliberately restricted to `log-only` in production.
        jdbc.update(
            "update notification_deliveries set endpoint_reference = ? where id = ?",
            "account:email",
            delivery.id,
        )
        return deliveries.findById(delivery.id).orElseThrow()
    }

    private fun createUser(): UUID = UUID.randomUUID().also { userId ->
        users += userId
        jdbc.update(
            """
            insert into users (id, email, password_hash, role, email_verification_status, phone_verification_status, status, created_at, updated_at)
            values (?, ?, 'hash', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "notification-$userId@example.com",
        )
    }

    private fun reserveSmsQuotaConcurrently(userIds: List<UUID>): Int {
        val executor = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        return try {
            val reservations = userIds.map { userId ->
                executor.submit<Boolean> {
                    check(start.await(10, TimeUnit.SECONDS))
                    smsQuotaReservations.reserveProviderAttempt(userId)
                }
            }
            start.countDown()
            reservations.count { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}

class ControlledNotificationAdapter : NotificationChannelAdapter {
    override val adapterKey = "test-controlled"
    val result = AtomicReference(AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS))
    val providerDeliveries = AtomicInteger()
    val exception = AtomicReference<RuntimeException?>()
    private val recordedResults = ConcurrentHashMap<String, AdapterResult>()
    private val entered = AtomicReference<CountDownLatch?>()
    private val release = AtomicReference<CountDownLatch?>()

    fun blockAfterAccept(entered: CountDownLatch, release: CountDownLatch) {
        this.entered.set(entered)
        this.release.set(release)
    }

    fun reset() {
        result.set(AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS))
        providerDeliveries.set(0)
        exception.set(null)
        recordedResults.clear()
        entered.set(null)
        release.set(null)
    }

    override fun reconcile(notification: RenderedNotification): AdapterResult? = recordedResults[notification.providerRequestId]

    override fun deliver(notification: RenderedNotification): AdapterResult {
        exception.get()?.let { throw it }
        val outcome = recordedResults.computeIfAbsent(notification.providerRequestId) {
            providerDeliveries.incrementAndGet()
            result.get()
        }
        entered.get()?.countDown()
        release.get()?.let { check(it.await(10, TimeUnit.SECONDS)) }
        return outcome
    }
}

@TestConfiguration(proxyBeanMethods = false)
class NotificationAdapterTestConfiguration {
    @Bean
    fun controlledNotificationAdapter() = ControlledNotificationAdapter()

    @Bean
    fun paymentEmailNotificationAdapter() = PaymentChannelAdapter("smtp-email")

    @Bean
    fun paymentSmsNotificationAdapter() = PaymentChannelAdapter("sms-ir")
}

class PaymentChannelAdapter(override val adapterKey: String) : NotificationChannelAdapter {
    override fun reconcile(notification: RenderedNotification): AdapterResult = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)
    override fun deliver(notification: RenderedNotification): AdapterResult = AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS)
}
