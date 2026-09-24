package com.gyro.api.subscription.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal

/** Immutable value object: structural equality and `copy` are intentional for embedded monetary values. */
@Embeddable
data class Money(
    @Column(nullable = false, precision = 12, scale = 2)
    val amount: BigDecimal,

    @Column(nullable = false, length = 3)
    val currency: String,
) {
    init {
        require(amount >= BigDecimal.ZERO) { "Amount must be non-negative" }
        require(currency.length == 3) { "Currency must be ISO 4217 alpha-3" }
    }
}
