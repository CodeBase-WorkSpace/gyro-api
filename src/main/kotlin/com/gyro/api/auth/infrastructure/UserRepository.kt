package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.domain.GyroUser
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface UserRepository : JpaRepository<GyroUser, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from GyroUser u where u.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<GyroUser>
    fun findByEmail(email: String): GyroUser?
    fun existsByEmail(email: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): GyroUser?
    fun existsByPhoneNumber(phoneNumber: String): Boolean
}
