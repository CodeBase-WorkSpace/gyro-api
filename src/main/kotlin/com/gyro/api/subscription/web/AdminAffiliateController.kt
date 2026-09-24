package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.AffiliateService
import com.gyro.api.subscription.domain.AffiliateStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/admin/affiliates")
@PreAuthorize("hasRole('ADMIN')")
class AdminAffiliateController(private val service: AffiliateService, private val audit: AccountAuditService) {
    @GetMapping fun list(@RequestParam(required = false) query: String?, @RequestParam(required = false) status: AffiliateStatus?, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int, request: HttpServletRequest): AffiliatePageResponse { val result = service.list(query, status, page, size); readAudit(request, "list"); return AffiliatePageResponse(result.content.map(::response), result.number, result.size, result.totalElements, result.totalPages) }
    @GetMapping("/{id}") fun detail(@PathVariable id: UUID, request: HttpServletRequest) = response(service.detail(id)).also { readAudit(request, "detail") }
    @PostMapping fun create(@Valid @RequestBody body: AffiliateCreateRequest, request: HttpServletRequest): ResponseEntity<AffiliateResponse> { val affiliate = service.create(body.command()); mutationAudit(request, "create", affiliate.id!!); return ResponseEntity.status(HttpStatus.CREATED).body(response(affiliate)) }
    @PutMapping("/{id}") fun update(@PathVariable id: UUID, @Valid @RequestBody body: AffiliateUpdateRequest, request: HttpServletRequest) = response(service.update(id, body.command())).also { mutationAudit(request, "update", id) }
    @PostMapping("/{id}/activate") fun activate(@PathVariable id: UUID, request: HttpServletRequest) = response(service.setActive(id, true)).also { mutationAudit(request, "activate", id) }
    @PostMapping("/{id}/deactivate") fun deactivate(@PathVariable id: UUID, request: HttpServletRequest) = response(service.setActive(id, false)).also { mutationAudit(request, "deactivate", id) }
    @PutMapping("/{id}/account") fun account(@PathVariable id: UUID, @RequestBody body: AffiliateAccountRequest, request: HttpServletRequest) = response(service.assignAccount(id, body.userId)).also { mutationAudit(request, if (body.userId == null) "unlink_account" else "link_account", id) }
    @GetMapping("/{id}/report") fun report(@PathVariable id: UUID, request: HttpServletRequest) = service.aggregate(id).also { readAudit(request, "report") }
    private fun response(a: com.gyro.api.subscription.domain.Affiliate) = AffiliateResponse.from(a, service.promotion(a), service.aggregate(a.id!!))
    private fun actor(r: HttpServletRequest) = UUID.fromString(r.getAttribute(USER_ID_ATTRIBUTE) as String)
    private fun mutationAudit(r: HttpServletRequest, action: String, id: UUID) = audit.record(actor(r), actor(r), AccountAuditEventType.ADMIN_AFFILIATE_CHANGED, metadata = mapOf("action" to action, "affiliateId" to id))
    private fun readAudit(r: HttpServletRequest, scope: String) = audit.record(actor(r), actor(r), AccountAuditEventType.ADMIN_AFFILIATE_READ, metadata = mapOf("scope" to scope))
}
