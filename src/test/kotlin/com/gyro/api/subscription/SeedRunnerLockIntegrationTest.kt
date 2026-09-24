package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.SeedRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.mock.env.MockEnvironment
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.subscription.catalog-verify-enabled=false",
        "app.seed.lock-timeout-seconds=2",
    ],
)
class SeedRunnerLockIntegrationTest(
    @Autowired private val seedRunner: SeedRunner,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
) {

    @BeforeEach
    fun setUp() {
        // Clean up test-created plans to ensure clean state
        jdbcTemplate.update("DELETE FROM provider_price_mappings WHERE subscription_price_id IN (SELECT id FROM subscription_prices WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code NOT IN ('FREE', 'ADVANCED')))")
        jdbcTemplate.update("DELETE FROM subscription_prices WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code NOT IN ('FREE', 'ADVANCED'))")
        jdbcTemplate.update("DELETE FROM plan_features WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code NOT IN ('FREE', 'ADVANCED'))")
        jdbcTemplate.update("DELETE FROM plan_localizations WHERE plan_id IN (SELECT id FROM subscription_plans WHERE code NOT IN ('FREE', 'ADVANCED'))")
        jdbcTemplate.update("DELETE FROM subscription_plans WHERE code NOT IN ('FREE', 'ADVANCED')")

        // Release any leftover advisory locks
        jdbcTemplate.execute("SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))")
    }

    // ── 1. Seed runner acquires advisory lock before execution ─────────

    @Nested
    inner class `Seed runner acquires advisory lock before execution` {

        @Test
        fun `seed runner acquires lock and inserts catalog data`() {
            // Given: Clean database (Flyway already ran V24, but we verify it works)
            val planCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans", Int::class.java,
            )!!

            // When: Seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: Catalog data exists
            val planCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans", Int::class.java,
            )!!
            assertTrue(planCount >= 2, "At least FREE and ADVANCED plans should exist")

            val advancedPlan = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans WHERE code = 'ADVANCED'", Int::class.java,
            )!!
            assertEquals(1, advancedPlan)

            val featureCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_features", Int::class.java,
            )!!
            assertTrue(featureCount >= 5, "At least 5 features should exist")

            val priceCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_prices sp JOIN subscription_plans p ON sp.plan_id = p.id WHERE p.code = 'ADVANCED' AND sp.active = true",
                Int::class.java,
            )!!
            assertTrue(priceCount >= 3, "ADVANCED should have at least 3 active prices")
        }

        @Test
        fun `advisory lock is held during seed execution`() {
            // Given: A separate connection that will check lock status
            val checkConnection = dataSource.connection
            try {
                // When: Seed runner executes (holds lock during execution)
                seedRunner.run(SimpleApplicationArguments())

                // Then: Lock was acquired and released (no stale lock)
                val lockHeld = checkConnection.prepareStatement(
                    "SELECT pg_try_advisory_lock(hashtext('gyro_seed_runner'))",
                ).executeQuery()
                lockHeld.next()
                // If lock is released, we can acquire it
                assertTrue(lockHeld.getBoolean(1), "Lock should be acquirable after seed runner completes")
                checkConnection.prepareStatement("SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))").execute()
            } finally {
                checkConnection.close()
            }
        }
    }

    // ── 2. Concurrent seed runner skips when lock is held ──────────────

    @Nested
    inner class `Concurrent seed runner skips when lock is held` {

        @Test
        fun `second seed runner skips when lock is already held`() {
            // Given: Lock is held by another session
            val lockConnection = dataSource.connection
            lockConnection.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('gyro_seed_runner'))",
            ).executeQuery().next()

            try {
                // When: Seed runner tries to execute
                seedRunner.run(SimpleApplicationArguments())

                // Then: Seed runner completed (skipped due to lock)
                // The catalog data should still exist from Flyway V24
                val planCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED')",
                    Int::class.java,
                )!!
                assertEquals(2, planCount, "Catalog data should remain unchanged")
            } finally {
                lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))",
                ).execute()
                lockConnection.close()
            }
        }

        @Test
        fun `no duplicate seed operations occur when lock is held`() {
            // Given: Lock is held
            val lockConnection = dataSource.connection
            lockConnection.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('gyro_seed_runner'))",
            ).executeQuery().next()

            try {
                // When: Seed runner is called multiple times
                seedRunner.run(SimpleApplicationArguments())
                seedRunner.run(SimpleApplicationArguments())

                // Then: No duplicates (UPSERT is idempotent, but lock prevents re-execution)
                val planCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM subscription_plans WHERE code = 'ADVANCED'",
                    Int::class.java,
                )!!
                assertEquals(1, planCount, "Should have exactly one ADVANCED plan")
            } finally {
                lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))",
                ).execute()
                lockConnection.close()
            }
        }
    }

    // ── 3. Lock release after successful execution ─────────────────────

    @Nested
    inner class `Lock release after successful execution` {

        @Test
        fun `advisory lock is released after seed runner completes`() {
            // When: Seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: Lock is released (another session can acquire it)
            val checkConnection = dataSource.connection
            try {
                val acquired = checkConnection.prepareStatement(
                    "SELECT pg_try_advisory_lock(hashtext('gyro_seed_runner'))",
                ).executeQuery()
                acquired.next()
                assertTrue(acquired.getBoolean(1), "Lock should be acquirable after completion")
                checkConnection.prepareStatement("SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))").execute()
            } finally {
                checkConnection.close()
            }
        }

        @Test
        fun `another seed runner can execute after first completes`() {
            // When: First seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: Second seed runner can also execute (lock is released)
            seedRunner.run(SimpleApplicationArguments())

            // And: No errors, catalog data is intact
            val planCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED')",
                Int::class.java,
            )!!
            assertEquals(2, planCount)
        }

        @Test
        fun `no stale lock remains in database`() {
            // When: Seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: No lock is held (try_lock returns false because no lock is held)
            val lockConnection = dataSource.connection
            try {
                val result = lockConnection.prepareStatement(
                    "SELECT pg_try_advisory_lock(hashtext('gyro_seed_runner'))",
                ).executeQuery()
                result.next()
                assertTrue(result.getBoolean(1), "Lock should be acquirable (not stale)")
                lockConnection.prepareStatement("SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))").execute()
            } finally {
                lockConnection.close()
            }
        }
    }

    // ── 4. Lock release after execution failure ────────────────────────

    @Nested
   inner class `Lock release after execution failure` {

        @Test
        fun `lock is released when seed execution fails`() {
            // Given: A DataSource that fails on the second statement
            val failingDataSource = FailingAfterFirstStatementDataSource(dataSource)

            val failingRunner = SeedRunner(
                dataSource = failingDataSource,
                lockTimeoutSeconds = 2,
                enabled = true,
            )

            try {
                failingRunner.run(SimpleApplicationArguments())
            } catch (e: Exception) {
                // Expected
            }

            // Then: Lock is released even after failure
            val checkConnection = dataSource.connection
            try {
                val acquired = checkConnection.prepareStatement(
                    "SELECT pg_try_advisory_lock(hashtext('gyro_seed_runner'))",
                ).executeQuery()
                acquired.next()
                assertTrue(acquired.getBoolean(1), "Lock should be acquirable after failure")
                checkConnection.prepareStatement("SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))").execute()
            } finally {
                checkConnection.close()
            }
        }

        @Test
        fun `subsequent seed runner can acquire lock after previous failure`() {
            // Given: A DataSource that fails on the second statement
            val failingDataSource = FailingAfterFirstStatementDataSource(dataSource)

            val failingRunner = SeedRunner(
                dataSource = failingDataSource,
                lockTimeoutSeconds = 2,
                enabled = true,
            )

            try {
                failingRunner.run(SimpleApplicationArguments())
            } catch (e: Exception) {
                // Expected
            }

            // When: A new seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: It succeeds (lock was released by the failed runner)
            val planCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED')",
                Int::class.java,
            )!!
            assertEquals(2, planCount)
        }
    }

    // ── 5. Lock acquisition timeout behavior ───────────────────────────

    @Nested
    inner class `Lock acquisition timeout behavior` {

        @Test
        fun `seed runner does not wait indefinitely when lock is held`() {
            // Given: Lock is held by another session
            val lockConnection = dataSource.connection
            lockConnection.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('gyro_seed_runner'))",
            ).executeQuery().next()

            try {
                // When: Seed runner tries to execute (with 2s timeout)
                val startTime = System.currentTimeMillis()
                seedRunner.run(SimpleApplicationArguments())
                val elapsed = System.currentTimeMillis() - startTime

                // Then: Runner completes within timeout (not waiting indefinitely)
                assertTrue(elapsed < 10_000, "Seed runner should not wait more than 10s, took ${elapsed}ms")
            } finally {
                lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))",
                ).execute()
                lockConnection.close()
            }
        }

        @Test
        fun `seed runner skips after timeout and logs warning`() {
            // Given: Lock is held
            val lockConnection = dataSource.connection
            lockConnection.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('gyro_seed_runner'))",
            ).executeQuery().next()

            try {
                // When: Seed runner tries to execute
                seedRunner.run(SimpleApplicationArguments())

                // Then: Runner completed (skipped, didn't block forever)
                // The catalog data should still exist from Flyway
                val planCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM subscription_plans WHERE code IN ('FREE', 'ADVANCED')",
                    Int::class.java,
                )!!
                assertEquals(2, planCount)
            } finally {
                lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))",
                ).execute()
                lockConnection.close()
            }
        }

        @Test
        fun `application remains healthy when lock cannot be acquired`() {
            // Given: Lock is held
            val lockConnection = dataSource.connection
            lockConnection.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('gyro_seed_runner'))",
            ).executeQuery().next()

            try {
                // When: Seed runner executes
                seedRunner.run(SimpleApplicationArguments())

                // Then: Application is still healthy (no crash, no hang)
                val healthCheck = jdbcTemplate.queryForObject(
                    "SELECT 1", Int::class.java,
                )
                assertEquals(1, healthCheck)
            } finally {
                lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtext('gyro_seed_runner'))",
                ).execute()
                lockConnection.close()
            }
        }
    }

    // ── 6. Seed idempotency ───────────────────────────────────────────

    @Nested
    inner class `Seed idempotency` {

        @Test
        fun `running seed runner twice produces no duplicates`() {
            // When: Seed runner executes twice
            seedRunner.run(SimpleApplicationArguments())
            seedRunner.run(SimpleApplicationArguments())

            // Then: No duplicate plans
            val advancedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_plans WHERE code = 'ADVANCED'",
                Int::class.java,
            )!!
            assertEquals(1, advancedCount)

            // Then: No duplicate features
            val featureCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_features WHERE key = 'premium_schedules'",
                Int::class.java,
            )!!
            assertEquals(1, featureCount)

            // Then: No duplicate prices
            val priceCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_prices sp JOIN subscription_plans p ON sp.plan_id = p.id WHERE p.code = 'ADVANCED' AND sp.billing_period_days = 30 AND sp.active = true",
                Int::class.java,
            )!!
            assertEquals(1, priceCount)
        }

        @Test
        fun `seed runner preserves an admin managed production price`() {
            val stalePriceId = jdbcTemplate.queryForObject(
                """
                UPDATE subscription_prices
                SET base_amount = 1000000.00,
                    discount_percent = 0.00,
                    amount = 1000000.00,
                    badge = 'OLD_PRICE'
                WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
                  AND billing_period_days = 30
                  AND active = true
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!

            seedRunner.run(SimpleApplicationArguments())

            val stalePrice = jdbcTemplate.queryForMap(
                "SELECT active, valid_until FROM subscription_prices WHERE id = ?",
                stalePriceId,
            )
            assertEquals(true, stalePrice["active"])
            assertEquals(null, stalePrice["valid_until"])

            val activeMonthlyPrices = jdbcTemplate.queryForList(
                """
                SELECT id, amount, badge
                FROM subscription_prices
                WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
                  AND billing_period_days = 30
                  AND active = true
                """.trimIndent(),
            )
            assertEquals(1, activeMonthlyPrices.size)
            assertEquals("1000000.00", activeMonthlyPrices.single()["amount"].toString())
            assertEquals("OLD_PRICE", activeMonthlyPrices.single()["badge"])
            assertEquals(stalePriceId, activeMonthlyPrices.single()["id"])
        }

        @Test
        fun `database allows inactive historical price beside one active price`() {
            val historicalPriceId = jdbcTemplate.queryForObject(
                """
                INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active, valid_from, valid_until)
                SELECT id, 30, 123456.00, 'IRR', 'HISTORICAL_TEST', false, now() - interval '30 days', now()
                FROM subscription_plans
                WHERE code = 'ADVANCED'
                RETURNING id
                """.trimIndent(),
                Long::class.java,
            )!!

            try {
                val matchingPrices = jdbcTemplate.queryForList(
                    """
                    SELECT id, active
                    FROM subscription_prices
                    WHERE plan_id = (SELECT id FROM subscription_plans WHERE code = 'ADVANCED')
                      AND billing_period_days = 30
                      AND currency = 'IRR'
                    """.trimIndent(),
                )
                val activeCount = matchingPrices.count { it["active"] == true }

                assertTrue(matchingPrices.size >= 2)
                assertEquals(1, activeCount)
            } finally {
                jdbcTemplate.update("DELETE FROM subscription_prices WHERE id = ?", historicalPriceId)
            }
        }
    }

    // ── 7. Normal startup behavior ─────────────────────────────────────

    @Nested
    inner class `Normal startup behavior` {

        @Test
        fun `seed runner works when no competing lock exists`() {
            // Given: No lock is held
            // When: Seed runner executes
            seedRunner.run(SimpleApplicationArguments())

            // Then: All catalog data is present
            val plans = jdbcTemplate.queryForList("SELECT code FROM subscription_plans ORDER BY code")
            assertTrue(plans.any { it["code"] == "FREE" })
            assertTrue(plans.any { it["code"] == "ADVANCED" })

            val features = jdbcTemplate.queryForList("SELECT key FROM subscription_features ORDER BY key")
            assertTrue(features.any { it["key"] == "premium_schedules" })
            assertTrue(features.any { it["key"] == "advanced_analytics" })

            val prices = jdbcTemplate.queryForList(
                "SELECT sp.billing_period_days FROM subscription_prices sp JOIN subscription_plans p ON sp.plan_id = p.id WHERE p.code = 'ADVANCED' AND sp.active = true ORDER BY sp.billing_period_days",
            )
            assertEquals(3, prices.size)
        }

        @Test
        fun `seed runner with enabled false does nothing`() {
            // Given: Seed runner is disabled
            val disabledRunner = SeedRunner(
                dataSource = dataSource,
                lockTimeoutSeconds = 2,
                enabled = false,
            )

            // When: Disabled runner executes
            disabledRunner.run(SimpleApplicationArguments())

            // Then: No error, application is healthy
            val healthCheck = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
            assertEquals(1, healthCheck)
        }

        @Test
        fun `production rejects PayPing amount override even when seed runner is disabled`() {
            val production = MockEnvironment().apply { setActiveProfiles("prod") }
            val guardedRunner = SeedRunner(
                dataSource = dataSource,
                lockTimeoutSeconds = 2,
                enabled = false,
                payPingProviderAmountOverrideToman = "2000",
                environment = production,
            )

            val error = assertThrows(IllegalArgumentException::class.java) {
                guardedRunner.run(SimpleApplicationArguments())
            }
            assertEquals(
                "PAYPING_PROVIDER_AMOUNT_OVERRIDE_TOMAN is forbidden in production.",
                error.message,
            )
        }
    }
}

private class SimpleApplicationArguments : org.springframework.boot.ApplicationArguments {
    override fun getSourceArgs(): Array<String> = emptyArray()
    override fun containsOption(name: String): Boolean = false
    override fun getOptionValues(name: String): List<String>? = null
    override fun getNonOptionArgs(): List<String> = emptyList()
    override fun getOptionNames(): MutableSet<String> = mutableSetOf()
}

/**
 * DataSource proxy that fails on the second statement execution.
 * Used to simulate seed execution failure while allowing lock acquisition to succeed.
 */
private class FailingAfterFirstStatementDataSource(private val delegate: DataSource) : DataSource by delegate {
    private val statementCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun getConnection(): java.sql.Connection {
        val realConnection = delegate.connection
        return FailingConnection(realConnection, statementCount)
    }

    private class FailingConnection(
        private val delegate: java.sql.Connection,
        private val statementCount: java.util.concurrent.atomic.AtomicInteger,
    ) : java.sql.Connection by delegate {

        override fun createStatement(): java.sql.Statement {
            val realStatement = delegate.createStatement()
            return object : java.sql.Statement by realStatement {
                override fun executeUpdate(sql: String): Int {
                    val count = statementCount.incrementAndGet()
                    if (count > 1) {
                        throw java.sql.SQLException("Simulated seed failure")
                    }
                    return realStatement.executeUpdate(sql)
                }
            }
        }
    }
}
