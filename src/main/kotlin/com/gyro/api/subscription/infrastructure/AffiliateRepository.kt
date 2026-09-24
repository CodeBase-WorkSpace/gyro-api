package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.Affiliate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.Optional
import java.util.UUID

interface AffiliateRepository : JpaRepository<Affiliate, UUID>, JpaSpecificationExecutor<Affiliate> {
    fun findByPromotionId(promotionId: Long): Optional<Affiliate>
    fun findByLinkedUserId(linkedUserId: UUID): Optional<Affiliate>
}
