package com.gyro.api.subscription.application

import com.gyro.api.auth.infrastructure.UserRepository
import com.gyro.api.common.error.*
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.domain.ManualGrant
import com.gyro.api.subscription.domain.ManualGrantReason
import com.gyro.api.subscription.domain.SubscriptionPlan
import com.gyro.api.subscription.infrastructure.ManualGrantRepository
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class ManualGrantServiceTest {

    private lateinit var manualGrantRepository: ManualGrantRepository
    private lateinit var lifecycleService: SubscriptionLifecycleService
    private lateinit var timeProvider: TimeProvider
    private lateinit var planRepository: SubscriptionPlanRepository
    private lateinit var userRepository: UserRepository
    private lateinit var promotionService: PromotionService
    private lateinit var manualGrantService: ManualGrantService

    private val now = Instant.parse("2026-07-08T12:00:00Z")

    @BeforeEach
    fun setUp() {
        manualGrantRepository = Mockito.mock(ManualGrantRepository::class.java)
        lifecycleService = Mockito.mock(SubscriptionLifecycleService::class.java)
        timeProvider = Mockito.mock(TimeProvider::class.java)
        planRepository = Mockito.mock(SubscriptionPlanRepository::class.java)
        userRepository = Mockito.mock(UserRepository::class.java)
        promotionService = Mockito.mock(PromotionService::class.java)

        Mockito.`when`(timeProvider.now()).thenReturn(now)

        manualGrantService = ManualGrantService(
            manualGrantRepository = manualGrantRepository,
            lifecycleService = lifecycleService,
            planRepository = planRepository,
            userRepository = userRepository,
            timeProvider = timeProvider,
            promotionService = promotionService,
        )
    }

    @Nested
    inner class `createGrant validation` {

        @Test
        fun `rejects grant when reason note is blank`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            assertThrows(GrantReasonRequiredException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = 30,
                    reason = ManualGrantReason.CUSTOM,
                    grantedBy = adminId,
                    reasonNote = "",
                )
            }
        }

        @Test
        fun `rejects grant when reason note is null`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            assertThrows(GrantReasonRequiredException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = 30,
                    reason = ManualGrantReason.CUSTOM,
                    grantedBy = adminId,
                    reasonNote = null,
                )
            }
        }

        @Test
        fun `rejects grant when user does not exist`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            Mockito.`when`(userRepository.existsById(userId)).thenReturn(false)

            assertThrows(com.gyro.api.common.error.DeletedUserException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = 30,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = adminId,
                    reasonNote = "test",
                )
            }
        }

        @Test
        fun `rejects grant when plan is inactive`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            val plan = SubscriptionPlan(id = 1L, code = "ADVANCED", name = "Advanced", free = false, active = false)

            Mockito.`when`(userRepository.existsById(userId)).thenReturn(true)
            Mockito.`when`(planRepository.findById(1L)).thenReturn(Optional.of(plan))

            assertThrows(InactivePlanException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = 30,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = adminId,
                    reasonNote = "test",
                )
            }
        }

        @Test
        fun `rejects grant when plan does not exist`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            Mockito.`when`(userRepository.existsById(userId)).thenReturn(true)
            Mockito.`when`(planRepository.findById(999L)).thenReturn(Optional.empty())

            assertThrows(ResourceNotFoundException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 999L,
                    durationDays = 30,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = adminId,
                    reasonNote = "test",
                )
            }
        }

        @Test
        fun `rejects grant when expiry is in the past`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            val plan = SubscriptionPlan(id = 1L, code = "ADVANCED", name = "Advanced", free = false, active = true)

            Mockito.`when`(userRepository.existsById(userId)).thenReturn(true)
            Mockito.`when`(planRepository.findById(1L)).thenReturn(Optional.of(plan))

            assertThrows(GrantExpiryInPastException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = -5,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = adminId,
                    reasonNote = "test",
                )
            }
        }

        @Test
        fun `rejects grant when duration is zero`() {
            val userId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            val plan = SubscriptionPlan(id = 1L, code = "ADVANCED", name = "Advanced", free = false, active = true)

            Mockito.`when`(userRepository.existsById(userId)).thenReturn(true)
            Mockito.`when`(planRepository.findById(1L)).thenReturn(Optional.of(plan))

            assertThrows(GrantExpiryInPastException::class.java) {
                manualGrantService.createGrant(
                    userId = userId,
                    planId = 1L,
                    durationDays = 0,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = adminId,
                    reasonNote = "zero duration",
                )
            }
        }
    }

    @Nested
    inner class `extendGrant` {

        @Test
        fun `extends active grant duration`() {
            val grantId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            val existingGrant = ManualGrant(
                id = grantId,
                userId = UUID.randomUUID(),
                planId = 1L,
                durationDays = 30,
                expiresAt = now.plus(15, ChronoUnit.DAYS),
                reason = ManualGrantReason.TESTER_ACCESS,
                grantedBy = UUID.randomUUID(),
            )
            val extendedGrant = ManualGrant(
                id = existingGrant.id,
                userId = existingGrant.userId,
                planId = existingGrant.planId,
                durationDays = 60,
                periodStart = existingGrant.periodStart,
                expiresAt = now.plus(45, ChronoUnit.DAYS),
                reason = existingGrant.reason,
                reasonNote = existingGrant.reasonNote,
                grantedBy = existingGrant.grantedBy,
                revokedAt = existingGrant.revokedAt,
                revokedBy = existingGrant.revokedBy,
                revokeReason = existingGrant.revokeReason,
                createdAt = existingGrant.createdAt,
            )

            Mockito.`when`(
                lifecycleService.extendGrant(
                    grantId = grantId,
                    additionalDays = 30,
                    adminId = adminId,
                    reason = "extending access",
                ),
            ).thenReturn(extendedGrant)

            val result = manualGrantService.extendGrant(
                grantId = grantId,
                additionalDays = 30,
                adminId = adminId,
                reason = "extending access",
            )

            assertNotNull(result)
            assertEquals(60, result.durationDays)
            Mockito.verify(lifecycleService).extendGrant(grantId, 30, adminId, "extending access")
        }

        @Test
        fun `rejects extension with non-positive days`() {
            val grantId = UUID.randomUUID()

            assertThrows(GrantExpiryInPastException::class.java) {
                manualGrantService.extendGrant(
                    grantId = grantId,
                    additionalDays = 0,
                    adminId = UUID.randomUUID(),
                    reason = "extending",
                )
            }
            Mockito.verifyNoInteractions(lifecycleService)
        }

        @Test
        fun `rejects extension when grant not found`() {
            val unknownId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            Mockito.`when`(
                lifecycleService.extendGrant(
                    grantId = unknownId,
                    additionalDays = 30,
                    adminId = adminId,
                    reason = "extending",
                ),
            ).thenThrow(ManualGrantNotFoundException())

            assertThrows(ManualGrantNotFoundException::class.java) {
                manualGrantService.extendGrant(
                    grantId = unknownId,
                    additionalDays = 30,
                    adminId = adminId,
                    reason = "extending",
                )
            }
        }
    }

    @Nested
    inner class `revokeGrant` {

        @Test
        fun `revokes active grant`() {
            val grantId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            manualGrantService.revokeGrant(grantId, adminId, "no longer needed")

            Mockito.verify(lifecycleService).revokeGrant(grantId, adminId, "no longer needed")
        }

        @Test
        fun `rejects revoke with blank reason`() {
            val grantId = UUID.randomUUID()
            val adminId = UUID.randomUUID()

            assertThrows(GrantReasonRequiredException::class.java) {
                manualGrantService.revokeGrant(grantId, adminId, " ")
            }

            Mockito.verifyNoInteractions(lifecycleService)
        }

        @Test
        fun `revoking non-existent grant throws`() {
            val unknownId = UUID.randomUUID()
            val adminId = UUID.randomUUID()
            Mockito.doThrow(ManualGrantNotFoundException())
                .`when`(lifecycleService).revokeGrant(unknownId, adminId, "reason")

            assertThrows(ManualGrantNotFoundException::class.java) {
                manualGrantService.revokeGrant(unknownId, adminId, "reason")
            }
        }
    }

    @Nested
    inner class `expireGrants` {

        @Test
        fun `expires grants through lifecycle`() {
            val grantId = UUID.randomUUID()
            Mockito.`when`(manualGrantRepository.findExpiredGrantIds(now, PageRequest.of(0, 100)))
                .thenReturn(listOf(grantId))

            manualGrantService.expireGrants()

            Mockito.verify(lifecycleService).expireGrant(grantId)
        }

        @Test
        fun `does nothing when no grants are expired`() {
            Mockito.`when`(manualGrantRepository.findExpiredGrantIds(now, PageRequest.of(0, 100)))
                .thenReturn(emptyList())

            manualGrantService.expireGrants()

            Mockito.verifyNoInteractions(lifecycleService)
        }
    }

    @Nested
    inner class `findActiveGrants` {

        @Test
        fun `returns active grants for user`() {
            val userId = UUID.randomUUID()
            val grants = listOf(
                ManualGrant(
                    id = UUID.randomUUID(),
                    userId = userId,
                    planId = 1L,
                    reason = ManualGrantReason.TESTER_ACCESS,
                    grantedBy = UUID.randomUUID(),
                ),
            )

            Mockito.`when`(manualGrantRepository.findActiveByUserId(userId, now)).thenReturn(grants)

            val result = manualGrantService.findActiveGrants(userId)

            assertEquals(1, result.size)
            assertEquals(userId, result[0].userId)
        }
    }
}
