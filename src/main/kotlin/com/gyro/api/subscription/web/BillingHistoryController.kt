package com.gyro.api.subscription.web

import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.subscription.application.BillingHistoryCursor
import com.gyro.api.subscription.application.BillingHistoryReadService
import com.gyro.api.subscription.application.BillingHistoryResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.*

@RestController
@RequestMapping("\${app.api.base-path}/billing/me/history")
class BillingHistoryController(
    private val billingHistoryReadService: BillingHistoryReadService,
) {
    @GetMapping
    fun history(
        @AuthenticationPrincipal userId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) beforeCreatedAt: String?,
        @RequestParam(required = false) beforeInvoiceId: String?,
    ): BillingHistoryResponse {
        return billingHistoryReadService.userHistory(
            userId = UUID.fromString(userId),
            limit = limit,
            before = parseCursor(beforeCreatedAt, beforeInvoiceId),
        )
    }

    private fun parseCursor(beforeCreatedAt: String?, beforeInvoiceId: String?): BillingHistoryCursor? {
        if (beforeCreatedAt == null && beforeInvoiceId == null) return null
        if (beforeCreatedAt == null || beforeInvoiceId == null) {
            throw cursorValidationException(
                "beforeCreatedAt and beforeInvoiceId must be provided together.",
            )
        }
        val createdAt = try {
            Instant.parse(beforeCreatedAt)
        } catch (_: DateTimeParseException) {
            throw cursorValidationException("beforeCreatedAt must be an ISO-8601 instant.")
        }
        val invoiceId = try {
            UUID.fromString(beforeInvoiceId)
        } catch (_: IllegalArgumentException) {
            throw cursorValidationException("beforeInvoiceId must be a UUID.")
        }
        return BillingHistoryCursor(createdAt = createdAt, invoiceId = invoiceId)
    }

    private fun cursorValidationException(message: String): FieldValidationException {
        return FieldValidationException(
            message = "Billing history cursor is invalid.",
            fieldErrors = listOf(ApiErrorResponse.FieldError(field = "beforeCreatedAt", errorMessage = message)),
        )
    }
}
