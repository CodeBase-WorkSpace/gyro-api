package com.gyro.api.user.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.DeletionPreviewStaleException
import com.gyro.api.common.id.UuidParser
import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.user.application.AdminUserDeletionService
import com.gyro.api.user.application.AdminDeletionSafetyService
import com.gyro.api.user.application.AdminUserQueryService
import com.gyro.api.user.web.dto.AdminUserDeletionConfirmRequest
import com.gyro.api.user.web.dto.AdminUserDeletionPreviewResponse
import com.gyro.api.user.web.dto.AdminUserDeletionResultResponse
import com.gyro.api.user.web.dto.AdminUserDetailResponse
import com.gyro.api.user.web.dto.AdminUserSummaryResponse
import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController(
    private val adminUserQueryService: AdminUserQueryService,
    private val adminUserDeletionService: AdminUserDeletionService,
    private val adminDeletionSafetyService: AdminDeletionSafetyService,
    private val accountAuditService: AccountAuditService,
) {
    @GetMapping
    fun searchUsers(
        @AuthenticationPrincipal adminId: String,
        @RequestParam(required = false) @Size(max = 320) query: String?,
        @RequestParam(required = false) @Size(max = 32) role: String?,
        @RequestParam(required = false) @Size(max = 32) status: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) size: Int,
    ): PageResponse<AdminUserSummaryResponse> {
        val actorId = UUID.fromString(adminId)
        val result = adminUserQueryService.searchUsers(
            query = query,
            role = role,
            status = status,
            page = page,
            size = size,
        )
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = actorId,
            eventType = AccountAuditEventType.ADMIN_USER_READ,
            metadata = mapOf(
                "scope" to "user_search",
                "queryProvided" to !query.isNullOrBlank(),
                "resultCount" to result.items.size,
            ),
        )
        return result
    }

    @GetMapping("/{userId}")
    fun getUserDetail(
        @AuthenticationPrincipal adminId: String,
        @PathVariable userId: UUID,
    ): AdminUserDetailResponse {
        val actorId = UUID.fromString(adminId)
        val detail = adminUserQueryService.getUserDetail(userId)
        accountAuditService.record(
            actorUserId = actorId,
            targetUserId = userId,
            eventType = AccountAuditEventType.ADMIN_USER_READ,
            metadata = mapOf("scope" to "user_detail"),
        )
        return detail
    }

    @PostMapping("/{userId}/deletion-preview")
    @PreAuthorize("hasRole('ADMIN') and @adminDeletionPolicy.enabled()")
    fun previewDeletion(
        @AuthenticationPrincipal adminId: String,
        @PathVariable userId: UUID,
        servletRequest: HttpServletRequest,
    ): AdminUserDeletionPreviewResponse {
        val actingAdminId = UUID.fromString(adminId)
        adminDeletionSafetyService.requireDeletionAuthority(actingAdminId, servletRequest.getHeader("Authorization"))
        adminDeletionSafetyService.checkRateLimit(actingAdminId)
        return adminUserDeletionService.previewDeletion(
            actingAdminId = actingAdminId,
            targetUserId = userId,
        )
    }

    @PostMapping("/{userId}/delete")
    @PreAuthorize("hasRole('ADMIN') and @adminDeletionPolicy.enabled()")
    fun confirmDeletion(
        @AuthenticationPrincipal adminId: String,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: AdminUserDeletionConfirmRequest,
        servletRequest: HttpServletRequest,
    ): AdminUserDeletionResultResponse {
        val operationId = UuidParser.parse(request.operationId.trim())
            ?: throw DeletionPreviewStaleException()
        val actingAdminId = UUID.fromString(adminId)
        adminDeletionSafetyService.requireDeletionAuthority(actingAdminId, servletRequest.getHeader("Authorization"))
        adminDeletionSafetyService.checkRateLimit(actingAdminId)
        return adminUserDeletionService.confirmDeletion(
            actingAdminId = actingAdminId,
            targetUserId = userId,
            operationId = operationId,
            confirmationToken = request.confirmationToken.trim(),
            confirmation = request.confirmation,
            reason = request.reason,
        )
    }
}
