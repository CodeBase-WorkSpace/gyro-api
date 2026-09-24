package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.infrastructure.StalePaymentAttemptRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.billing.lifecycle.jobs-enabled=false",
    ],
)
class StalePaymentAttemptRepositoryIntegrationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val repository: StalePaymentAttemptRepository,
) {
    @Test
    fun `concurrent cleanup workers claim each stale pending attempt once without regressing terminal rows`() {
        val fixture = createFixture()
        val now = Instant.now()
        val stalePendingIds = List(3) { index ->
            insertAttempt(
                invoiceId = fixture.invoiceId,
                status = "PENDING",
                createdAt = now.minus(Duration.ofHours((index + 2).toLong())),
            )
        }
        val verifiedId = insertAttempt(
            invoiceId = fixture.invoiceId,
            status = "VERIFIED",
            createdAt = now.minus(Duration.ofHours(5)),
        )
        val recentPendingId = insertAttempt(
            invoiceId = fixture.invoiceId,
            status = "PENDING",
            createdAt = now.minus(Duration.ofMinutes(5)),
        )
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val workerResults = List(2) {
                executor.submit<List<UUID>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    repository.markStaleBefore(now.minus(Duration.ofHours(1)), now, batchSize = 2)
                }
            }.map { future -> future.get(15, TimeUnit.SECONDS) }
            val results = workerResults.flatten()

            assertTrue(workerResults.all { it.size <= 2 }, "Each worker must honor the configured batch size")
            assertEquals(stalePendingIds.toSet(), results.toSet())
            assertEquals(stalePendingIds.size, results.size, "A stale attempt must not be claimed twice")
            assertEquals("VERIFIED", statusOf(verifiedId))
            assertEquals("PENDING", statusOf(recentPendingId))
            stalePendingIds.forEach { id -> assertEquals("STALE", statusOf(id)) }
        } finally {
            executor.shutdownNow()
            deleteFixture(fixture)
        }
    }

    private fun createFixture(): Fixture {
        val userId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            ) values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            userId,
            "stale-payment-$userId@example.com",
        )
        val planId = jdbcTemplate.queryForObject(
            """
            insert into subscription_plans (code, name, free, active, grace_period_days)
            values (?, 'Stale payment test', false, true, 7)
            returning id
            """.trimIndent(),
            Long::class.java,
            "STALE_${UUID.randomUUID().toString().replace("-", "")}",
        ) ?: error("Expected plan id")
        val invoiceId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into invoices (
                id, user_id, plan_id, period_start, period_end,
                amount_due, currency, amount_after_discount, discount_currency,
                status, manual, created_at, updated_at
            ) values (?, ?, ?, now(), now() + interval '30 days', ?, 'IRR', ?, 'IRR', 'OPEN', false, now(), now())
            """.trimIndent(),
            invoiceId,
            userId,
            planId,
            TEST_AMOUNT,
            TEST_AMOUNT,
        )
        return Fixture(userId, planId, invoiceId)
    }

    private fun insertAttempt(invoiceId: UUID, status: String, createdAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into payment_attempts (
                id, invoice_id, provider, client_ref_id, amount, currency,
                status, reversible, created_at, updated_at
            ) values (?, ?, 'PAYPING', ?, ?, 'IRR', ?, false, ?, ?)
            """.trimIndent(),
            id,
            invoiceId,
            "stale-$id",
            TEST_AMOUNT,
            status,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
        )
        return id
    }

    private fun statusOf(id: UUID): String = requireNotNull(
        jdbcTemplate.queryForObject(
            "select status from payment_attempts where id = ?",
            String::class.java,
            id,
        ),
    )

    private fun deleteFixture(fixture: Fixture) {
        jdbcTemplate.update("delete from payment_attempts where invoice_id = ?", fixture.invoiceId)
        jdbcTemplate.update("delete from invoices where id = ?", fixture.invoiceId)
        jdbcTemplate.update("delete from subscription_plans where id = ?", fixture.planId)
        jdbcTemplate.update("delete from users where id = ?", fixture.userId)
    }

    private data class Fixture(
        val userId: UUID,
        val planId: Long,
        val invoiceId: UUID,
    )

    private companion object {
        val TEST_AMOUNT: BigDecimal = BigDecimal("1990000.00")
    }
}
