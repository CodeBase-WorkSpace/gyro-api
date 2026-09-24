package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.AdminPromotionService
import com.gyro.api.subscription.domain.PromotionRedemptionStatus
import com.gyro.api.subscription.domain.PromotionType
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController @RequestMapping("\${app.api.base-path}/admin/promotions") @PreAuthorize("hasRole('ADMIN')")
class AdminPromotionController(private val service: AdminPromotionService, private val redemptions: PromotionRedemptionRepository, private val audit: AccountAuditService) {
    @GetMapping fun list(@RequestParam(required = false) query: String?, @RequestParam(required = false) active: Boolean?, @RequestParam(required = false) type: PromotionType?, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int, request: HttpServletRequest): AdminPromotionPageResponse { val result = service.list(query, active, type, page, size); val counts = service.redemptionCounts(result.content.mapNotNull { it.id }); readAudit(request, "list"); return AdminPromotionPageResponse(result.content.map { AdminPromotionResponse.from(it, counts[it.id]) }, result.number, result.size, result.totalElements, result.totalPages) }
    @PostMapping fun create(@Valid @RequestBody body: AdminPromotionRequest, request: HttpServletRequest): ResponseEntity<AdminPromotionResponse> = ResponseEntity.status(HttpStatus.CREATED).body(service.create(body.command()).also { mutationAudit(request, "create", it.id!!) }.let { AdminPromotionResponse.from(it) })
    @GetMapping("/{id}") fun get(@PathVariable id: Long, request: HttpServletRequest): AdminPromotionResponse { val promotion = service.detail(id); readAudit(request, "detail"); return AdminPromotionResponse.from(promotion, service.redemptionCounts(id)) }
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @Valid @RequestBody body: AdminPromotionRequest, request: HttpServletRequest): AdminPromotionResponse = service.update(id, body.command()).also { mutationAudit(request, "update", id) }.let { AdminPromotionResponse.from(it) }
    @PostMapping("/{id}/archive") @ResponseStatus(HttpStatus.NO_CONTENT) fun archive(@PathVariable id: Long, @RequestBody body: ArchivePromotionRequest, request: HttpServletRequest) { service.archive(id, body.expectedVersion); mutationAudit(request, "archive", id) }
    @GetMapping("/{id}/redemptions") fun redemptions(@PathVariable id: Long, @RequestParam(required = false) status: PromotionRedemptionStatus?, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int, request: HttpServletRequest): Any { readAudit(request, "redemptions"); val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)); return (status?.let { redemptions.findByPromotionIdAndStatus(id, it, pageable) } ?: redemptions.findByPromotionId(id, pageable)).map { mapOf("id" to it.id, "userId" to it.userId, "status" to it.status, "manualGrantId" to it.manualGrantId, "redeemedAt" to it.redeemedAt, "createdAt" to it.createdAt) } }
    @GetMapping("/redemptions/user/{userId}") fun userRedemptions(@PathVariable userId: UUID, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int, request: HttpServletRequest): Any { readAudit(request, "user_redemptions"); return redemptions.findByUserId(userId, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1,100))).map { mapOf("id" to it.id, "promotionId" to it.promotionId, "status" to it.status, "redeemedAt" to it.redeemedAt) } }
    private fun actor(request: HttpServletRequest) = UUID.fromString(request.getAttribute(USER_ID_ATTRIBUTE) as String)
    private fun mutationAudit(request: HttpServletRequest, action: String, id: Long) = audit.record(actor(request), actor(request), AccountAuditEventType.ADMIN_PROMOTION_CHANGED, metadata = mapOf("action" to action, "promotionId" to id))
    private fun readAudit(request: HttpServletRequest, scope: String) = audit.record(actor(request), actor(request), AccountAuditEventType.ADMIN_PROMOTION_READ, metadata = mapOf("scope" to scope))
}
