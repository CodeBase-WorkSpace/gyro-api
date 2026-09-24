package com.gyro.api.user.web

import com.gyro.api.common.observability.StageLog
import com.gyro.api.user.application.ActivityHeatmapDay
import com.gyro.api.user.application.ActivityHeatmapReadModel
import com.gyro.api.user.application.ActivityHeatmapService
import com.gyro.api.user.application.ActivityHeatmapBucketThreshold
import com.gyro.api.daily_score.application.DailyScoreBand
import com.gyro.api.daily_score.application.DailyScoreMode
import com.gyro.api.user.application.UserService
import com.gyro.api.user.application.UpdateUserProfileCommand
import com.gyro.api.user.application.UserProfileView
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}")
class MeController(
    private val userService: UserService,
    private val activityHeatmapService: ActivityHeatmapService,
) {

    @GetMapping("/users/me")
    fun me(@AuthenticationPrincipal userId: String): MeResponse {
        return StageLog.around(
            logger = logger,
            event = ACCOUNT_STATE_LOG_EVENT,
            stage = "profile_query",
        ) {
            val profile = userService.getProfile(UUID.fromString(userId))
            profile.toResponse()
        }
    }

    @GetMapping("/users/me/activity-heatmap")
    fun activityHeatmap(
        @AuthenticationPrincipal userId: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): ActivityHeatmapResponse {
        return StageLog.around(
            logger = logger,
            event = ACCOUNT_STATE_LOG_EVENT,
            stage = "activity_state_query",
            fields = mapOf("rangePresent" to true),
        ) {
            val heatmap = activityHeatmapService.activityHeatmap(
                userId = UUID.fromString(userId),
                from = from,
                to = to,
            )

            heatmap.toResponse()
        }
    }

    @PatchMapping("/users/me/profile")
    fun updateProfile(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: UpdateMeProfileRequest,
    ): MeResponse {
        return StageLog.around(
            logger = logger,
            event = ACCOUNT_STATE_LOG_EVENT,
            stage = "profile_update",
            fields = mapOf(
                "displayNamePresent" to !request.displayName.isNullOrBlank(),
                "timezonePresent" to !request.timezone.isNullOrBlank(),
                "localePresent" to !request.locale.isNullOrBlank(),
            ),
        ) {
            val profile = userService.updateProfile(
                userId = UUID.fromString(userId),
                command = UpdateUserProfileCommand(
                    displayName = request.displayName,
                    timezone = request.timezone,
                    locale = request.locale,
                ),
            )

            profile.toResponse()
        }
    }

    @PostMapping("/users/me/onboarding/welcome-seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markOnboardingWelcomeSeen(@AuthenticationPrincipal userId: String) {
        userService.markOnboardingWelcomeSeen(UUID.fromString(userId))
    }

    @PostMapping("/users/me/coach/calculator-rerun-prompt/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acknowledgeCalculatorRerunPrompt(@AuthenticationPrincipal userId: String) {
        userService.acknowledgeCalculatorRerunPrompt(UUID.fromString(userId))
    }

    @DeleteMapping("/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@AuthenticationPrincipal userId: String) {
        StageLog.around(
            logger = logger,
            event = ACCOUNT_STATE_LOG_EVENT,
            stage = "deactivate",
        ) {
            userService.deactivateCurrentUser(UUID.fromString(userId))
        }
    }

    private fun UserProfileView.toResponse(): MeResponse {
        return MeResponse(
            id = id,
            email = email,
            phoneNumber = phoneNumber,
            displayName = displayName,
            timezone = timezone,
            locale = locale,
            role = role,
            status = status,
            emailVerificationStatus = emailVerificationStatus,
            phoneVerificationStatus = phoneVerificationStatus,
            hasPassword = hasPassword,
            onboardingWelcomeSeenAt = onboardingWelcomeSeenAt,
            calculatorRerunPromptAcknowledgedAt = calculatorRerunPromptAcknowledgedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ActivityHeatmapReadModel.toResponse(): ActivityHeatmapResponse {
        return ActivityHeatmapResponse(
            from = from,
            to = to,
            timezone = timezone,
            locale = locale,
            totalLoggedDays = totalLoggedDays,
            maxEntryCount = maxEntryCount,
            bucketThresholds = bucketThresholds.map { it.toResponse() },
            days = days.map { it.toResponse() },
        )
    }

    private fun ActivityHeatmapBucketThreshold.toResponse(): ActivityHeatmapBucketThresholdResponse {
        return ActivityHeatmapBucketThresholdResponse(
            bucket = bucket,
            minEntryCount = minEntryCount,
            maxEntryCount = maxEntryCount,
        )
    }

    private fun ActivityHeatmapDay.toResponse(): ActivityHeatmapDayResponse {
        return ActivityHeatmapDayResponse(
            date = date,
            entryCount = entryCount,
            logged = logged,
            intensity = intensity,
            score = score,
            scoreMode = scoreMode,
            scoreBand = scoreBand,
            finalizedAt = finalizedAt,
        )
    }

    companion object {
        private const val ACCOUNT_STATE_LOG_EVENT = "account_state"
        private val logger = LoggerFactory.getLogger(MeController::class.java)
    }
}

data class ActivityHeatmapResponse(
    val from: LocalDate,
    val to: LocalDate,
    val timezone: String,
    val locale: String,
    val totalLoggedDays: Int,
    val maxEntryCount: Int,
    val bucketThresholds: List<ActivityHeatmapBucketThresholdResponse>,
    val days: List<ActivityHeatmapDayResponse>,
)

data class ActivityHeatmapBucketThresholdResponse(
    val bucket: Int,
    val minEntryCount: Int,
    val maxEntryCount: Int?,
)

data class ActivityHeatmapDayResponse(
    val date: LocalDate,
    val entryCount: Int,
    val logged: Boolean,
    val intensity: Int,
    val score: Int?,
    val scoreMode: DailyScoreMode?,
    val scoreBand: DailyScoreBand?,
    val finalizedAt: Instant?,
)

data class UpdateMeProfileRequest(
    @field:Size(max = 120)
    val displayName: String? = null,

    @field:Size(max = 64)
    val timezone: String? = null,

    @field:Size(max = 16)
    val locale: String? = null,
)
