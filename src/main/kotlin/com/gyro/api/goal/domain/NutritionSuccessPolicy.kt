package com.gyro.api.goal.domain

import java.math.BigDecimal

object NutritionSuccessPolicy {
    /** Protein is a success at 90% of the schedule-aware daily target. */
    val PROTEIN_TARGET_RATIO: BigDecimal = BigDecimal("0.90")
}
