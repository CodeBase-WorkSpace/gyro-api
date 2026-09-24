package com.gyro.api.goal.web

import com.gyro.api.goal.application.recalibration.RecalibrationService
import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.goal.domain.RecalibrationDismissReason
import com.gyro.api.subscription.application.EntitlementGateService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RecalibrationSuggestionResponse(
    val id: UUID,
    val status: String,
    val suggested: RecalibrationTargetsResponse,
    val previous: RecalibrationTargetsResponse,
    val basis: Map<String, Any?>,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class RecalibrationTargetsResponse(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
)

data class RecalibrationPendingResponse(
    val suggestion: RecalibrationSuggestionResponse?,
)

data class RecalibrationDecisionResponse(
    val suggestion: RecalibrationSuggestionResponse,
    val applied: Boolean,
)

data class DismissRecalibrationRequest(
    /**
     * Optional product-feedback signal. It is stored outside the engine basis
     * and intentionally omitted from suggestion responses.
     */
    val reason: RecalibrationDismissReason? = null,
)

@RestController
@RequestMapping("\${app.api.base-path}/goals/recalibration")
class RecalibrationController(
    private val recalibrationService: RecalibrationService,
    private val entitlementGateService: EntitlementGateService,
) {
    @GetMapping("/pending")
    fun pending(@AuthenticationPrincipal userId: String): RecalibrationPendingResponse {
        val id = UUID.fromString(userId)
        entitlementGateService.requireFeature(id, FEATURE_KEY, ROUTE)
        return RecalibrationPendingResponse(
            suggestion = recalibrationService.pendingFor(id)?.toResponse(),
        )
    }

    @PostMapping("/{suggestionId}/accept")
    fun accept(
        @AuthenticationPrincipal userId: String,
        @PathVariable suggestionId: UUID,
    ): RecalibrationDecisionResponse {
        val id = UUID.fromString(userId)
        entitlementGateService.requireFeature(id, FEATURE_KEY, ROUTE)
        val decision = recalibrationService.accept(id, suggestionId)
        return RecalibrationDecisionResponse(
            suggestion = decision.suggestion.toResponse(),
            applied = decision.applied,
        )
    }

    @PostMapping("/{suggestionId}/dismiss")
    fun dismiss(
        @AuthenticationPrincipal userId: String,
        @PathVariable suggestionId: UUID,
        @RequestBody(required = false) request: DismissRecalibrationRequest?,
    ): RecalibrationDecisionResponse {
        val id = UUID.fromString(userId)
        entitlementGateService.requireFeature(id, FEATURE_KEY, ROUTE)
        val suggestion = recalibrationService.dismiss(id, suggestionId, request?.reason)
        return RecalibrationDecisionResponse(
            suggestion = suggestion.toResponse(),
            applied = false,
        )
    }

    companion object {
        private const val FEATURE_KEY = "goal_recalibration"
        private const val ROUTE = "/api/v1/goals/recalibration"
    }
}

fun PlanRecalibrationSuggestion.toResponse() = RecalibrationSuggestionResponse(
        id = requireNotNull(id),
        status = status.name,
        suggested = RecalibrationTargetsResponse(
            calories = suggestedCalories,
            protein = suggestedProtein,
            carbs = suggestedCarbs,
            fat = suggestedFat,
        ),
        previous = RecalibrationTargetsResponse(
            calories = previousCalories,
            protein = previousProtein,
            carbs = previousCarbs,
            fat = previousFat,
        ),
        basis = basis,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )
