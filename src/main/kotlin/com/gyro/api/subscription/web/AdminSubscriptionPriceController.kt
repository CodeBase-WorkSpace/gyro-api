package com.gyro.api.subscription.web

import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.SubscriptionPriceManagementService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/admin/subscription-prices")
@PreAuthorize("hasRole('ADMIN')")
class AdminSubscriptionPriceController(private val service: SubscriptionPriceManagementService) {
    @GetMapping("/plans") fun plans() = service.plans().map { (plan, prices) -> AdminPlanPricesResponse.from(plan, prices) }
    @GetMapping fun search(@RequestParam(required = false) planId: Long?, @RequestParam(required = false) active: Boolean?, @RequestParam(required = false) billingPeriodDays: Int?, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): AdminSubscriptionPricePageResponse { val result = service.search(planId, active, billingPeriodDays, page, size); return AdminSubscriptionPricePageResponse(result.content.map(AdminSubscriptionPriceResponse::from), result.number, result.size, result.totalElements, result.totalPages) }
    @PostMapping fun create(@Valid @RequestBody body: CreateSubscriptionPriceRequest, request: HttpServletRequest): ResponseEntity<PriceMutationResponse> = ResponseEntity.status(HttpStatus.CREATED).body(PriceMutationResponse.from(service.create(body.command(), actor(request))))
    @PostMapping("/{id}/deactivate") fun deactivate(@PathVariable id: Long, @Valid @RequestBody body: DeactivateSubscriptionPriceRequest, request: HttpServletRequest) = AdminSubscriptionPriceResponse.from(service.deactivate(id, actor(request), body.reason))
    @GetMapping("/{id}/impact") fun impact(@PathVariable id: Long) = PriceImpactResponse.from(service.impact(id))
    private fun actor(request: HttpServletRequest) = UUID.fromString(request.getAttribute(USER_ID_ATTRIBUTE) as String)
}
