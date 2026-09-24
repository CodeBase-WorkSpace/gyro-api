package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.domain.GyroUser
import com.gyro.api.auth.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun findAllByUserAndRevokedAtIsNull(user: GyroUser): List<RefreshToken>

    @Modifying
    @Query(
        """
        delete from RefreshToken token
        where token.expiresAt < :now
           or (
                token.revokedAt is not null
                and token.revokedAt < :revokedBefore
           )
        """
    )
    fun deleteExpiredOrOldRevoked(
        now: Instant,
        revokedBefore: Instant,
    ): Int

    @Modifying
    @Query("""
    update RefreshToken rt
    set rt.revokedAt = :revokedAt,
        rt.rotationGraceExpiresAt = null
    where rt.user.id = :userId
      and (rt.revokedAt is null or rt.rotationGraceExpiresAt is not null)
""")
    fun revokeAllByUserId(
        userId: UUID,
        revokedAt: Instant,
    ): Int

    @Modifying
    @Query(
        """
        update RefreshToken rt
        set rt.revokedAt = :revokedAt,
            rt.rotationGraceExpiresAt = null
        where rt.familyId = :familyId
          and (rt.revokedAt is null or rt.rotationGraceExpiresAt is not null)
        """
    )
    fun revokeAllByFamilyId(
        familyId: UUID,
        revokedAt: Instant,
    ): Int
}
