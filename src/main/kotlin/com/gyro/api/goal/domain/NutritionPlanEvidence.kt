package com.gyro.api.goal.domain

fun NutritionPlanEntity.hasCompleteCalculatorProvenance(): Boolean {
    val legacyComplete = calculatorFormula != null &&
        calculatorFormulaVersion != null &&
        maintenanceCalories != null &&
        dailyEnergyDelta != null &&
        weeklyWeightChangeKg != null
    if (!legacyComplete) return false

    return when (dailyEnergyDeltaSource) {
        DailyEnergyDeltaSource.OBSERVED_WIZARD ->
            calculatorMaintenanceSource == CalculatorMaintenanceSource.OBSERVED &&
                formulaMaintenanceCalories != null &&
                calculatorObservationBasis != null
        DailyEnergyDeltaSource.RECALIBRATION_AUDIT -> false
        DailyEnergyDeltaSource.FORMULA_WIZARD ->
            calculatorMaintenanceSource == CalculatorMaintenanceSource.FORMULA &&
                formulaMaintenanceCalories != null
        null -> true // Complete snapshots created before source tracking remain trusted.
    }
}

fun NutritionPlanEntity.hasTrustedDailyEnergyDelta(): Boolean =
    dailyEnergyDelta != null && (dailyEnergyDeltaSource != null || hasCompleteCalculatorProvenance())
