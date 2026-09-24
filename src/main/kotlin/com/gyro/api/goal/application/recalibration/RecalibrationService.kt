package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.goal.domain.RecalibrationDismissReason
import com.gyro.api.goal.domain.RecalibrationSuggestionStatus
import com.gyro.api.goal.infrastructure.NutritionPlanRepository
import com.gyro.api.goal.infrastructure.PlanRecalibrationSuggestionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class RecalibrationDecision(
    val suggestion: PlanRecalibrationSuggestion,
    /** True when accept applied the targets; false when the plan had changed underneath (SUPERSEDED). */
    val applied: Boolean,
)

@Service
class RecalibrationService(
    private val suggestionRepository: PlanRecalibrationSuggestionRepository,
    private val nutritionPlanRepository: NutritionPlanRepository,
    private val recalibrationDataLoader: RecalibrationDataLoader,
    private val timeProvider: TimeProvider,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun pendingFor(userId: UUID): PlanRecalibrationSuggestion? {
        val pending = suggestionRepository.findFirstByUserIdAndStatus(
            userId, RecalibrationSuggestionStatus.PENDING,
        ) ?: return null
        return if (pending.expiresAt.isAfter(timeProvider.now())) pending else null
    }

    /**
     * Evaluates bounded adaptive evidence windows and stores a PENDING suggestion
     * when the selected, conflict-free window finds a worthwhile correction.
     *
     * REQUIRES_NEW so this owns a transaction boundary of its own.
     *
     * Two reasons. The weigh-in listener calls this from an AFTER_COMMIT callback, where
     * the triggering transaction is still completing and a REQUIRED transaction would
     * join it and fail every write. And the suggestion must be committed before anything
     * downstream — notably notification creation — is attempted, so a failure there
     * cannot mark a shared transaction rollback-only and take the suggestion with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun evaluateAndSuggest(userId: UUID): PlanRecalibrationSuggestion? {
        val now = timeProvider.now()
        expirePendingForUser(userId, now)
        if (suggestionRepository.findFirstByUserIdAndStatus(userId, RecalibrationSuggestionStatus.PENDING) != null) {
            return null
        }
        val latest = suggestionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
        if (latest != null && latest.createdAt.isAfter(now.minus(MIN_INTERVAL))) {
            return null
        }

        val candidates = recalibrationDataLoader.loadForProducer(userId) ?: return null
        val data = candidates.windows.firstOrNull() ?: return null
        val plan = data.plan
        val selection = AdaptiveRecalibrationSelector.select(candidates.windows.map(RecalibrationData::input))
        val outcome = selection.outcome
        logEvaluation(userId, requireNotNull(plan.id), candidates.boundary, selection)

        val suggestion = when (outcome) {
            is RecalibrationOutcome.NoSuggestion -> {
                return null
            }
            is RecalibrationOutcome.Suggestion -> outcome
        }

        return try {
            suggestionRepository.save(
                PlanRecalibrationSuggestion(
                    userId = userId,
                    nutritionPlanId = requireNotNull(plan.id),
                    suggestedCalories = suggestion.suggestedCalories,
                    suggestedProtein = suggestion.suggestedProtein,
                    suggestedCarbs = suggestion.suggestedCarbs,
                    suggestedFat = suggestion.suggestedFat,
                    previousCalories = plan.calories,
                    previousProtein = plan.protein,
                    previousCarbs = plan.carbs,
                    previousFat = plan.fat,
                    basis = suggestion.basis,
                    createdAt = now,
                    expiresAt = now.plus(SUGGESTION_TTL),
                ),
            ).also {
                dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.RECALIBRATION)
            }
        } catch (exception: DataIntegrityViolationException) {
            // A concurrent run won the pending-unique index; that run's
            // suggestion stands.
            null
        }
    }

    @Transactional
    fun accept(userId: UUID, suggestionId: UUID): RecalibrationDecision {
        val suggestion = pendingOwnedBy(userId, suggestionId)
        val now = timeProvider.now()
        val plan = nutritionPlanRepository.findById(suggestion.nutritionPlanId).orElse(null)

        val planUnchanged = plan != null &&
            plan.calories.compareTo(suggestion.previousCalories) == 0 &&
            plan.protein.compareTo(suggestion.previousProtein) == 0 &&
            plan.carbs.compareTo(suggestion.previousCarbs) == 0 &&
            plan.fat.compareTo(suggestion.previousFat) == 0

        if (!planUnchanged) {
            suggestion.status = RecalibrationSuggestionStatus.SUPERSEDED
            suggestion.decidedAt = now
            suggestionRepository.save(suggestion)
            dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.RECALIBRATION)
            return RecalibrationDecision(suggestion, applied = false)
        }

        plan!!.calories = suggestion.suggestedCalories
        plan.protein = suggestion.suggestedProtein
        plan.carbs = suggestion.suggestedCarbs
        plan.fat = suggestion.suggestedFat
        nutritionPlanRepository.save(plan)

        suggestion.status = RecalibrationSuggestionStatus.ACCEPTED
        suggestion.decidedAt = now
        suggestionRepository.save(suggestion)
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.RECALIBRATION)
        log.info("event=recalibration outcome=accepted userId={} suggestionId={}", userId, suggestionId)
        return RecalibrationDecision(suggestion, applied = true)
    }

    @Transactional
    fun dismiss(
        userId: UUID,
        suggestionId: UUID,
        reason: RecalibrationDismissReason? = null,
    ): PlanRecalibrationSuggestion {
        val suggestion = pendingOwnedBy(userId, suggestionId)
        suggestion.status = RecalibrationSuggestionStatus.DISMISSED
        suggestion.decidedAt = timeProvider.now()
        suggestion.dismissReason = reason
        return suggestionRepository.save(suggestion).also {
            dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.RECALIBRATION)
        }
    }

    @Transactional
    fun expireStalePending(): Int {
        val now = timeProvider.now()
        val stale = suggestionRepository.findAllExpiredForUpdate(
            RecalibrationSuggestionStatus.PENDING, now,
        )
        expire(stale, now)
        return stale.size
    }

    private fun expirePendingForUser(userId: UUID, now: Instant) {
        val stale = suggestionRepository.findAllExpiredForUserForUpdate(
            userId,
            RecalibrationSuggestionStatus.PENDING,
            now,
        )
        expire(stale, now)
    }

    private fun expire(stale: List<PlanRecalibrationSuggestion>, now: Instant) {
        stale.forEach {
            it.status = RecalibrationSuggestionStatus.EXPIRED
            it.decidedAt = now
        }
        suggestionRepository.saveAll(stale)
        stale.map { it.userId }.distinct().forEach { userId ->
            dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.RECALIBRATION)
        }
    }

    private fun pendingOwnedBy(userId: UUID, suggestionId: UUID): PlanRecalibrationSuggestion {
        val suggestion = suggestionRepository.findByIdAndUserIdForUpdate(suggestionId, userId)
            ?: throw ResourceNotFoundException("Recalibration suggestion")
        if (
            suggestion.status != RecalibrationSuggestionStatus.PENDING ||
            !suggestion.expiresAt.isAfter(timeProvider.now())
        ) {
            throw ResourceNotFoundException("Recalibration suggestion")
        }
        return suggestion
    }

    private fun logEvaluation(
        userId: UUID,
        planId: UUID,
        boundary: RecalibrationEvidenceBoundary,
        selection: AdaptiveRecalibrationSelection,
    ) {
        val evaluatedWindows = selection.evaluations.joinToString(",") { evaluation ->
            val slope = evaluation.observedKgPerWeek?.setScale(3, RoundingMode.HALF_UP)
            val result = when (val evaluationOutcome = evaluation.outcome) {
                is RecalibrationOutcome.Suggestion -> "SUGGESTION"
                is RecalibrationOutcome.NoSuggestion -> evaluationOutcome.reason
            }
            "${evaluation.windowDays}:$result:$slope"
        }
        val outcome = selection.outcome
        log.info(
            "event=recalibration_evaluation userId={} planId={} boundaryDate={} boundarySource={} evaluatedWindows={} selectedWindow={} outcome={} reason={}",
            userId,
            planId,
            boundary.date,
            boundary.source,
            evaluatedWindows,
            selection.selectedWindowDays,
            if (outcome is RecalibrationOutcome.Suggestion) "suggestion" else "skipped",
            (outcome as? RecalibrationOutcome.NoSuggestion)?.reason,
        )
    }

    companion object {
        val MIN_INTERVAL: Duration = Duration.ofDays(7)
        val SUGGESTION_TTL: Duration = Duration.ofDays(14)
    }
}
