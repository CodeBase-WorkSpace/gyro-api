package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.UserSubscription
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface SubscriptionRepository : JpaRepository<UserSubscription, Long> {
    @Query(
        """
        select us from UserSubscription us
        where us.userId = :userId
          and us.status in ('ACTIVE', 'GRACE_PERIOD')
        order by us.periodStart desc
        """
    )
    fun findActiveSubscriptionsByUserId(userId: UUID): List<UserSubscription>

    @Query(
        """
        select us from UserSubscription us
        where us.userId = :userId
          and us.status = 'EXPIRED'
        order by us.periodStart desc
        """
    )
    fun findExpiredSubscriptionsByUserId(userId: UUID): List<UserSubscription>

    @Query(
        """
        select case when count(pf) > 0 then true else false end
        from UserSubscription us
        join SubscriptionPlan p on us.planId = p.id
        join PlanFeature pf on pf.planId = p.id
        join SubscriptionFeature sf on sf.key = pf.featureKey
        where us.userId = :userId
          and us.status in ('ACTIVE', 'GRACE_PERIOD')
          and (us.periodEnd is null or us.periodEnd > :now)
          and pf.featureKey = :featureKey
          and pf.enabled = true
          and sf.active = true
        """
    )
    fun hasEnabledFeatureForUser(userId: UUID, featureKey: String, now: Instant): Boolean

    @Query(
        """
        select case when count(pf) > 0 then true else false end
        from UserSubscription us
        join SubscriptionPlan p on us.planId = p.id
        join PlanFeature pf on pf.planId = p.id
        join SubscriptionFeature sf on sf.key = pf.featureKey
        where us.userId = :userId
          and us.status = 'GRACE_PERIOD'
          and us.gracePeriodEnd > :now
          and pf.featureKey = :featureKey
          and pf.enabled = true
          and sf.active = true
        """
    )
    fun hasEnabledFeatureForUserInGrace(userId: UUID, featureKey: String, now: Instant): Boolean

    @Query(
        """
        select case when count(pf) > 0 then true else false end
        from UserSubscription us
        join SubscriptionPlan p on us.planId = p.id
        join PlanFeature pf on pf.planId = p.id
        join SubscriptionFeature sf on sf.key = pf.featureKey
        where us.userId = :userId
          and us.status = 'EXPIRED'
          and pf.featureKey = :featureKey
          and pf.enabled = true
          and sf.active = true
        """
    )
    fun hadEnabledFeatureForUser(userId: UUID, featureKey: String): Boolean
}
