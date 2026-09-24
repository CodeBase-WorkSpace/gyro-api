package com.gyro.api.user.infrastructure

import com.gyro.api.user.domain.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserProfileRepository : JpaRepository<UserProfile, UUID> {
    fun findByUser_Id(userId: UUID): UserProfile?
}
