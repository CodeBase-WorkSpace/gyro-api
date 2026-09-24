package com.gyro.api.subscription.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class AffiliateStatus { ACTIVE, INACTIVE }

@Entity
@Table(name = "affiliates")
class Affiliate(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "display_name", nullable = false, length = 160) var displayName: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var status: AffiliateStatus = AffiliateStatus.ACTIVE,
    @Column(name = "promotion_id", nullable = false, unique = true) val promotionId: Long,
    @Column(name = "linked_user_id", unique = true) var linkedUserId: UUID? = null,
    @Column(name = "commission_percentage", nullable = false, precision = 5, scale = 2) var commissionPercentage: BigDecimal,
    @Column(name = "internal_notes") var internalNotes: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Version val version: Long = 0,
)
