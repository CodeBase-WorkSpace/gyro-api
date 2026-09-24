package com.gyro.api.goal.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.goal-schedules")
data class GoalScheduleProperties(
    val premiumGoalSchedulesEnabled: Boolean = true,
)
