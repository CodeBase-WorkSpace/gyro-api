package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.Promotion
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.Optional

interface PromotionRepository : JpaRepository<Promotion, Long>, JpaSpecificationExecutor<Promotion> {
    @Query("select count(p) from Promotion p where p.active = true and (p.applicableSubscriptionPriceId = :priceId or (p.applicableSubscriptionPriceId is null and (p.applicablePlanId is null or p.applicablePlanId = :planId)))")
    fun countActiveApplicableToPrice(priceId: Long, planId: Long): Long
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.code = :code")
    fun findByCodeForUpdate(code: String): Optional<Promotion>
}
