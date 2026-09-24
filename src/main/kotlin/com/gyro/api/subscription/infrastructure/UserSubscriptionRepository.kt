package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.UserSubscription
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface UserSubscriptionRepository : JpaRepository<UserSubscription, Long> {
    @Query("select count(us) from UserSubscription us where us.lockedPriceId = :priceId and us.status in ('ACTIVE', 'GRACE_PERIOD')")
    fun countActiveLockedToPrice(priceId: Long): Long
    fun findByUserId(userId: UUID): Optional<UserSubscription>

    @Query(
        """
        select us from UserSubscription us
        where us.userId = :userId
          and us.status in ('ACTIVE', 'GRACE_PERIOD')
        """
    )
    fun findActiveByUserId(userId: UUID): Optional<UserSubscription>

    @Query(
        """
        select us from UserSubscription us
        where us.status = 'ACTIVE'
          and us.periodEnd is not null
          and us.periodEnd < :threshold
        """
    )
    fun findExpiredSubscriptions(threshold: Instant): List<UserSubscription>

    @Query("select us from UserSubscription us where us.status in ('ACTIVE', 'CANCELED') and us.periodEnd is not null and us.periodEnd < :threshold order by us.periodEnd asc")
    fun findPeriodExpiryCandidates(threshold: Instant, pageable: Pageable): List<UserSubscription>

    @Query("select us from UserSubscription us where us.status = 'GRACE_PERIOD' and us.gracePeriodEnd is not null and us.gracePeriodEnd < :threshold order by us.gracePeriodEnd asc")
    fun findGraceExitCandidates(threshold: Instant, pageable: Pageable): List<UserSubscription>

    @Query("select us from UserSubscription us where us.status = 'ACTIVE' and us.cancelAtPeriodEnd = false and us.periodEnd between :from and :to order by us.periodEnd asc")
    fun findRenewalReminderCandidates(from: Instant, to: Instant, pageable: Pageable): List<UserSubscription>
}
