package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.ManualGrant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.*

interface ManualGrantRepository : JpaRepository<ManualGrant, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<ManualGrant>
    @Query(
        """
        select mg from ManualGrant mg
        where mg.userId = :userId
          and mg.revokedAt is null
          and (mg.expiresAt is null or mg.expiresAt > :now)
        order by mg.createdAt desc
        """
    )
    fun findActiveByUserId(userId: UUID, now: Instant): List<ManualGrant>

    @Query(
        """
        select mg from ManualGrant mg
        where mg.userId = :userId
          and mg.revokedAt is null
          and (mg.expiresAt is null or mg.expiresAt > :now)
        order by mg.createdAt asc, mg.id asc
        """
    )
    fun findEffectiveByUserId(userId: UUID, now: Instant): List<ManualGrant>

    @Query(
        """
        select mg.id from ManualGrant mg
        where mg.expiresAt is not null
          and mg.expiresAt <= :now
          and mg.revokedAt is null
        order by mg.expiresAt asc, mg.createdAt asc, mg.id asc
        """
    )
    fun findExpiredGrantIds(now: Instant, pageable: Pageable): List<UUID>
}
