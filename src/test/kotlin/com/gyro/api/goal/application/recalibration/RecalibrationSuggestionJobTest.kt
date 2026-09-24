package com.gyro.api.goal.application.recalibration

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.infrastructure.RecalibrationCandidateRepository
import com.gyro.api.notification.application.NotificationService
import com.gyro.api.subscription.application.EntitlementService
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementSource
import com.gyro.api.subscription.domain.EntitlementStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class RecalibrationSuggestionJobTest {
    private val recalibrationService = Mockito.mock(RecalibrationService::class.java)
    private val entitlementService = Mockito.mock(EntitlementService::class.java)
    private val notificationService = Mockito.mock(NotificationService::class.java)
    private val candidateRepository = Mockito.mock(RecalibrationCandidateRepository::class.java)
    private val timeProvider = Mockito.mock(TimeProvider::class.java)

    @Test
    fun `processes every keyset page and continues after one user fails`() {
        val users = (1..5).map { UUID.fromString("00000000-0000-0000-0000-00000000000$it") }
        Mockito.`when`(candidateRepository.findEligiblePage(null, 2)).thenReturn(users.subList(0, 2))
        Mockito.`when`(candidateRepository.findEligiblePage(users[1], 2)).thenReturn(users.subList(2, 4))
        Mockito.`when`(candidateRepository.findEligiblePage(users[3], 2)).thenReturn(users.subList(4, 5))
        Mockito.`when`(candidateRepository.findEligiblePage(users[4], 2)).thenReturn(emptyList())
        Mockito.`when`(recalibrationService.expireStalePending()).thenReturn(0)

        val entitlement = Entitlement(
            status = EntitlementStatus.ACTIVE,
            planKey = "ADVANCED",
            features = setOf(RecalibrationSuggestionJob.FEATURE_KEY),
            currentPeriodEnd = null,
            gracePeriodEnd = null,
            cancelAtPeriodEnd = false,
            supportReasonCode = null,
            source = EntitlementSource.SUBSCRIPTION,
        )
        users.forEach { userId ->
            Mockito.`when`(entitlementService.compute(userId)).thenReturn(entitlement)
        }
        Mockito.doThrow(IllegalStateException("one user failed"))
            .`when`(recalibrationService).evaluateAndSuggest(users[1])

        // A real trigger over mocked collaborators, so this still covers the
        // entitlement check and the notify path the job delegates to it.
        val job = RecalibrationSuggestionJob(
            recalibrationService = recalibrationService,
            trigger = RecalibrationTrigger(
                recalibrationService = recalibrationService,
                entitlementService = entitlementService,
                notificationService = notificationService,
                time = timeProvider,
                meterRegistry = SimpleMeterRegistry(),
            ),
            candidateRepository = candidateRepository,
            meterRegistry = SimpleMeterRegistry(),
            enabled = true,
            batchSize = 2,
        )

        job.run()

        users.forEach { userId ->
            Mockito.verify(recalibrationService).evaluateAndSuggest(userId)
        }
        Mockito.verify(candidateRepository).findEligiblePage(users[4], 2)
        Mockito.verifyNoInteractions(notificationService)
    }
}
