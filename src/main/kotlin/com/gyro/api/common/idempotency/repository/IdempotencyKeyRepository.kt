package com.gyro.api.common.idempotency.repository

import com.gyro.api.common.idempotency.domain.IdempotencyKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, UUID> {

    fun findByScopeAndIdempotencyKey(
        scope: String,
        idempotencyKey: String,
    ): IdempotencyKey?

    @Modifying
    @Query("delete from IdempotencyKey key where key.expiresAt < :now")
    fun deleteExpired(now: Instant): Int
}
