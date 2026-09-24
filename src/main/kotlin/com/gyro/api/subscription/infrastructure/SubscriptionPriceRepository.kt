package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.SubscriptionPrice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional

interface SubscriptionPriceRepository : JpaRepository<SubscriptionPrice, Long>, JpaSpecificationExecutor<SubscriptionPrice> {
    @Query(
        """
        select sp from SubscriptionPrice sp
        where sp.planId = :planId
          and sp.active = true
        order by sp.billingPeriodDays asc
        """
    )
    fun findActiveByPlanId(planId: Long): List<SubscriptionPrice>

    @Query(
        """
        select sp from SubscriptionPrice sp
        where sp.planId = :planId
          and sp.billingPeriodDays = :billingPeriodDays
          and sp.price.currency = :currency
        """
    )
    fun findByPlanIdAndBillingPeriodDaysAndCurrency(
        planId: Long,
        billingPeriodDays: Int,
        currency: String,
    ): Optional<SubscriptionPrice>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sp from SubscriptionPrice sp where sp.planId = :planId and sp.billingPeriodDays = :days and sp.price.currency = :currency and sp.active = true")
    fun findActiveForUpdate(@Param("planId") planId: Long, @Param("days") days: Int, @Param("currency") currency: String): Optional<SubscriptionPrice>

    fun existsByPlanIdAndBillingPeriodDaysAndPriceCurrencyAndScheduledTrue(planId: Long, billingPeriodDays: Int, currency: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sp from SubscriptionPrice sp where sp.scheduled = true and sp.activationConflictedAt is null and sp.validFrom <= :now order by sp.validFrom asc")
    fun findDueScheduledForUpdate(@Param("now") now: Instant): List<SubscriptionPrice>
}
