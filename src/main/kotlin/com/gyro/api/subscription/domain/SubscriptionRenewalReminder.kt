package com.gyro.api.subscription.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "subscription_renewal_reminders", uniqueConstraints = [UniqueConstraint(columnNames = ["subscription_id", "period_end"])])
class SubscriptionRenewalReminder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(name = "subscription_id", nullable = false) val subscriptionId: Long,
    @Column(name = "period_end", nullable = false) val periodEnd: Instant,
    @Column(name = "reminded_at", nullable = false) val remindedAt: Instant = Instant.now(),
)
