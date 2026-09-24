package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PromotionRedemption
import com.gyro.api.subscription.domain.PromotionRedemptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.*

interface PromotionRedemptionRepository : JpaRepository<PromotionRedemption, UUID> {
    interface StatusCount {
        fun getPromotionId(): Long
        fun getStatus(): PromotionRedemptionStatus
        fun getCount(): Long
    }

    @Query("""select pr.promotionId as promotionId, pr.status as status, count(pr) as count from PromotionRedemption pr where pr.promotionId in :promotionIds group by pr.promotionId, pr.status""")
    fun countStatusesByPromotionIds(promotionIds: Collection<Long>): List<StatusCount>

    @Query(
        """
        select count(pr) from PromotionRedemption pr
        where pr.promotionId = :promotionId
          and (pr.status = 'REDEEMED' or (pr.status = 'RESERVED' and pr.reservationExpiresAt > :now))
        """
    )
    fun countActiveByPromotionId(promotionId: Long, now: Instant): Long

    @Query(
        """
        select count(pr) from PromotionRedemption pr
        where pr.userId = :userId
          and pr.promotionId = :promotionId
          and (pr.status = 'REDEEMED' or (pr.status = 'RESERVED' and pr.reservationExpiresAt > :now))
        """
    )
    fun countActiveByUserIdAndPromotionId(userId: UUID, promotionId: Long, now: Instant): Long

    fun findByUserIdAndPromotionIdAndIdempotencyKey(
        userId: UUID,
        promotionId: Long,
        idempotencyKey: String,
    ): Optional<PromotionRedemption>

    fun findByInvoiceId(invoiceId: UUID): List<PromotionRedemption>

    fun findByManualGrantId(manualGrantId: UUID): PromotionRedemption?

    fun findByPromotionId(promotionId: Long, pageable: Pageable): Page<PromotionRedemption>

    fun findByPromotionIdAndStatus(promotionId: Long, status: PromotionRedemptionStatus, pageable: Pageable): Page<PromotionRedemption>

    fun findByUserId(userId: UUID, pageable: Pageable): Page<PromotionRedemption>

    fun countByPromotionIdAndStatus(promotionId: Long, status: PromotionRedemptionStatus): Long
}
