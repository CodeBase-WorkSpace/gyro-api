package com.gyro.api.notification.web

import com.gyro.api.notification.application.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("\${app.api.base-path}/notifications/schedules")
class FoodReminderScheduleController(private val schedules: FoodReminderScheduleService) {
    @GetMapping fun get(@AuthenticationPrincipal userId: String) = schedules.get(UUID.fromString(userId))

    @PutMapping("/{type}")
    fun save(@AuthenticationPrincipal userId: String, @PathVariable type: FoodReminderScheduleType, @Valid @RequestBody request: FoodReminderScheduleRequest): FoodReminderSchedule =
        schedules.save(UUID.fromString(userId), FoodReminderSchedule(type, request.mealType, request.localTime, request.daysOfWeek, request.enabled, FoodReminderScheduleState.PAUSED))
}

data class FoodReminderScheduleRequest(
    val mealType: FoodReminderMealType?,
    @field:NotNull val localTime: LocalTime,
    @field:NotEmpty val daysOfWeek: Set<Int>,
    val enabled: Boolean,
)
