package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.SubscriptionLifecycleService
import com.gyro.api.subscription.application.SubscriptionSummary
import com.gyro.api.subscription.application.SubscriptionSummaryService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/billing/me/subscription")
class SubscriptionController(private val lifecycle: SubscriptionLifecycleService, private val summaries: SubscriptionSummaryService) {
    @GetMapping fun current(@AuthenticationPrincipal userId: String): SubscriptionSummary = summaries.current(UUID.fromString(userId))
    @PostMapping("/cancel") fun cancel(@AuthenticationPrincipal userId: String): SubscriptionSummary = summaries.summary(lifecycle.cancelAtPeriodEnd(UUID.fromString(userId)))
    @PostMapping("/restore") fun restore(@AuthenticationPrincipal userId: String): SubscriptionSummary = summaries.summary(lifecycle.restoreSubscription(UUID.fromString(userId)))
}
