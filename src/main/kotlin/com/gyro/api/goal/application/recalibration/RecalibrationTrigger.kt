package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.notification.domain.NotificationRequest
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.subscription.application.EntitlementService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** Which path asked for the evaluation. Tags the suggestion counter. */
enum class RecalibrationTriggerSource(val tag: String) {
    SCHEDULED("scheduled"),
    WEIGH_IN("weigh_in"),
}

/**
 * The single path from "this user might be due" to "a suggestion exists and they were
 * told about it".
 *
 * Both the scheduled sweep and the weigh-in listener go through here. Calling
 * [RecalibrationService.evaluateAndSuggest] directly would skip three things that live
 * outside it: the entitlement check, the notification, and the counter. A caller that
 * forgot the first would create suggestions for free users; one that forgot the second
 * would create a suggestion nobody is told about, which the cron cannot repair because
 * its next run returns null while a PENDING row exists.
 *
 * **This method is deliberately not transactional.** The suggestion commits inside
 * `evaluateAndSuggest` (REQUIRES_NEW) and everything after it runs on its own boundary,
 * so a notification failure cannot mark a shared transaction rollback-only and destroy
 * an otherwise valid suggestion on commit.
 */
@Component
class RecalibrationTrigger(
    private val recalibrationService: RecalibrationService,
    private val entitlementService: EntitlementService,
    private val notificationService: NotificationService,
    private val time: TimeProvider,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns the created suggestion, or null when the user was not due one. */
    fun runFor(userId: UUID, source: RecalibrationTriggerSource): PlanRecalibrationSuggestion? {
        val entitlement = entitlementService.compute(userId)
        if (FEATURE_KEY !in entitlement.features) return null

        // Committed by the time this returns.
        val suggestion = recalibrationService.evaluateAndSuggest(userId) ?: return null

        // Counted here rather than at the call sites, so every creation path is counted
        // exactly once. The scheduled sweep used to own this and the weigh-in path would
        // otherwise be invisible to the metric named as the rollout canary.
        meterRegistry.counter(
            "gyro.goal.recalibration.suggestions",
            "source", source.tag,
            "confidence", suggestion.basis["confidence"]?.toString() ?: "unknown",
        ).increment()

        notifySuggestion(userId, suggestion.id.toString())
        return suggestion
    }

    /**
     * Notification failures are swallowed: the suggestion is already committed and is
     * visible in the app without a push, so losing the push is strictly better than
     * losing the suggestion.
     *
     * Duplicate sends are impossible by construction rather than by guard — the key is
     * derived from the suggestion id, uniqueness is enforced on (source_type,
     * idempotency_key), and the partial unique index permits only one live suggestion per
     * user, so two callers cannot produce two ids for one suggestion.
     */
    private fun notifySuggestion(userId: UUID, suggestionId: String) {
        val now = time.now()
        runCatching {
            notificationService.create(
                NotificationRequest(
                    recipientUserId = userId,
                    type = NotificationType.RECALIBRATION_SUGGESTION,
                    templateData = emptyMap(),
                    occurredAt = now,
                    scheduledAt = now,
                    expiresAt = now.plus(Duration.ofHours(48)),
                    idempotencyKey = "recalibration:$suggestionId",
                    requestId = "recalibration-$suggestionId",
                    sourceType = "RECALIBRATION_SUGGESTION",
                    sourceReference = suggestionId,
                ),
            )
        }.onFailure {
            log.warn("event=recalibration_notify outcome=failure userId={} reason={}", userId, it::class.simpleName)
        }
    }

    companion object {
        const val FEATURE_KEY = "goal_recalibration"
    }
}
