package com.gyro.api.subscription.web

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CheckoutResponseDto(
    val status: String,
    val invoiceId: String? = null,
    val paymentAttemptId: String? = null,
    val gatewayUrl: String? = null,
    val requestId: String? = null,
    val failureReason: String? = null,
)
