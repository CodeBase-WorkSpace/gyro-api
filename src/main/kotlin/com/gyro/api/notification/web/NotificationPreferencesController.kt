package com.gyro.api.notification.web

import com.gyro.api.notification.application.NotificationPreferenceService
import com.gyro.api.notification.domain.NotificationCategory
import com.gyro.api.notification.domain.UpdateNotificationPreferencesCommand
import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/notifications/preferences")
class NotificationPreferencesController(
    private val preferences: NotificationPreferenceService,
) {
    @GetMapping
    fun get(@AuthenticationPrincipal userId: String): NotificationPreferencesResponse =
        preferences.get(UUID.fromString(userId)).toResponse()

    @PutMapping
    fun update(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: UpdateNotificationPreferencesRequest,
    ): NotificationPreferencesResponse {
        if (request.categories.map(NotificationCategoryPreferenceRequest::category).distinct().size != request.categories.size) {
            throw FieldValidationException(
                fieldErrors = listOf(ApiErrorResponse.FieldError("categories", "Each notification category may be provided once.", "DUPLICATE")),
            )
        }
        return preferences.update(
            UUID.fromString(userId),
            UpdateNotificationPreferencesCommand(
                quietHoursStart = request.quietHoursStart,
                quietHoursEnd = request.quietHoursEnd,
                categories = request.categories.associate { it.category to it.enabled },
            ),
        ).toResponse()
    }
}

data class UpdateNotificationPreferencesRequest(
    @field:NotNull val quietHoursStart: LocalTime,
    @field:NotNull val quietHoursEnd: LocalTime,
    @field:Valid val categories: List<NotificationCategoryPreferenceRequest>,
)

data class NotificationCategoryPreferenceRequest(
    @field:NotNull val category: NotificationCategory,
    val enabled: Boolean,
)

data class NotificationPreferencesResponse(
    val timezone: String,
    val quietHoursStart: LocalTime,
    val quietHoursEnd: LocalTime,
    val categories: List<NotificationCategoryPreferenceResponse>,
    val eligibility: NotificationEligibilityResponse,
)

data class NotificationCategoryPreferenceResponse(val category: NotificationCategory, val enabled: Boolean, val mutable: Boolean)
data class NotificationEligibilityResponse(val emailEligible: Boolean, val smsEligible: Boolean)

private fun com.gyro.api.notification.domain.NotificationPreferencesView.toResponse() = NotificationPreferencesResponse(
    timezone = timezone,
    quietHoursStart = quietHoursStart,
    quietHoursEnd = quietHoursEnd,
    categories = categories.map { NotificationCategoryPreferenceResponse(it.category, it.enabled, it.mutable) },
    eligibility = NotificationEligibilityResponse(emailEligible, smsEligible),
)
