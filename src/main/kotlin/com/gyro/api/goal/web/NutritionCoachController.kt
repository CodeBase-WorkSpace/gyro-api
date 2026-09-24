package com.gyro.api.goal.web

import com.gyro.api.goal.application.coach.CoachInsightImpressionService
import com.gyro.api.goal.application.coach.NutritionCoachStateResult
import com.gyro.api.goal.application.coach.NutritionCoachStateService
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/goals/coach")
@ConditionalOnProperty(prefix = "app.nutrition-coach", name = ["enabled"], havingValue = "true")
class NutritionCoachController(
    private val nutritionCoachStateService: NutritionCoachStateService,
    private val coachInsightImpressionService: CoachInsightImpressionService,
) {
    /** Authenticated state discovery intentionally has no premium route gate. */
    @GetMapping("/state")
    fun state(@AuthenticationPrincipal userId: String): NutritionCoachStateResult =
        nutritionCoachStateService.stateFor(UUID.fromString(userId))

    @PostMapping("/impressions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun recordImpression(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: CoachInsightImpressionRequest,
    ) {
        coachInsightImpressionService.recordVisible(
            userId = UUID.fromString(userId),
            impressionId = request.impressionId,
        )
    }
}

data class CoachInsightImpressionRequest(
    @field:Size(min = 1, max = 80, message = "must contain 1 to 80 characters")
    val impressionId: String,
)
