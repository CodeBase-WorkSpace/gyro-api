package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PlanLocalization
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface PlanLocalizationRepository : JpaRepository<PlanLocalization, Long> {
    fun findByPlanIdAndLocale(planId: Long, locale: String): Optional<PlanLocalization>

    fun findByPlanId(planId: Long): List<PlanLocalization>
}
