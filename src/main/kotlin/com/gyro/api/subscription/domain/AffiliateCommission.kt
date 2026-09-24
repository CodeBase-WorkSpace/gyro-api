package com.gyro.api.subscription.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class AffiliateCommissionStatus { EARNED, REVERSED }

@Entity
@Table(name = "affiliate_commissions")
class AffiliateCommission(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "affiliate_id", nullable = false) val affiliateId: UUID,
    @Column(name = "referred_user_id", nullable = false, updatable = false) val referredUserId: UUID,
    @Column(name = "invoice_id", nullable = false, updatable = false) val invoiceId: UUID,
    @Column(name = "promotion_redemption_id", nullable = false, updatable = false) val promotionRedemptionId: UUID,
    @Column(nullable = false, length = 3, updatable = false) val currency: String,
    @Column(name = "original_invoice_amount", nullable = false, precision = 12, scale = 2, updatable = false) val originalInvoiceAmount: BigDecimal,
    @Column(name = "customer_paid_amount", nullable = false, precision = 12, scale = 2, updatable = false) val customerPaidAmount: BigDecimal,
    @Column(name = "discount_percentage_snapshot", nullable = false, precision = 5, scale = 2, updatable = false) val discountPercentageSnapshot: BigDecimal,
    @Column(name = "commission_percentage_snapshot", nullable = false, precision = 5, scale = 2, updatable = false) val commissionPercentageSnapshot: BigDecimal,
    @Column(name = "earning_amount", nullable = false, precision = 12, scale = 2, updatable = false) val earningAmount: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) val status: AffiliateCommissionStatus = AffiliateCommissionStatus.EARNED,
    @Column(name = "earned_at", nullable = false, updatable = false) val earnedAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "reversed_at") val reversedAt: Instant? = null,
    @Column(name = "reversal_reason") val reversalReason: String? = null,
)
