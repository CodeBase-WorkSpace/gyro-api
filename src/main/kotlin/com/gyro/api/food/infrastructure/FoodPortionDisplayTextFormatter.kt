package com.gyro.api.food.infrastructure

import java.math.BigDecimal

internal fun localizedPortionDisplayText(
    locale: String,
    amount: BigDecimal,
    unitName: String?,
    modifier: String?,
    gramWeight: BigDecimal?,
    portionDescription: String?,
): String {
    val fallback = listOfNotNull(amount.stripTrailingZeros().toPlainString(), unitName, modifier)
        .joinToString(" ")
    if (!locale.isPersianLocale()) return portionDescription ?: fallback

    val label = listOfNotNull(
        amount.stripTrailingZeros().toPlainString(),
        unitName,
        modifier,
    ).joinToString(" ")
    val gramLabel = gramWeight
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?.let { "$it گرم" }

    return listOfNotNull(label.ifBlank { null }, gramLabel?.let { "($it)" }).joinToString(" ")
        .ifBlank { portionDescription ?: fallback }
}

internal fun String.isPersianLocale(): Boolean {
    return this == "fa" || this.startsWith("fa-")
}
