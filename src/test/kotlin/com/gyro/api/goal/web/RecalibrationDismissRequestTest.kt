package com.gyro.api.goal.web

import com.gyro.api.common.error.GlobalExceptionHandler
import com.gyro.api.goal.application.recalibration.RecalibrationService
import com.gyro.api.goal.domain.PlanRecalibrationSuggestion
import com.gyro.api.goal.domain.RecalibrationDismissReason
import com.gyro.api.subscription.application.EntitlementGateService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecalibrationDismissRequestTest {
    private val objectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Test
    fun `known dismiss reason is accepted`() {
        val request = objectMapper.readValue<DismissRecalibrationRequest>(
            """{"reason":"DATA_IS_WRONG"}""",
        )

        assertEquals(RecalibrationDismissReason.DATA_IS_WRONG, request.reason)
    }

    @Test
    fun `empty object and explicit null leave the reason absent`() {
        assertNull(
            objectMapper.readValue<DismissRecalibrationRequest>("{}").reason,
        )
        assertNull(
            objectMapper.readValue<DismissRecalibrationRequest>(
                """{"reason":null}""",
            ).reason,
        )
    }

    @Test
    fun `missing request body dismisses without a reason`() {
        val userId = UUID.randomUUID()
        val suggestionId = UUID.randomUUID()
        val service = Mockito.mock(RecalibrationService::class.java)
        val entitlement = Mockito.mock(EntitlementGateService::class.java)
        Mockito.`when`(service.dismiss(userId, suggestionId, null))
            .thenReturn(suggestion(suggestionId, userId))

        RecalibrationController(service, entitlement)
            .dismiss(userId.toString(), suggestionId, null)

        Mockito.verify(service).dismiss(userId, suggestionId, null)
    }

    @Test
    fun `unknown dismiss reason is rejected`() {
        assertThrows<RuntimeException> {
            objectMapper.readValue<DismissRecalibrationRequest>(
                """{"reason":"SOMETHING_ELSE"}""",
            )
        }
    }

    @Test
    fun `unknown dismiss reason uses the standard validation response`() {
        val controller = RecalibrationController(
            Mockito.mock(RecalibrationService::class.java),
            Mockito.mock(EntitlementGateService::class.java),
        )
        val mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .addPlaceholderValue("app.api.base-path", "/api/v1")
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mockMvc.perform(
            post("/api/v1/goals/recalibration/${java.util.UUID.randomUUID()}/dismiss")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"SOMETHING_ELSE"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Request validation failed."))
    }

    private fun suggestion(id: UUID, userId: UUID) = PlanRecalibrationSuggestion(
        id = id,
        userId = userId,
        nutritionPlanId = UUID.randomUUID(),
        suggestedCalories = BigDecimal("1800"),
        suggestedProtein = BigDecimal("90"),
        suggestedCarbs = BigDecimal("180"),
        suggestedFat = BigDecimal("63"),
        previousCalories = BigDecimal("2000"),
        previousProtein = BigDecimal("100"),
        previousCarbs = BigDecimal("200"),
        previousFat = BigDecimal("70"),
        createdAt = Instant.parse("2026-07-29T10:00:00Z"),
        expiresAt = Instant.parse("2026-08-05T10:00:00Z"),
    )
}
