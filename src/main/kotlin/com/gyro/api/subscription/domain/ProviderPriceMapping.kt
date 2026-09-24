package com.gyro.api.subscription.domain

import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "provider_price_mappings",
    uniqueConstraints = [UniqueConstraint(
        columnNames = ["subscription_price_id", "provider", "environment"],
        name = "uk_price_provider_env",
    )],
)
class ProviderPriceMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "subscription_price_id", nullable = false)
    val subscriptionPriceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: PaymentProvider,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val environment: ProviderEnvironment,

    /** Provider's product identifier. Nullable if provider uses dynamic amounts. */
    @Column(name = "provider_product_id")
    val providerProductId: String? = null,

    /** Provider's price identifier. Nullable if provider uses dynamic amounts. */
    @Column(name = "provider_price_id")
    val providerPriceId: String? = null,

    /**
     * Optional amount override for providers that manage their own pricing (Apple, Google Play).
     * When null, the adapter uses the catalog amount from subscription_prices.
     * When set, the adapter sends this amount instead.
     */
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "amount", column = Column(name = "provider_amount_override", precision = 12, scale = 2)),
        AttributeOverride(name = "currency", column = Column(name = "provider_currency_override", length = 3)),
    )
    val providerAmountOverride: Money? = null,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
