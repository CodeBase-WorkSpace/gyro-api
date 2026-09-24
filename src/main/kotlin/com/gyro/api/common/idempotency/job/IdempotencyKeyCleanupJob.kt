package com.gyro.api.common.idempotency.job

import com.gyro.api.common.idempotency.repository.IdempotencyKeyRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class IdempotencyKeyCleanupJob(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
) {

    @Scheduled(cron = "\${app.idempotency.cleanup-cron}")
    @Transactional
    fun cleanupExpiredIdempotencyKeys() {
        val deletedCount = idempotencyKeyRepository.deleteExpired(Instant.now())
        logger.info("Deleted {} expired idempotency keys", deletedCount)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IdempotencyKeyCleanupJob::class.java)
    }
}
