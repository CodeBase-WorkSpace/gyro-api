package com.gyro.api.common.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "outbox_event_consumptions",
    uniqueConstraints = [UniqueConstraint(
        columnNames = ["event_id", "consumer_name"],
        name = "uk_event_consumer",
    )],
)
class OutboxEventConsumption(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "event_id", nullable = false)
    val eventId: Long,
    @Column(name = "consumer_name", nullable = false)
    val consumerName: String,
    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant = Instant.now(),
)
