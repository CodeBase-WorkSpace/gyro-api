package com.gyro.api.subscription.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.gyro.api.subscription.application.PromotionValidationResult
import com.gyro.api.subscription.domain.Money
import java.math.BigDecimal

data class CheckoutRequestDto(
    @JsonProperty("priceId")
    val priceId: Long,

    @JsonProperty("promotionCode")
    val promotionCode: String? = null,
)

data class PromotionValidationRequestDto(
    @JsonProperty("priceId")
    val priceId: Long,

    @JsonProperty("promotionCode")
    val promotionCode: String,
)

data class PromotionValidationResponseDto(
    @JsonProperty("valid")
    val valid: Boolean,

    @JsonProperty("promotionCode")
    val promotionCode: String,

    @JsonProperty("amountBeforeDiscount")
    val amountBeforeDiscount: MoneyDto,

    @JsonProperty("amountAfterDiscount")
    val amountAfterDiscount: MoneyDto,

    @JsonProperty("discountAmount")
    val discountAmount: MoneyDto,
    val promotionType: String,
    val redemptionMode: String,
    val freeDays: Int?,
) {
    companion object {
        fun from(result: PromotionValidationResult) = PromotionValidationResponseDto(
            valid = true,
            promotionCode = result.promotionCode,
            amountBeforeDiscount = MoneyDto.from(result.amountBeforeDiscount),
            amountAfterDiscount = MoneyDto.from(result.amountAfterDiscount),
            discountAmount = MoneyDto.from(result.discountAmount),
            promotionType = result.promotionType,
            redemptionMode = result.redemptionMode,
            freeDays = result.freeDays,
        )
    }
}

data class MoneyDto(
    @JsonProperty("amount")
    val amount: BigDecimal,

    @JsonProperty("currency")
    val currency: String,
) {
    companion object {
        fun from(money: Money) = MoneyDto(
            amount = money.amount,
            currency = money.currency,
        )
    }
}
