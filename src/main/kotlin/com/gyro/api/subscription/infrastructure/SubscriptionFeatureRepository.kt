package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.SubscriptionFeature
import org.springframework.data.jpa.repository.JpaRepository

interface SubscriptionFeatureRepository : JpaRepository<SubscriptionFeature, String> {
    fun findByActiveTrue(): List<SubscriptionFeature>

    fun existsByKeyAndActiveTrue(key: String): Boolean
}
