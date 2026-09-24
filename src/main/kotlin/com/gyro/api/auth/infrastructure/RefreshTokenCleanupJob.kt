package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.infrastructure.RefreshTokenRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class RefreshTokenCleanupJob(
    private val refreshTokenRepository: RefreshTokenRepository,
    @Value("\${app.auth.revoked-token-retention}")
    private val revokedTokenRetention: Duration,
) {

    @Scheduled(cron = "\${app.auth.refresh-token-cleanup-cron}")
    @Transactional
    fun cleanupRefreshTokens() {
        val now = Instant.now()
        val revokedBefore = now.minus(revokedTokenRetention)

        val deletedCount = refreshTokenRepository.deleteExpiredOrOldRevoked(
            now = now,
            revokedBefore = revokedBefore,
        )

        logger.info("Deleted {} old refresh tokens", deletedCount)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RefreshTokenCleanupJob::class.java)
    }
}