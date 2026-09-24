package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Evaluates recalibration the moment a weigh-in commits, so a suggestion arrives in
 * seconds rather than waiting for the next scheduled sweep.
 *
 * A new weigh-in is the only thing that can newly satisfy the weigh-in-count and span
 * gates, which makes it the one write worth reacting to. Diary writes are deliberately
 * ignored: they fire many times a day and coverage is rarely the binding gate.
 *
 * Synchronous, matching [com.gyro.api.subscription.application.trial.TrialSignupListener]:
 * a fast in-process path, everything caught, a counter for the failures, and a scheduled
 * job behind it as the durable backstop. Nothing is lost when an event is dropped —
 * [RecalibrationSuggestionJob] re-derives the same suggestion on its next run, which is
 * exactly the behaviour that existed before this listener.
 *
 * `fallbackExecution` stays off. Unlike a cache eviction, this persists a row and sends a
 * push, so it must not run against a transaction whose commit is unknown. **Consequence
 * for tests:** publishing the event directly will not fire this. It has to be exercised
 * through a real committing write.
 */
@Component
class WeighInRecalibrationListener(
    private val trigger: RecalibrationTrigger,
    meterRegistry: MeterRegistry,
    @Value("\${app.goal-recalibration.trigger-on-weigh-in-enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = meterRegistry.counter("gyro.goal.recalibration.weigh_in_trigger_failures")

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onWeightRecorded(event: UserDashboardDataChangedEvent) {
        if (event.source != UserDashboardChangeSource.WEIGHT) return
        if (!enabled) return

        runCatching { trigger.runFor(event.userId, RecalibrationTriggerSource.WEIGH_IN) }
            .onSuccess { suggestion ->
                if (suggestion != null) {
                    log.info("event=recalibration_weigh_in_trigger outcome=created userId={}", event.userId)
                }
            }
            .onFailure { failure ->
                // Never rethrow. AFTER_COMMIT cannot roll the weigh-in back, so throwing
                // would surface a 500 for a write that already succeeded — the user would
                // see their weigh-in fail while it sits in the database.
                failures.increment()
                log.warn(
                    "event=recalibration_weigh_in_trigger outcome=failure userId={} reason={}",
                    event.userId,
                    failure::class.simpleName,
                )
            }
    }
}
