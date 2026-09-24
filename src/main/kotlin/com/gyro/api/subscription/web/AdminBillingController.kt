package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.InvoiceNotFoundException
import com.gyro.api.subscription.application.AdminBillingInvoiceDetail
import com.gyro.api.subscription.application.AdminBillingReadService
import com.gyro.api.subscription.application.AdminBillingSnapshot
import com.gyro.api.subscription.application.BillingHistoryReadService
import com.gyro.api.subscription.application.AdminLifecycleReadService
import com.gyro.api.subscription.application.AdminLifecycleSnapshot
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("\${app.api.base-path}/admin/billing")
class AdminBillingController(
    private val adminBillingReadService: AdminBillingReadService,
    private val billingHistoryReadService: BillingHistoryReadService,
    private val accountAuditService: AccountAuditService,
    private val lifecycleReadService: AdminLifecycleReadService,
) {
    @GetMapping("/lifecycle")
    @PreAuthorize("hasRole('ADMIN')")
    fun lifecycle(@AuthenticationPrincipal adminId: String): ResponseEntity<AdminLifecycleSnapshot> {
        val actorId = UUID.fromString(adminId)
        val result = lifecycleReadService.snapshot()
        accountAuditService.record(actorUserId = actorId, targetUserId = actorId, eventType = AccountAuditEventType.ADMIN_BILLING_READ, metadata = mapOf("scope" to "lifecycle", "outcome" to "SUCCESS"))
        return ResponseEntity.ok(result)
    }
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun snapshot(
        @AuthenticationPrincipal adminId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<AdminBillingSnapshot> {
        val actorId = UUID.fromString(adminId)
        val snapshot = adminBillingReadService.snapshot(limit, query, status)
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = actorId,
            eventType = AccountAuditEventType.ADMIN_BILLING_READ,
            metadata = mapOf(
                "scope" to "search",
                "queryProvided" to !query.isNullOrBlank(),
                "status" to status?.trim()?.uppercase(),
                "resultCount" to snapshot.recentAttempts.size,
            ),
        )
        return ResponseEntity.ok(snapshot)
    }

    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize("hasRole('ADMIN')")
    fun invoiceDetail(
        @AuthenticationPrincipal adminId: String,
        @PathVariable invoiceId: UUID,
    ): ResponseEntity<AdminBillingInvoiceDetail> {
        val actorId = UUID.fromString(adminId)
        val detail = try {
            billingHistoryReadService.adminInvoice(invoiceId)
        } catch (exception: InvoiceNotFoundException) {
            accountAuditService.record(
                actorUserId = actorId,
                targetUserId = actorId,
                eventType = AccountAuditEventType.ADMIN_BILLING_READ,
                metadata = mapOf(
                    "scope" to "invoice_detail",
                    "invoiceId" to invoiceId.toString(),
                    "outcome" to "NOT_FOUND",
                ),
            )
            throw exception
        }
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = detail.user.userId,
            eventType = AccountAuditEventType.ADMIN_BILLING_READ,
            metadata = mapOf(
                "scope" to "invoice_detail",
                "invoiceId" to invoiceId.toString(),
                "outcome" to "SUCCESS",
            ),
        )
        return ResponseEntity.ok(detail)
    }
}
