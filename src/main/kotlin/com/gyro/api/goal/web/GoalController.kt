package com.gyro.api.goal.web

import com.gyro.api.common.observability.StageLog
import com.gyro.api.goal.application.GoalCommandService
import com.gyro.api.goal.application.GoalPreviewApplicationService
import com.gyro.api.goal.application.GoalQueryService
import com.gyro.api.goal.web.dto.GoalPreviewRequest
import com.gyro.api.goal.web.dto.GoalPreviewResponse
import com.gyro.api.goal.web.dto.GoalResponse
import com.gyro.api.goal.web.dto.SaveGoalRequest
import com.gyro.api.goal.web.dto.toResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@Validated
@RestController
@RequestMapping("\${app.api.base-path}/goals")
class GoalController(
    private val goalPreviewApplicationService: GoalPreviewApplicationService,
    private val goalQueryService: GoalQueryService,
    private val goalCommandService: GoalCommandService,
) {
    @PostMapping("/preview")
    fun previewGoal(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: GoalPreviewRequest,
    ): GoalPreviewResponse {
        return StageLog.around(
            logger = logger,
            event = GOAL_LOG_EVENT,
            stage = "calculator_preview",
        ) {
            goalPreviewApplicationService.preview(
                userId = UUID.fromString(userId),
                input = request.toInput(),
            ).toResponse()
        }
    }

    @GetMapping
    fun getGoals(
        @AuthenticationPrincipal userId: String,
    ): GoalResponse {
        return StageLog.around(
            logger = logger,
            event = GOAL_LOG_EVENT,
            stage = "current_query",
        ) {
            goalQueryService.getCurrentGoal(UUID.fromString(userId)).toResponse()
        }
    }

    @PutMapping
    fun saveGoal(
        @AuthenticationPrincipal userId: String,
        @Valid @RequestBody request: SaveGoalRequest,
    ): GoalResponse {
        return StageLog.around(
            logger = logger,
            event = GOAL_LOG_EVENT,
            stage = "save",
            fields = mapOf(
                "acceptedWarningCount" to request.acceptedWarningCodes.size,
                "blockingWarningCount" to request.blockingWarningCodes.size,
                "calculatorUpdateMode" to (request.activePlan?.calculatorUpdateMode?.name ?: "LEGACY"),
            ),
        ) {
            goalCommandService.saveGoal(
                userId = UUID.fromString(userId),
                command = request.toCommand(),
            ).toResponse()
        }
    }

    @DeleteMapping
    fun deleteGoal(
        @AuthenticationPrincipal userId: String,
    ): GoalResponse {
        return StageLog.around(
            logger = logger,
            event = GOAL_LOG_EVENT,
            stage = "delete",
        ) {
            goalCommandService.deleteGoal(UUID.fromString(userId)).toResponse()
        }
    }

    companion object {
        private const val GOAL_LOG_EVENT = "goal"
        private val logger = LoggerFactory.getLogger(GoalController::class.java)
    }
}
