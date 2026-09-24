package com.gyro.api.goal.application.recalibration

data class RecalibrationWindowPolicy(
    val days: Int,
    val minimumLoggedDays: Int,
    val minimumCoverage: Double,
    val requiresRecentCoverage: Boolean,
) {
    companion object {
        val FOURTEEN_DAYS = RecalibrationWindowPolicy(14, 7, 0.50, false)
        val TWENTY_ONE_DAYS = RecalibrationWindowPolicy(21, 13, 0.60, true)
        val TWENTY_EIGHT_DAYS = RecalibrationWindowPolicy(28, 20, 0.70, true)
        val ordered = listOf(FOURTEEN_DAYS, TWENTY_ONE_DAYS, TWENTY_EIGHT_DAYS)

        fun forDaysOrNull(days: Int): RecalibrationWindowPolicy? = ordered.firstOrNull { it.days == days }
    }
}
