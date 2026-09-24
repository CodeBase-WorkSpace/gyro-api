package com.gyro.api.common.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    @Query(
        """
        select oe from OutboxEvent oe
        where oe.status = 'PENDING'
          and (oe.nextRetryAt is null or oe.nextRetryAt <= :now)
        order by oe.createdAt asc
        """,
    )
    fun findReadyToPublish(now: Instant, pageable: Pageable): List<OutboxEvent>

    @Query("select count(oe) from OutboxEvent oe where oe.status = :status")
    fun countByStatus(status: OutboxStatus): Long

    @Query("select min(oe.createdAt) from OutboxEvent oe where oe.status = 'PENDING'")
    fun oldestPendingCreatedAt(): Instant?

    @Modifying
    @Query("delete from OutboxEvent oe where oe.status = 'PUBLISHED' and oe.createdAt < :threshold")
    fun deletePublishedBefore(threshold: Instant): Int
}

interface OutboxEventConsumptionRepository : JpaRepository<OutboxEventConsumption, Long> {
    @Modifying
    @Query("delete from OutboxEventConsumption c where c.eventId in (select e.id from OutboxEvent e where e.status = 'PUBLISHED' and e.createdAt < :threshold)")
    fun deleteForPublishedBefore(threshold: Instant): Int
}
