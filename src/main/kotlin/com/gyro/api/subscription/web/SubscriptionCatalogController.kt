package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.SubscriptionCatalog
import com.gyro.api.subscription.application.SubscriptionCatalogService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("\${app.api.base-path}/billing")
class SubscriptionCatalogController(
    private val subscriptionCatalogService: SubscriptionCatalogService,
) {
    @GetMapping("/plans")
    fun getPlans(
        @RequestParam(required = false) locale: String?,
    ): ResponseEntity<SubscriptionCatalog> {
        return ResponseEntity.ok(subscriptionCatalogService.getPublicCatalog(locale))
    }
}
