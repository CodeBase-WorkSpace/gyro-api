package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PaymentProvider
import com.gyro.api.subscription.domain.ProviderEnvironment
import com.gyro.api.subscription.domain.ProviderPriceMapping
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface ProviderPriceMappingRepository : JpaRepository<ProviderPriceMapping, Long> {
    @Query(
        """
        select ppm from ProviderPriceMapping ppm
        where ppm.subscriptionPriceId = :subscriptionPriceId
          and ppm.active = true
        """
    )
    fun findActiveBySubscriptionPriceId(subscriptionPriceId: Long): List<ProviderPriceMapping>

    @Query(
        """
        select ppm from ProviderPriceMapping ppm
        where ppm.subscriptionPriceId = :subscriptionPriceId
          and ppm.provider = :provider
          and ppm.environment = :environment
          and ppm.active = true
        """
    )
    fun findBySubscriptionPriceIdAndProviderAndEnvironmentAndActiveTrue(
        subscriptionPriceId: Long,
        provider: PaymentProvider,
        environment: ProviderEnvironment,
    ): Optional<ProviderPriceMapping>
}
