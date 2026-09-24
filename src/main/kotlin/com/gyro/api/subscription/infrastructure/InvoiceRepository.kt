package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.Invoice
import com.gyro.api.subscription.domain.InvoiceStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.*

interface InvoiceRepository : JpaRepository<Invoice, UUID> {
    @Query("select i.userId from Invoice i where i.id = :id")
    fun findUserIdById(id: UUID): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<Invoice>
    @Query("select count(i) from Invoice i where i.subscriptionPriceId = :priceId and i.status = 'OPEN'")
    fun countOpenBySubscriptionPriceId(priceId: Long): Long
    fun findByUserIdAndStatus(userId: UUID, status: InvoiceStatus): List<Invoice>
    fun countByUserIdAndStatus(userId: UUID, status: InvoiceStatus): Long

    @Query(
        """
        select i from Invoice i
        where i.userId = :userId
          and i.status = 'OPEN'
        order by i.createdAt desc
        """
    )
    fun findOpenByUserId(userId: UUID): List<Invoice>
}
