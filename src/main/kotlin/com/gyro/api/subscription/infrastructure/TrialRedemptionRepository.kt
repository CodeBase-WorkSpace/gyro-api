package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.TrialRedemption
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface TrialRedemptionRepository : JpaRepository<TrialRedemption, UUID> {
    fun findByUserId(userId: UUID): Optional<TrialRedemption>
    fun existsByUserId(userId: UUID): Boolean
    fun existsByIdentifierHash(identifierHash: String): Boolean
    fun existsByUserIdOrIdentifierHash(userId: UUID, identifierHash: String): Boolean
}
