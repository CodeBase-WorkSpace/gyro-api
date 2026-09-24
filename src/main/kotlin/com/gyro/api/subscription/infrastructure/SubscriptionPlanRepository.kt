package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.SubscriptionPlan
import org.springframework.data.jpa.repository.JpaRepository

interface SubscriptionPlanRepository : JpaRepository<SubscriptionPlan, Long> {
    fun findByCode(code: String): SubscriptionPlan?

    fun existsByIdAndActiveTrue(id: Long): Boolean

    fun findByActiveTrueOrderByFreeDescCodeAsc(): List<SubscriptionPlan>
}
