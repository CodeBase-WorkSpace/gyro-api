package com.gyro.api.notification.application.delivery

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp

data class TelegramLinkDataCleanupResult(
    val legacyTokens: Int,
    val unclaimedCodes: Int,
    val consumedCodes: Int,
    val webhookUpdates: Int,
) {
    val linkTokens: Int
        get() = legacyTokens + unclaimedCodes + consumedCodes
}

@Service
class TelegramLinkDataCleanupService(
    private val jdbc: JdbcTemplate,
    private val properties: NotificationProperties,
    private val time: TimeProvider,
) {
    @Transactional
    fun purgeBatch(): TelegramLinkDataCleanupResult {
        val cutoff = Timestamp.from(time.now().minus(properties.telegramLinkDataRetention))
        val unclaimedCodeCutoff = Timestamp.from(time.now().minus(properties.telegramUnclaimedLinkCodeRetention))
        val batchSize = properties.telegramCleanupBatchSize
        val legacyTokens = jdbc.update(
            """
            with candidates as (
                select id
                from notification_telegram_link_tokens
                where expires_at < ?
                order by expires_at
                limit ?
                for update skip locked
            )
            delete from notification_telegram_link_tokens token
            using candidates
            where token.id = candidates.id
            """.trimIndent(),
            cutoff,
            batchSize,
        )
        val unclaimedCodes = jdbc.update(
            """
            with candidates as (
                select id
                from notification_telegram_link_codes
                where consumed_at is null
                  and expires_at < ?
                order by expires_at
                limit ?
                for update skip locked
            )
            delete from notification_telegram_link_codes code
            using candidates
            where code.id = candidates.id
            """.trimIndent(),
            unclaimedCodeCutoff,
            batchSize,
        )
        val consumedCodes = jdbc.update(
            """
            with candidates as (
                select id
                from notification_telegram_link_codes
                where consumed_at is not null
                  and expires_at < ?
                order by expires_at
                limit ?
                for update skip locked
            )
            delete from notification_telegram_link_codes code
            using candidates
            where code.id = candidates.id
            """.trimIndent(),
            cutoff,
            batchSize,
        )
        return TelegramLinkDataCleanupResult(
            legacyTokens = legacyTokens,
            unclaimedCodes = unclaimedCodes,
            consumedCodes = consumedCodes,
            webhookUpdates = jdbc.update(
                """
                with candidates as (
                    select bot_identity, update_id
                    from notification_telegram_webhook_updates
                    where received_at < ?
                    order by received_at
                    limit ?
                    for update skip locked
                )
                delete from notification_telegram_webhook_updates webhook
                using candidates
                where webhook.bot_identity = candidates.bot_identity
                  and webhook.update_id = candidates.update_id
                """.trimIndent(),
                cutoff,
                batchSize,
            ),
        )
    }
}

@Component
class TelegramLinkDataCleanupJob(
    private val cleanup: TelegramLinkDataCleanupService,
    private val properties: NotificationProperties,
    private val metrics: NotificationMetrics,
) {
    @Scheduled(fixedDelayString = "\${app.notification.telegram-cleanup-delay:1h}")
    fun run() {
        if (!properties.jobsEnabled) return
        runCatching(cleanup::purgeBatch)
            .onSuccess { result ->
                metrics.telegramLinkDataCleaned(
                    result.legacyTokens,
                    result.unclaimedCodes,
                    result.consumedCodes,
                    result.webhookUpdates,
                )
                if (result.linkTokens > 0 || result.webhookUpdates > 0) {
                    log.info(
                        "event=telegram_link_data_cleanup legacyTokens={} unclaimedCodes={} consumedCodes={} webhookUpdates={}",
                        result.legacyTokens,
                        result.unclaimedCodes,
                        result.consumedCodes,
                        result.webhookUpdates,
                    )
                }
            }
            .onFailure { exception ->
                metrics.telegramLinkDataCleanupFailed()
                log.warn("event=telegram_link_data_cleanup_failed exception={}", exception::class.simpleName)
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelegramLinkDataCleanupJob::class.java)
    }
}
