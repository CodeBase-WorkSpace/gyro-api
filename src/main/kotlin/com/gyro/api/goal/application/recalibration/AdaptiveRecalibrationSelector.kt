package com.gyro.api.goal.application.recalibration

import java.math.BigDecimal

data class AdaptiveRecalibrationSelection(
    val outcome: RecalibrationOutcome,
    val evaluations: List<RecalibrationWindowEvaluation>,
    val selectedWindowDays: Int?,
)

/** Selects the smallest trustworthy evidence window, then validates it against every other usable trend. */
object AdaptiveRecalibrationSelector {
    private val flatZoneKgPerWeek = BigDecimal("0.10")
    private val materialConflictKgPerWeek = BigDecimal("0.25")

    fun evaluate(windows: List<RecalibrationInput>): RecalibrationOutcome = select(windows).outcome

    fun select(windows: List<RecalibrationInput>): AdaptiveRecalibrationSelection {
        val evaluations = windows
            .sortedBy(RecalibrationInput::windowDays)
            .map { input -> RecalibrationEngine.evaluateWindow(input) }
        val selected = selectSmallestActionable(evaluations)
            ?: return AdaptiveRecalibrationSelection(
                outcome = RecalibrationOutcome.NoSuggestion("INSUFFICIENT_LOGGED_DAYS"),
                evaluations = evaluations,
                selectedWindowDays = null,
            )

        if (selected.outcome is RecalibrationOutcome.Suggestion &&
            evaluations.any { candidate -> candidate !== selected && materiallyConflicts(selected, candidate) }
        ) {
            return AdaptiveRecalibrationSelection(
                outcome = RecalibrationOutcome.NoSuggestion("CONFLICTING_TRENDS"),
                evaluations = evaluations,
                selectedWindowDays = null,
            )
        }

        return AdaptiveRecalibrationSelection(
            outcome = selected.outcome,
            evaluations = evaluations,
            selectedWindowDays = selected.windowDays,
        )
    }

    private fun selectSmallestActionable(
        evaluations: List<RecalibrationWindowEvaluation>,
    ): RecalibrationWindowEvaluation? {
        var latest: RecalibrationWindowEvaluation? = null
        for (evaluation in evaluations) {
            latest = evaluation
            if (evaluation.outcome is RecalibrationOutcome.Suggestion || evaluation.outcome.stopsExpansion()) {
                return evaluation
            }
        }
        return latest
    }

    private fun RecalibrationOutcome.stopsExpansion(): Boolean =
        this is RecalibrationOutcome.NoSuggestion && reason == "ADJUSTMENT_TOO_SMALL"

    private fun materiallyConflicts(
        selected: RecalibrationWindowEvaluation,
        candidate: RecalibrationWindowEvaluation,
    ): Boolean {
        val selectedSlope = selected.observedKgPerWeek ?: return false
        val candidateSlope = candidate.observedKgPerWeek ?: return false
        if (!selected.hasSufficientEvidence || !candidate.hasSufficientEvidence) return false
        val oppositeDirections = selectedSlope > flatZoneKgPerWeek && candidateSlope < flatZoneKgPerWeek.negate() ||
            selectedSlope < flatZoneKgPerWeek.negate() && candidateSlope > flatZoneKgPerWeek
        return oppositeDirections && selectedSlope.subtract(candidateSlope).abs() >= materialConflictKgPerWeek
    }
}
