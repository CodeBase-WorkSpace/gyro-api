package com.gyro.api.subscription.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Runs subscription catalog seed data on application startup.
 * Acquires a PostgreSQL advisory lock to prevent concurrent execution
 * across multiple application instances.
 *
 * Holds a single JDBC connection for the entire lock lifecycle (acquire → seed → release)
 * to ensure the advisory lock stays on the same session. Advisory locks are connection-scoped,
 * so the lock must be acquired, used, and released on the same connection.
 *
 * Uses pg_try_advisory_lock for non-blocking acquisition with configurable timeout.
 * Seed SQL is idempotent and only creates missing bootstrap prices. Production disables this
 * runner so operator-managed catalog state can never be rewritten during application startup.
 *
 * Price Override Configuration:
 * The PAYPING_PROVIDER_AMOUNT_OVERRIDE_TOMAN environment variable controls whether checkout
 * prices are overridden for the PayPing provider. At startup:
 * - If set to a numeric value (e.g., 2000): all ADVANCED plan checkout amounts are replaced
 *   with this fixed price in Toman. Useful for testing to avoid charging real amounts before
 *   PayPing approval.
 * - If left empty or not set: the system uses actual prices from the subscription_prices table.
 *   This is required for production.
 *
 * The override is persisted to the provider_price_mappings table and used by CheckoutService
 * during payment processing.
 */
@Component
@Order(1)
class SeedRunner(
    private val dataSource: DataSource,
    @Value("\${app.seed.lock-timeout-seconds:5}")
    private val lockTimeoutSeconds: Long,
    @Value("\${app.seed.enabled:true}")
    private val enabled: Boolean,
    @Value("\${app.billing.payping.provider-amount-override-toman:}")
    private val payPingProviderAmountOverrideToman: String = "", // Fixed price override for testing; empty = use catalog prices
    private val environment: Environment? = null,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val LOCK_KEY_NAME = "gyro_seed_runner"
        const val LOCK_TIMEOUT_MS = 100L
    }

    override fun run(args: ApplicationArguments) {
        require(
            !environment.isProduction() || payPingProviderAmountOverrideToman.isBlank(),
        ) {
            "PAYPING_PROVIDER_AMOUNT_OVERRIDE_TOMAN is forbidden in production."
        }
        if (!enabled) {
            log.info("event=subscription_catalog_seed outcome=disabled")
            return
        }

        log.info("event=subscription_catalog_seed outcome=lock_attempt")
        val connection = dataSource.connection
        try {
            val acquired = acquireLock(connection)
            if (!acquired) {
                log.warn(
                    "event=subscription_catalog_seed outcome=lock_timeout lock_timeout_seconds={}",
                    lockTimeoutSeconds,
                )
                return
            }

            try {
                log.info("event=subscription_catalog_seed outcome=started")
                executeSeed(connection)
                log.info("event=subscription_catalog_seed outcome=success")
            } catch (e: Exception) {
                log.error("event=subscription_catalog_seed outcome=failure", e)
                throw e
            } finally {
                releaseLock(connection)
            }
        } finally {
            connection.close()
        }
    }

    private fun acquireLock(connection: java.sql.Connection): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(lockTimeoutSeconds)
        while (System.currentTimeMillis() < deadline) {
            val stmt = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")
            stmt.use {
                it.setString(1, LOCK_KEY_NAME)
                val rs = it.executeQuery()
                rs.use {
                    if (it.next() && it.getBoolean(1)) return true
                }
            }
            Thread.sleep(LOCK_TIMEOUT_MS)
        }
        return false
    }

    private fun releaseLock(connection: java.sql.Connection) {
        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))").use {
                it.setString(1, LOCK_KEY_NAME)
                it.execute()
            }
            log.info("event=subscription_catalog_seed outcome=lock_released")
        } catch (e: Exception) {
            log.warn("event=subscription_catalog_seed outcome=lock_release_failure message={}", e.message)
        }
    }

    private fun executeSeed(connection: java.sql.Connection) {
        val originalAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val stmt = connection.createStatement()
            stmt.use {
                // Plans
                it.executeUpdate(
                """
                INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
                VALUES ('FREE', 'Free', true, true, 0, now(), now()),
                       ('ADVANCED', 'Advanced', false, true, 7, now(), now())
                ON CONFLICT (code) DO UPDATE SET
                    name = excluded.name, free = excluded.free, active = excluded.active,
                    grace_period_days = excluded.grace_period_days, updated_at = now()
                """.trimIndent(),
                )

                // Features
                it.executeUpdate(
                """
                INSERT INTO subscription_features (key, description, active) VALUES
                    ('premium_schedules', 'Advanced goal scheduling (weekday/weekend, zigzag, custom)', true),
                    ('advanced_analytics', 'Long-range nutrition, weight, goal, and maintenance analytics', true),
                    ('data_export', 'CSV/JSON data export', true),
                    ('future_meal_planning', 'Planned meals and meal planning calendar', true),
                    ('higher_limits', 'Unlimited custom food and custom meal limits', true),
                    ('goal_recalibration', 'Automatic goal recalibration from weight trend (suggest and confirm)', true)
                ON CONFLICT (key) DO UPDATE SET
                    description = excluded.description,
                    active = excluded.active
                """.trimIndent(),
                )

                // Plan features for ADVANCED
                it.executeUpdate(
                """
                INSERT INTO plan_features (plan_id, feature_key, enabled)
                SELECT p.id, f.key, true
                FROM subscription_plans p, subscription_features f
                WHERE p.code = 'ADVANCED'
                  AND f.key IN ('premium_schedules', 'advanced_analytics', 'data_export', 'future_meal_planning', 'higher_limits', 'goal_recalibration')
                ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = true
                """.trimIndent(),
                )

                // Plan features for FREE
                it.executeUpdate(
                """
                INSERT INTO plan_features (plan_id, feature_key, enabled)
                SELECT p.id, f.key, false
                FROM subscription_plans p, subscription_features f
                WHERE p.code = 'FREE'
                  AND f.key IN ('premium_schedules', 'advanced_analytics', 'data_export', 'future_meal_planning', 'higher_limits', 'goal_recalibration')
                ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = false
                """.trimIndent(),
                )

                // Localizations
                it.executeUpdate(
                """
                INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
                SELECT p.id, 'fa-IR', 'رایگان', 'ردیابی روزانه کالری و درشت‌مغذی‌ها'
                FROM subscription_plans p WHERE p.code = 'FREE'
                ON CONFLICT (plan_id, locale) DO NOTHING
                """.trimIndent(),
                )
                it.executeUpdate(
                """
                INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
                SELECT p.id, 'en-US', 'Free', 'Daily calorie and macro tracking'
                FROM subscription_plans p WHERE p.code = 'FREE'
                ON CONFLICT (plan_id, locale) DO NOTHING
                """.trimIndent(),
                )
                it.executeUpdate(
                """
                INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
                SELECT p.id, 'fa-IR', 'پیشرفته', 'برنامه‌ریزی، تحلیل و خروجی داده'
                FROM subscription_plans p WHERE p.code = 'ADVANCED'
                ON CONFLICT (plan_id, locale) DO NOTHING
                """.trimIndent(),
                )
                it.executeUpdate(
                """
                INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
                SELECT p.id, 'en-US', 'Advanced', 'Planning, analytics, and data export'
                FROM subscription_plans p WHERE p.code = 'ADVANCED'
                ON CONFLICT (plan_id, locale) DO NOTHING
                """.trimIndent(),
                )

                // Bootstrap prices. Never alter or replace an operator-managed price version.
                it.executeUpdate(
                """
                INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, badge, active)
                SELECT p.id, 30, 1990000.00, 0.00, 1990000.00, 'IRR', null, true
                FROM subscription_plans p WHERE p.code = 'ADVANCED'
                  AND NOT EXISTS (
                    SELECT 1 FROM subscription_prices existing
                    WHERE existing.plan_id = p.id AND existing.billing_period_days = 30 AND existing.currency = 'IRR'
                  )
                """.trimIndent(),
                )
                it.executeUpdate(
                """
                INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, badge, active)
                SELECT p.id, 90, 5490000.00, 0.00, 5490000.00, 'IRR', 'RECOMMENDED', true
                FROM subscription_plans p WHERE p.code = 'ADVANCED'
                  AND NOT EXISTS (
                    SELECT 1 FROM subscription_prices existing
                    WHERE existing.plan_id = p.id AND existing.billing_period_days = 90 AND existing.currency = 'IRR'
                  )
                """.trimIndent(),
                )
                it.executeUpdate(
                """
                INSERT INTO subscription_prices (plan_id, billing_period_days, base_amount, discount_percent, amount, currency, badge, active)
                SELECT p.id, 365, 19900000.00, 0.00, 19900000.00, 'IRR', 'BEST_VALUE', true
                FROM subscription_plans p WHERE p.code = 'ADVANCED'
                  AND NOT EXISTS (
                    SELECT 1 FROM subscription_prices existing
                    WHERE existing.plan_id = p.id AND existing.billing_period_days = 365 AND existing.currency = 'IRR'
                  )
                """.trimIndent(),
                )

                // Provider mappings
                val payPingOverride = payPingProviderOverride()
                val payPingAmountOverride = payPingOverride?.toPlainString() ?: "null"
                val payPingCurrencyOverride = if (payPingOverride == null) "null" else "'IRT'"
                it.executeUpdate(
                """
                INSERT INTO provider_price_mappings (
                    subscription_price_id, provider, environment,
                    provider_amount_override, provider_currency_override, active
                )
                SELECT sp.id, 'PAYPING', 'PROD', $payPingAmountOverride, $payPingCurrencyOverride, true
                FROM subscription_prices sp
                JOIN subscription_plans p ON sp.plan_id = p.id
                WHERE p.code = 'ADVANCED' AND sp.active = true
                ON CONFLICT (subscription_price_id, provider, environment) DO NOTHING
                """.trimIndent(),
                )
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = originalAutoCommit
        }
    }

    private fun payPingProviderOverride(): BigDecimal? {
        // When PAYPING_PROVIDER_AMOUNT_OVERRIDE_TOMAN is empty, no override is applied.
        // Checkouts will use actual prices from subscription_prices table.
        // When set to a numeric value, that amount becomes the fixed checkout price.
        val raw = payPingProviderAmountOverrideToman.trim()
        if (raw.isBlank()) return null

        val amount = raw.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("app.billing.payping.provider-amount-override-toman must be numeric.")
        require(amount > BigDecimal.ZERO) {
            "app.billing.payping.provider-amount-override-toman must be positive."
        }
        return amount
    }
}

private fun Environment?.isProduction(): Boolean =
    this?.acceptsProfiles(Profiles.of("prod")) == true
