package com.gyro.api.subscription.application.trial

import com.gyro.api.common.error.DomainException
import com.gyro.api.common.error.TrialAlreadyRedeemedException
import com.gyro.api.subscription.config.TrialProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Durable backstop for [TrialSignupListener]: the after-commit event is
 * in-process and can be lost to a crash, deploy, or transient failure. This
 * sweep finds recently created, contactable users with no trial redemption
 * and no subscription history and redeems idempotently -- the unique indexes
 * on trial_redemptions make double grants impossible even if the listener
 * and the sweep race.
 */
@Component
class TrialReconciliationJob(
    private val trialService: TrialService,
    private val jdbc: JdbcTemplate,
    private val properties: TrialProperties,
    meterRegistry: MeterRegistry,
    @Value("\${app.trial.reconciliation-enabled:true}") private val enabled: Boolean,
    @Value("\${app.trial.reconciliation-lookback-days:3}") private val lookbackDays: Int,
    @Value("\${app.trial.reconciliation-batch-size:200}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recovered = meterRegistry.counter("gyro.subscription.trial.reconciled_grants")
    private val runs = meterRegistry.counter("gyro.subscription.trial.reconciliation_runs")

    @Scheduled(fixedDelayString = "\${app.trial.reconciliation-delay:900000}")
    fun run() = reconcile(batchSize)

    internal fun reconcile(limit: Int) {
        if (!enabled || !properties.enabled) return
        runs.increment()

        val candidates = jdbc.query(
            """
            select u.id, u.email, u.phone_number
            from users u
            where u.status = 'ACTIVE'
              and u.created_at > now() - make_interval(days => ?)
              and (u.email_verification_status = 'VERIFIED' or u.phone_verification_status = 'VERIFIED')
              and not exists (select 1 from trial_redemptions tr where tr.user_id = u.id)
              and not exists (select 1 from user_subscriptions us where us.user_id = u.id)
              and not exists (select 1 from trial_reconciliation_outcomes tro where tro.user_id = u.id)
            order by u.created_at
            limit ?
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("email"),
                    rs.getString("phone_number"),
                )
            },
            lookbackDays,
            limit,
        )

        var granted = 0
        candidates.forEach { (userId, email, phone) ->
            try {
                trialService.grantSignupTrial(userId, email ?: phone)
                granted += 1
                recovered.increment()
                log.info("event=trial_grant outcome=success source=RECONCILIATION userId={}", userId)
            } catch (exception: TrialAlreadyRedeemedException) {
                recordTerminalOutcome(userId, exception.code.name)
                log.info(
                    "event=trial_grant outcome=terminal_skip source=RECONCILIATION userId={} reason={}",
                    userId,
                    exception.code,
                )
            } catch (exception: DomainException) {
                // Other domain failures may be configuration or state races;
                // leave them retryable instead of making a permanent decision.
                log.info(
                    "event=trial_grant outcome=retryable_skip source=RECONCILIATION userId={} reason={}",
                    userId,
                    exception.code,
                )
            } catch (exception: RuntimeException) {
                log.warn(
                    "event=trial_grant outcome=failure source=RECONCILIATION userId={} reason={}",
                    userId,
                    exception::class.simpleName,
                )
            }
        }
        if (candidates.isNotEmpty()) {
            log.info("event=trial_reconciliation outcome=completed candidates={} granted={}", candidates.size, granted)
        }
    }

    private fun recordTerminalOutcome(userId: UUID, outcome: String) {
        jdbc.update(
            """
            insert into trial_reconciliation_outcomes (user_id, outcome)
            values (?, ?)
            on conflict (user_id) do nothing
            """.trimIndent(),
            userId,
            outcome,
        )
    }
}
