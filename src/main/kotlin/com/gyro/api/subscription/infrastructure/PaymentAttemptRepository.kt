package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PaymentAttempt
import com.gyro.api.subscription.domain.PaymentProvider
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.*

interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, UUID> {
    @Query("select count(pa) from PaymentAttempt pa where pa.invoiceId in (select i.id from Invoice i where i.subscriptionPriceId = :priceId) and pa.status in ('PENDING', 'VERIFY_PENDING')")
    fun countPendingBySubscriptionPriceId(priceId: Long): Long
    fun findByClientRefId(clientRefId: String): Optional<PaymentAttempt>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pa from PaymentAttempt pa where pa.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<PaymentAttempt>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pa from PaymentAttempt pa where pa.clientRefId = :clientRefId")
    fun findByClientRefIdForUpdate(clientRefId: String): Optional<PaymentAttempt>

    fun findByProviderAndProviderRefId(provider: PaymentProvider, providerRefId: String): Optional<PaymentAttempt>

    fun findByInvoiceId(invoiceId: UUID): List<PaymentAttempt>

    fun findByProviderCode(providerCode: String): List<PaymentAttempt>

    @Query(
        """
        select pa from PaymentAttempt pa
        where pa.status = 'VERIFY_PENDING'
        """
    )
    fun findPendingVerification(): List<PaymentAttempt>

    @Query(
        """
        select pa from PaymentAttempt pa
        where pa.provider = 'PAYPING'
          and pa.status in ('PENDING', 'VERIFY_PENDING')
          and pa.providerRefId is not null
        """
    )
    fun findLocallyVerifiableAttempts(): List<PaymentAttempt>

    @Query(
        """
        select pa from PaymentAttempt pa
        where pa.provider = 'PAYPING'
          and pa.status in ('PENDING', 'VERIFY_PENDING')
          and pa.providerRefId is not null
          and pa.createdAt >= :threshold
        order by pa.createdAt asc
        """
    )
    fun findReconciliationCandidates(threshold: Instant, pageable: org.springframework.data.domain.Pageable): List<PaymentAttempt>

    @Query(
        """
        select pa from PaymentAttempt pa
        where pa.provider = 'PAYPING'
          and pa.status in ('PENDING', 'VERIFY_PENDING')
          and pa.providerRefId is not null
          and pa.createdAt < :threshold
        order by pa.createdAt asc
        """
    )
    fun findExpiredReconciliationCandidates(
        threshold: Instant,
        pageable: org.springframework.data.domain.Pageable,
    ): List<PaymentAttempt>
}
