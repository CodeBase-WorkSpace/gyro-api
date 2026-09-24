package com.gyro.api.subscription.web

import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.EntitlementGateService
import com.gyro.api.subscription.application.ManualGrantService
import com.gyro.api.subscription.domain.ManualGrant
import com.gyro.api.subscription.domain.ManualGrantReason
import com.gyro.api.subscription.infrastructure.SubscriptionPlanRepository
import com.gyro.api.subscription.infrastructure.PromotionRedemptionRepository
import com.gyro.api.subscription.infrastructure.PromotionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.util.*

class ManualGrantControllerTest {

    @Test
    fun `create grant parses the JWT user id request attribute`() {
        val manualGrantService = Mockito.mock(ManualGrantService::class.java)
        val controller = ManualGrantController(
            manualGrantService = manualGrantService,
            subscriptionPlanRepository = Mockito.mock(SubscriptionPlanRepository::class.java),
            cachedEntitlementService = Mockito.mock(CachedEntitlementService::class.java),
            entitlementGateService = Mockito.mock(EntitlementGateService::class.java),
            accountAuditService = Mockito.mock(AccountAuditService::class.java),
            promotionRedemptionRepository = Mockito.mock(PromotionRedemptionRepository::class.java),
            promotionRepository = Mockito.mock(PromotionRepository::class.java),
        )
        val adminId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val grant = ManualGrant(
            id = UUID.randomUUID(),
            userId = userId,
            planId = 1L,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            reasonNote = "Approved tester access.",
            grantedBy = adminId,
            createdAt = Instant.parse("2026-07-11T12:00:00Z"),
        )
        val servletRequest = MockHttpServletRequest().apply {
            setAttribute(USER_ID_ATTRIBUTE, adminId.toString())
        }

        Mockito.`when`(
            manualGrantService.createGrant(
                userId = userId,
                planId = 1L,
                durationDays = 30,
                reason = ManualGrantReason.TESTER_ACCESS,
                grantedBy = adminId,
                reasonNote = "Approved tester access.",
            ),
        ).thenReturn(grant)

        val response = controller.createGrant(
            request = CreateManualGrantRequest(
                userId = userId,
                planId = 1L,
                durationDays = 30,
                reason = ManualGrantReason.TESTER_ACCESS,
                reasonNote = "Approved tester access.",
            ),
            servletRequest = servletRequest,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(grant.id, response.body!!.id)
        Mockito.verify(manualGrantService).createGrant(
            userId = userId,
            planId = 1L,
            durationDays = 30,
            reason = ManualGrantReason.TESTER_ACCESS,
            grantedBy = adminId,
            reasonNote = "Approved tester access.",
        )
    }
}
