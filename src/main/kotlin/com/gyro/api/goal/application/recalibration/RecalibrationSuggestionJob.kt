package com.gyro.api.goal.application.recalibration

import com.gyro.api.goal.infrastructure.RecalibrationCandidateRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Daily sweep: for every user who plausibly holds goal_recalibration (batched
 * SQL over active subscriptions/grants), evaluate the last two weeks and store at most
 * one PENDING suggestion. Idempotent without a distributed lock: the partial unique
 * index on (user_id) where status = PENDING plus the per-user minimum interval make
 * duplicate runs no-ops.
 *
 * Also the durable backstop for [WeighInRecalibrationListener]: it catches users whose
 * eligibility changed without a weigh-in, and anyone whose in-process event was lost.
 */
@Component
class RecalibrationSuggestionJob(
    private val recalibrationService: RecalibrationService,
    private val trigger: RecalibrationTrigger,
    private val candidateRepository: RecalibrationCandidateRepository,
    meterRegistry: MeterRegistry,
    @Value("\${app.goal-recalibration.jobs-enabled:true}") private val enabled: Boolean,
    @Value("\${app.goal-recalibration.batch-size:200}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val runsCounter = meterRegistry.counter("gyro.goal.recalibration.job_runs")

    @Scheduled(cron = "\${app.goal-recalibration.cron:0 30 5 * * *}")
    fun run() {
        if (!enabled) return
        runsCounter.increment()

        val expired = recalibrationService.expireStalePending()
        if (expired > 0) log.info("event=recalibration_job stage=expired_pending count={}", expired)

        var candidateCount = 0
        var created = 0
        var cursor: UUID? = null

        while (true) {
            val candidates = candidateRepository.findEligiblePage(cursor, batchSize)
            if (candidates.isEmpty()) break

            candidates.forEach { userId ->
                runCatching {
                    trigger.runFor(userId, RecalibrationTriggerSource.SCHEDULED) ?: return@forEach
                    created += 1
                }.onFailure { failure ->
                    log.warn(
                        "event=recalibration_job outcome=user_failure userId={} reason={}",
                        userId,
                        failure::class.simpleName,
                    )
                }
            }
            candidateCount += candidates.size
            cursor = candidates.last()
        }
        log.info("event=recalibration_job outcome=completed candidates={} created={}", candidateCount, created)
    }

    companion object {
        /** Kept for callers that reference the job's feature key; owned by the trigger. */
        const val FEATURE_KEY = RecalibrationTrigger.FEATURE_KEY
    }
}
