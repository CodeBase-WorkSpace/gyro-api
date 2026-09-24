package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.AffiliateCommission
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface AffiliateCommissionRepository : JpaRepository<AffiliateCommission, UUID> {
    fun findByInvoiceId(invoiceId: UUID): Optional<AffiliateCommission>
    fun existsByReferredUserId(referredUserId: UUID): Boolean
    fun findByAffiliateIdAndEarnedAtBetweenOrderByEarnedAtAsc(affiliateId: UUID, from: Instant, to: Instant): List<AffiliateCommission>
    fun findByAffiliateId(affiliateId: UUID): List<AffiliateCommission>
}
