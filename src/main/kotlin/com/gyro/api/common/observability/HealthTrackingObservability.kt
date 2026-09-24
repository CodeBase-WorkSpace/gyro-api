package com.gyro.api.common.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.*

@Component
class HealthTrackingObservability(
    private val meterRegistry: MeterRegistry,
) {
    fun goalSaved(
        userId: UUID,
        scheduleType: String?,
    ) {
        logger.atInfo()
            .addKeyValue("event", "nutrition_goal_saved")
            .addKeyValue("userId", userId)
            .addKeyValue("scheduleType", scheduleType)
            .log("Nutrition goal saved")
    }

    fun weightEntrySaved(
        userId: UUID,
        source: String,
    ) {
        logger.atInfo()
            .addKeyValue("event", "weight_entry_saved")
            .addKeyValue("userId", userId)
            .addKeyValue("source", source)
            .log("Weight entry saved")
    }

    fun weightEntriesBatchAccepted(
        userId: UUID,
        submittedCount: Int,
        acceptedCount: Int,
    ) {
        recordWeightBatchSize("submitted", submittedCount)
        recordWeightBatchSize("accepted", acceptedCount)

        logger.atInfo()
            .addKeyValue("event", "weight_entries_batch_accepted")
            .addKeyValue("userId", userId)
            .addKeyValue("submittedCount", submittedCount)
            .addKeyValue("acceptedCount", acceptedCount)
            .log("Weight entries batch accepted")
    }

    fun <T> observeProgressQuery(
        userId: UUID,
        domain: String,
        mode: String,
        period: String,
        rangeCount: Int,
        block: () -> T,
    ): T {
        logger.atInfo()
            .addKeyValue("event", "progress_range_requested")
            .addKeyValue("userId", userId)
            .addKeyValue("progressDomain", domain)
            .addKeyValue("requestMode", mode)
            .addKeyValue("period", period)
            .addKeyValue("rangeCount", rangeCount)
            .log("Progress range requested")

        val sample = Timer.start(meterRegistry)
        return try {
            val result = block()
            sample.stop(progressTimer(domain, mode, period, "success"))
            result
        } catch (error: Throwable) {
            sample.stop(progressTimer(domain, mode, period, "failure"))
            meterRegistry.counter(
                "gyro.health_tracking.progress.query.failures",
                "domain", domain,
                "mode", mode,
                "period", period,
            ).increment()
            throw error
        }
    }

    private fun recordWeightBatchSize(
        type: String,
        count: Int,
    ) {
        DistributionSummary.builder("gyro.health_tracking.weight_entries.batch.size")
            .description("Submitted and accepted row counts for batch weight imports.")
            .baseUnit("entries")
            .tags("type", type)
            .register(meterRegistry)
            .record(count.toDouble())
    }

    private fun progressTimer(
        domain: String,
        mode: String,
        period: String,
        outcome: String,
    ): Timer {
        return Timer.builder("gyro.health_tracking.progress.query.duration")
            .description("Progress query latency by bounded progress surface.")
            .publishPercentileHistogram()
            .tags(
                "domain", domain,
                "mode", mode,
                "period", period,
                "outcome", outcome,
            )
            .register(meterRegistry)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HealthTrackingObservability::class.java)

        fun rangeType(periods: Collection<Enum<*>>): String {
            val names = periods.map { it.name }.toSet()
            return when (names.size) {
                0 -> "NONE"
                1 -> names.first()
                else -> "MIXED"
            }
        }

        fun rangeType(period: Enum<*>): String = period.name

        fun rangeCount(from: LocalDate, to: LocalDate): Int {
            return to.toEpochDay().minus(from.toEpochDay()).plus(1).toInt()
        }
    }
}
