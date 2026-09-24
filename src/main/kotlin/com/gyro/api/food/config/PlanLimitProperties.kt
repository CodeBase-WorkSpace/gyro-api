package com.gyro.api.food.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.plan-limits")
data class PlanLimitProperties(
    val freeCustomFoods: Int = 2,
    val freeCustomMeals: Int = 2,
)
