package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.ManualGrantService
import com.gyro.api.subscription.domain.ManualGrant
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import com.gyro.api.subscription.infrastructure.PromotionRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("\${app.api.base-path}/admin/grants")
@PreAuthorize("hasRole('ADMIN')")
class ManualGrantController(
    private val manualGrantService: ManualGrantService,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val cachedEntitlementService: CachedEntitlementService,
    private val entitlementGateService: EntitlementGateService,
    private val accountAuditService: AccountAuditService,
    private val promotionRedemptionRepository: PromotionRedemptionRepository,
    private val promotionRepository: PromotionRepository,
) {

    @PostMapping
    fun createGrant(
        @Valid @RequestBody request: CreateManualGrantRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ManualGrantResponse> {
        val adminId = servletRequest.authenticatedAdminId()

        val grant = manualGrantService.createGrant(
            userId = request.userId!!,
            planId = request.planId!!,
            durationDays = request.durationDays!!,
            reason = request.reason!!,
            grantedBy = adminId,
            reasonNote = request.reasonNote,
            promotionCode = request.promotionCode,
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(grant.toResponse())
    }

    @PostMapping("/{grantId}/extend")
    fun extendGrant(
        @PathVariable grantId: UUID,
        @Valid @RequestBody request: ExtendManualGrantRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ManualGrantResponse> {
        val adminId = servletRequest.authenticatedAdminId()

        val grant = manualGrantService.extendGrant(
            grantId = grantId,
            additionalDays = request.additionalDays!!,
            adminId = adminId,
            reason = request.reason!!,
        )

        return ResponseEntity.ok(grant.toResponse())
    }

    @PostMapping("/{grantId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeGrant(
        @PathVariable grantId: UUID,
        @Valid @RequestBody request: RevokeManualGrantRequest,
        servletRequest: HttpServletRequest,
    ) {
        val adminId = servletRequest.authenticatedAdminId()

        manualGrantService.revokeGrant(
            grantId = grantId,
            revokedBy = adminId,
            reason = request.reason!!,
        )
    }

    @GetMapping("/user/{userId}")
    fun listUserGrants(
        @PathVariable userId: UUID,
    ): ResponseEntity<List<ManualGrantResponse>> {
        val grants = manualGrantService.findGrants(userId)
        return ResponseEntity.ok(grants.map { it.toResponse() })
    }

    @GetMapping("/plans")
    fun listGrantPlans(): List<AdminGrantPlanResponse> =
        subscriptionPlanRepository.findByActiveTrueOrderByFreeDescCodeAsc().map { plan ->
            AdminGrantPlanResponse(id = requireNotNull(plan.id), code = plan.code, name = plan.name)
        }

    @PostMapping("/user/{userId}/recalculate-entitlement")
    fun recalculateEntitlement(
        @PathVariable userId: UUID,
        @Valid @RequestBody request: RecalculateEntitlementRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<EntitlementResponse> {
        cachedEntitlementService.invalidate(userId)
        val entitlement = entitlementGateService.entitlementFor(userId)
        accountAuditService.record(
            actorUserId = servletRequest.authenticatedAdminId(),
            targetUserId = userId,
            eventType = AccountAuditEventType.ADMIN_ENTITLEMENT_RECALCULATED,
            reason = request.reason,
            metadata = mapOf("source" to entitlement.source.name),
        )
        return ResponseEntity.ok(entitlement.toResponse())
    }

    private fun HttpServletRequest.authenticatedAdminId(): UUID {
        val userId = getAttribute(USER_ID_ATTRIBUTE) as? String
            ?: throw AccessDeniedException("Authenticated admin identity is unavailable.")
        return UUID.fromString(userId)
    }

    private fun ManualGrant.toResponse(): ManualGrantResponse {
        return ManualGrantResponse(
            id = id!!,
            userId = userId,
            planId = planId,
            durationDays = durationDays,
            periodStart = periodStart,
            expiresAt = expiresAt,
            reason = reason,
            reasonNote = reasonNote,
            grantedBy = grantedBy,
            revokedAt = revokedAt,
            revokedBy = revokedBy,
            revokeReason = revokeReason,
            createdAt = createdAt,
            promotionCode = id?.let { grantId -> promotionRedemptionRepository.findByManualGrantId(grantId)?.promotionId }
                ?.let { promotionId -> promotionRepository.findById(promotionId).orElse(null)?.code },
        )
    }

}
