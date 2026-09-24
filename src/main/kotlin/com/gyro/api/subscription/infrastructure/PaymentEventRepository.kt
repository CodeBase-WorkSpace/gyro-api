package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.PaymentEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PaymentEventRepository : JpaRepository<PaymentEvent, Long> {
    fun findByPaymentAttemptId(paymentAttemptId: UUID): List<PaymentEvent>
}
