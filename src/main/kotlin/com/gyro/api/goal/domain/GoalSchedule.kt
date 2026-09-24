package com.gyro.api.goal.domain

enum class GoalScheduleType {
    FLAT,
    WEEKDAY_WEEKEND,
    ZIGZAG,
    CUSTOM,
}

enum class MacroTargetAdjustmentMode {
    FIXED_GRAMS,
    SCALE_WITH_CALORIES,
    FIXED_PROTEIN_FLEXIBLE_CARBS_FAT,
}
