package com.gyro.api.subscription.application.trial

import com.gyro.api.auth.application.UserRegisteredEvent
import com.gyro.api.common.error.DomainException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Fast path for the signup trial. The in-process event is not durable: a
 * crash between commit and listener, or a transient grant failure, loses the
 * event -- [TrialReconciliationJob] sweeps those users up within its cadence,
 * and the failure counter below makes losses observable.
 */
@Component
class TrialSignupListener(
    private val trialService: TrialService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = meterRegistry.counter("gyro.subscription.trial.signup_grant_failures")

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUserRegistered(event: UserRegisteredEvent) {
        try {
            val redemption = trialService.grantSignupTrial(event.userId, event.primaryIdentifier)
            log.info(
                "event=trial_grant outcome=success source=SIGNUP userId={} expiresAt={}",
                event.userId,
                redemption.expiresAt,
            )
        } catch (exception: DomainException) {
            log.info(
                "event=trial_grant outcome=skipped source=SIGNUP userId={} reason={}",
                event.userId,
                exception.code,
            )
        } catch (exception: RuntimeException) {
            failures.increment()
            log.warn(
                "event=trial_grant outcome=failure source=SIGNUP userId={} reason={}",
                event.userId,
                exception::class.simpleName,
            )
        }
    }
}
