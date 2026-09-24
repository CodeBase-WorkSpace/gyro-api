package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PlanFeature
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PlanFeatureRepository : JpaRepository<PlanFeature, Long> {
    @Query(
        """
        select pf from PlanFeature pf
        where pf.planId = :planId
          and pf.enabled = true
        """
    )
    fun findEnabledByPlanId(planId: Long): List<PlanFeature>

    @Query(
        """
        select pf from PlanFeature pf
        join SubscriptionFeature sf on sf.key = pf.featureKey
        where pf.planId = :planId
          and sf.active = true
        order by pf.featureKey asc
        """
    )
    fun findActiveFeatureMappingsByPlanId(planId: Long): List<PlanFeature>
}
