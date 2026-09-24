package com.gyro.api.goal.application.coach

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.diary.application.CoachInsightImpressionRecorder
import com.gyro.api.diary.application.CoachInsightImpressionMetrics
import com.gyro.api.diary.application.CoachInsightImpressionRecordOutcome
import com.gyro.api.diary.application.CoachObservationFingerprintPolicy
import com.gyro.api.user.application.UserTimezoneResolver
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class CoachInsightImpressionService(
    private val recorder: CoachInsightImpressionRecorder,
    private val userTimezoneResolver: UserTimezoneResolver,
    private val timeProvider: TimeProvider,
    private val coachStateCache: NutritionCoachStateCache,
    private val impressionMetrics: CoachInsightImpressionMetrics,
    private val issuedImpressionRegistry: CoachIssuedImpressionRegistry,
    private val rateLimits: CoachInsightImpressionRateLimitService,
) {
    fun recordVisible(
        userId: UUID,
        impressionId: String,
    ) {
        val parsedKind = CoachObservationFingerprintPolicy.recordableKind(impressionId)
        if (parsedKind == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Coach impression identity is not recognized.",
            )
        }
        rateLimits.check(userId)
        val verification = issuedImpressionRegistry.verify(userId, impressionId)
        val issuedKind = when (verification.outcome) {
            CoachIssuedImpressionVerificationOutcome.ISSUED -> verification.kind
            CoachIssuedImpressionVerificationOutcome.NOT_ISSUED -> {
                impressionMetrics.impressionRejected(
                    CoachInsightImpressionMetrics.REJECTION_NOT_ISSUED,
                )
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Coach impression identity was not issued for this state.",
                )
            }
            CoachIssuedImpressionVerificationOutcome.ERROR -> throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Coach impression identity could not be verified.",
            )
        }
        if (issuedKind == null || issuedKind != parsedKind) {
            impressionMetrics.impressionRejected(
                CoachInsightImpressionMetrics.REJECTION_NOT_ISSUED,
            )
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Coach impression identity was not issued for this state.",
            )
        }
        val zone = userTimezoneResolver.resolve(userId)
        when (recorder.recordSafely(
            userId = userId,
            keys = listOf(impressionId),
            shownOn = timeProvider.today(zone),
        )) {
            CoachInsightImpressionRecordOutcome.INSERTED -> {
                impressionMetrics.impressionAccepted(issuedKind)
                // Ranking is part of the cached Coach response. Same-day pinning keeps
                // the visible observation stable after this invalidation.
                coachStateCache.invalidate(userId)
            }
            CoachInsightImpressionRecordOutcome.ALREADY_RECORDED -> {
                impressionMetrics.impressionDuplicate()
            }
            CoachInsightImpressionRecordOutcome.FAILED -> throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Coach impression could not be recorded.",
            )
        }
    }
}
