package com.gyro.api.progress

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.*
import java.time.Instant
import java.util.*
import java.util.logging.Logger
import javax.sql.DataSource

@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    ProgressControllerIntegrationTest.QueryCountingDataSourceConfiguration::class,
)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class ProgressControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `get nutrition progress returns a Saturday-start week with missing empty days and logged zero calorie entries`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-week-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "nutrition-progress-week-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val firstDayId = seedDiaryDay(userId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = firstDayId,
            userId = userId,
            date = "2026-06-21",
            calories = "500.00",
            protein = "30.000",
            carbs = "55.000",
            fat = "12.000",
            fiber = "8.000",
            sugar = "10.000",
            sodium = "450.000",
        )
        seedDiaryDay(userId, "2026-06-22")
        val zeroEntryDayId = seedDiaryDay(userId, "2026-06-23")
        seedManualNutritionEntry(
            dayId = zeroEntryDayId,
            userId = userId,
            date = "2026-06-23",
        )
        val otherDayId = seedDiaryDay(otherUserId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = otherDayId,
            userId = otherUserId,
            date = "2026-06-21",
            calories = "900.00",
            protein = "90.000",
        )

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "WEEK")
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("WEEK"))
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.points.length()").value(7))
            .andExpect(jsonPath("$.points[0].date").value("2026-06-20"))
            .andExpect(jsonPath("$.points[0].logged").value(false))
            .andExpect(jsonPath("$.points[0].goal").isEmpty)
            .andExpect(jsonPath("$.points[1].date").value("2026-06-21"))
            .andExpect(jsonPath("$.points[1].logged").value(true))
            .andExpect(jsonPath("$.points[1].totals.calories").value(500.00))
            .andExpect(jsonPath("$.points[1].totals.protein").value(30.000))
            .andExpect(jsonPath("$.points[2].date").value("2026-06-22"))
            .andExpect(jsonPath("$.points[2].logged").value(false))
            .andExpect(jsonPath("$.points[2].totals.calories").value(0.00))
            .andExpect(jsonPath("$.points[3].date").value("2026-06-23"))
            .andExpect(jsonPath("$.points[3].logged").value(true))
            .andExpect(jsonPath("$.points[3].totals.calories").value(0.00))
            .andExpect(jsonPath("$.summary.totals.calories").value(500.00))
            .andExpect(jsonPath("$.summary.totals.protein").value(30.000))
            .andExpect(jsonPath("$.summary.averagePerDay.calories").value(71.43))
            .andExpect(jsonPath("$.summary.averagePerLoggedDay.calories").value(250.00))
            .andExpect(jsonPath("$.summary.minDailyTotals.calories").value(0.00))
            .andExpect(jsonPath("$.summary.maxDailyTotals.calories").value(500.00))
            .andExpect(jsonPath("$.summary.loggedDayCount").value(2))
            .andExpect(jsonPath("$.summary.missingDayCount").value(5))
    }

    @Test
    fun `get weekly progress aliases nutrition week preset with goal adherence`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-06-01")
        seedNutritionPlan(
            userId = userId,
            startDate = "2026-06-22",
            calories = "4000.00",
            protein = "240.000",
            carbs = "440.000",
            fat = "130.000",
            fiber = "56.000",
            targetWeight = "76.500",
            targetWeightUnit = "KG",
        )
        val dayId = seedDiaryDay(userId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = "2026-06-21",
            calories = "1900.00",
            protein = "120.000",
            carbs = "210.000",
            fat = "60.000",
            fiber = "30.000",
        )
        seedWeightEntry(userId, "2026-06-20", "79.000")
        seedWeightEntry(userId, "2026-06-26", "78.400")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.period").doesNotExist())
            .andExpect(jsonPath("$.points").doesNotExist())
            .andExpect(jsonPath("$.summary").doesNotExist())
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(1))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(6))
            .andExpect(jsonPath("$.nutrition.calories.total").value(1900.00))
            .andExpect(jsonPath("$.nutrition.calories.average").value(1900.00))
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").value(95.00))
            .andExpect(jsonPath("$.nutrition.macros.protein.total").value(120.000))
            .andExpect(jsonPath("$.nutrition.macros.protein.average").value(120.000))
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").value(100.000))
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.total").value(30.000))
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.goalAveragePercent").value(107.143))
            .andExpect(jsonPath("$.weight.configured").value(true))
            .andExpect(jsonPath("$.weight.startWeightKg").value(79.000))
            .andExpect(jsonPath("$.weight.endWeightKg").value(78.400))
            .andExpect(jsonPath("$.weight.absoluteChangeKg").value(-0.600))
            .andExpect(jsonPath("$.weight.trendDirection").value("DOWN"))
            .andExpect(jsonPath("$.weight.targetWeightKg").value(76.500))
            .andExpect(jsonPath("$.warnings.length()").value(0))
    }

    @Test
    fun `get weekly progress accepts documented explicit week range`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-range-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val dayId = seedDiaryDay(userId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = "2026-06-21",
            calories = "500.00",
            protein = "30.000",
            carbs = "55.000",
            fat = "12.000",
            fiber = "8.000",
            sodium = "450.000",
        )

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-20")
                .param("to", "2026-06-26")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.nutrition.calories.total").value(500.00))
            .andExpect(jsonPath("$.nutrition.macros.protein.total").value(30.000))
            .andExpect(jsonPath("$.nutrition.micronutrients.sodium.total").value(450.000))
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(1))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(6))
            .andExpect(jsonPath("$.weight").isEmpty)
    }

    @Test
    fun `get weekly progress preserves nutrition week range validation`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("anchor", "2026-06-25")
                .param("from", "2026-06-20")
                .param("to", "2026-06-26")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("month", "2026-06")
            )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-20")
                .param("to", "2026-06-27")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `get weekly progress returns explicit empty state when no diary goal or weight exists`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-empty-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(0))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(7))
            .andExpect(jsonPath("$.nutrition.calories.total").value(0.00))
            .andExpect(jsonPath("$.nutrition.calories.average").isEmpty)
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.nutrition.macros.protein.total").value(0.000))
            .andExpect(jsonPath("$.nutrition.macros.protein.average").isEmpty)
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.total").value(0.000))
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.average").isEmpty)
            .andExpect(jsonPath("$.weight").isEmpty)
            .andExpect(jsonPath("$.warnings.length()").value(0))
    }

    @Test
    fun `get weekly progress does not aggregate another user's goals diary or weight`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "weekly-progress-owner-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedProfile(otherUserId, "Asia/Tehran")
        seedNutritionPlan(otherUserId, "2026-06-01", calories = "4000.00", protein = "240.000")
        val otherDayId = seedDiaryDay(otherUserId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = otherDayId,
            userId = otherUserId,
            date = "2026-06-21",
            calories = "3900.00",
            protein = "230.000",
            carbs = "420.000",
            fat = "120.000",
            fiber = "50.000",
        )
        seedWeightEntry(otherUserId, "2026-06-20", "95.000")
        seedWeightEntry(otherUserId, "2026-06-26", "94.000")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(0))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(7))
            .andExpect(jsonPath("$.nutrition.calories.total").value(0.00))
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.nutrition.macros.protein.total").value(0.000))
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.total").value(0.000))
            .andExpect(jsonPath("$.weight").isEmpty)
            .andExpect(jsonPath("$.warnings.length()").value(0))
    }

    @Test
    fun `get weekly progress handles diary only goal only weight only and mixed missing days`() {
        val diaryOnlyUserId = UUID.randomUUID()
        seedUser(diaryOnlyUserId, "weekly-progress-diary-only-${System.nanoTime()}@example.com")
        seedProfile(diaryOnlyUserId, "Asia/Tehran")
        val diaryOnlyDayId = seedDiaryDay(diaryOnlyUserId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = diaryOnlyDayId,
            userId = diaryOnlyUserId,
            date = "2026-06-21",
            calories = "600.00",
            protein = "35.000",
        )

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(diaryOnlyUserId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(1))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(6))
            .andExpect(jsonPath("$.nutrition.calories.total").value(600.00))
            .andExpect(jsonPath("$.nutrition.calories.average").value(600.00))
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.nutrition.macros.protein.total").value(35.000))
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.weight").isEmpty)

        val goalOnlyUserId = UUID.randomUUID()
        seedUser(goalOnlyUserId, "weekly-progress-goal-only-${System.nanoTime()}@example.com")
        seedProfile(goalOnlyUserId, "Asia/Tehran")
        seedNutritionPlan(goalOnlyUserId, "2026-06-01")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(goalOnlyUserId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(0))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(7))
            .andExpect(jsonPath("$.nutrition.calories.total").value(0.00))
            .andExpect(jsonPath("$.nutrition.calories.average").isEmpty)
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").isEmpty)
            .andExpect(jsonPath("$.weight").isEmpty)

        val weightOnlyUserId = UUID.randomUUID()
        seedUser(weightOnlyUserId, "weekly-progress-weight-only-${System.nanoTime()}@example.com")
        seedProfile(weightOnlyUserId, "Asia/Tehran")
        seedWeightEntry(weightOnlyUserId, "2026-06-23", "80.200")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(weightOnlyUserId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(0))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(7))
            .andExpect(jsonPath("$.nutrition.calories.total").value(0.00))
            .andExpect(jsonPath("$.weight.configured").value(true))
            .andExpect(jsonPath("$.weight.startWeightKg").value(80.200))
            .andExpect(jsonPath("$.weight.endWeightKg").value(80.200))
            .andExpect(jsonPath("$.weight.absoluteChangeKg").value(0.000))
            .andExpect(jsonPath("$.weight.trendDirection").value("INSUFFICIENT_DATA"))

        val mixedUserId = UUID.randomUUID()
        seedUser(mixedUserId, "weekly-progress-mixed-${System.nanoTime()}@example.com")
        seedProfile(mixedUserId, "Asia/Tehran")
        seedNutritionPlan(mixedUserId, "2026-06-01")
        val mixedDayId = seedDiaryDay(mixedUserId, "2026-06-24")
        seedManualNutritionEntry(
            dayId = mixedDayId,
            userId = mixedUserId,
            date = "2026-06-24",
            calories = "1000.00",
            protein = "60.000",
        )
        seedWeightEntry(mixedUserId, "2026-06-20", "82.000")
        seedWeightEntry(mixedUserId, "2026-06-26", "81.250")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(mixedUserId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nutrition.loggedDayCount").value(1))
            .andExpect(jsonPath("$.nutrition.missingDayCount").value(6))
            .andExpect(jsonPath("$.nutrition.calories.total").value(1000.00))
            .andExpect(jsonPath("$.nutrition.calories.average").value(1000.00))
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").value(50.00))
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").value(50.000))
            .andExpect(jsonPath("$.weight.configured").value(true))
            .andExpect(jsonPath("$.weight.absoluteChangeKg").value(-0.750))
            .andExpect(jsonPath("$.weight.trendDirection").value("DOWN"))
            .andExpect(jsonPath("$.warnings.length()").value(0))
    }

    @Test
    fun `get weekly progress converts saved lb target weight to kilograms`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weekly-progress-lb-target-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(
            userId = userId,
            startDate = "2026-06-01",
            targetWeight = "168.654",
            targetWeightUnit = "LB",
        )
        seedWeightEntry(userId, "2026-06-20", "79.000")
        seedWeightEntry(userId, "2026-06-26", "78.400")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.weight.configured").value(true))
            .andExpect(jsonPath("$.weight.targetWeightKg").value(76.500))
    }

    @Test
    fun `single analytics endpoints resolve week month and phase boundaries in the user timezone`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "analytics-boundaries-${System.nanoTime()}@example.com")
        seedProfile(userId, "Pacific/Pago_Pago")

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "WEEK")
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Pacific/Pago_Pago"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "MONTH")
                .param("month", "2026-02")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Pacific/Pago_Pago"))
            .andExpect(jsonPath("$.from").value("2026-02-01"))
            .andExpect(jsonPath("$.to").value("2026-02-28"))

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-03")
                .param("to", "2026-06-09")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Pacific/Pago_Pago"))
            .andExpect(jsonPath("$.from").value("2026-06-03"))
            .andExpect(jsonPath("$.to").value("2026-06-09"))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "WEEK")
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Pacific/Pago_Pago"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-03")
                .param("to", "2026-06-09")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Pacific/Pago_Pago"))
            .andExpect(jsonPath("$.from").value("2026-06-03"))
            .andExpect(jsonPath("$.to").value("2026-06-09"))
    }

    @Test
    fun `single and batch analytics endpoints produce identical nutrition and weight results for the same range`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "analytics-parity-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-06-01")
        val dayId = seedDiaryDay(userId, "2026-06-03")
        seedManualNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = "2026-06-03",
            calories = "1200.00",
            protein = "80.000",
            carbs = "150.000",
            fat = "40.000",
            fiber = "20.000",
            sugar = "11.000",
            sodium = "500.000",
        )
        seedWeightEntry(userId, "2026-06-01", "82.000")
        seedWeightEntry(userId, "2026-06-07", "81.250")

        val nutritionSingle = mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
            .toJson()
        val nutritionBatch = mockMvc.perform(
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "same-phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
            .toJson()["results"][0]

        assertProgressPayloadEquals(nutritionSingle, nutritionBatch)

        val weightSingle = mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
            .toJson()
        val weightBatch = mockMvc.perform(
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "same-phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
            .toJson()["results"][0]

        assertProgressPayloadEquals(weightSingle, weightBatch)
    }

    @Test
    fun `analytics DTO snapshots keep weekly raw nutrition weight and batch wrapper shapes stable`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "analytics-dto-snapshot-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-06-01")
        val dayId = seedDiaryDay(userId, "2026-06-03")
        seedManualNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = "2026-06-03",
            calories = "500.00",
            protein = "30.000",
            carbs = "55.000",
            fat = "12.000",
            fiber = "8.000",
            sugar = "10.000",
            sodium = "450.000",
        )
        seedWeightEntry(userId, "2026-06-01", "82.000")
        seedWeightEntry(userId, "2026-06-03", "81.500")

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").doesNotExist())
            .andExpect(jsonPath("$.points").doesNotExist())
            .andExpect(jsonPath("$.summary").doesNotExist())
            .andExpect(jsonPath("$.nutrition.calories.total").value(500.00))
            .andExpect(jsonPath("$.nutrition.macros.protein.average").value(30.000))
            .andExpect(jsonPath("$.nutrition.micronutrients.sodium.average").value(450.000))
            .andExpect(jsonPath("$.weight.configured").value(true))
            .andExpect(jsonPath("$.weight.startWeightKg").value(82.000))
            .andExpect(jsonPath("$.warnings.length()").value(0))

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-03")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("PHASE"))
            .andExpect(jsonPath("$.points[0].date").value("2026-06-03"))
            .andExpect(jsonPath("$.points[0].logged").value(true))
            .andExpect(jsonPath("$.points[0].totals.calories").value(500.00))
            .andExpect(jsonPath("$.points[0].goal.configured").value(true))
            .andExpect(jsonPath("$.points[0].goal.targets.protein").value(120.000))
            .andExpect(jsonPath("$.points[0].goal.adherence.caloriesDelta").value(-1500.00))
            .andExpect(jsonPath("$.summary.averagePerLoggedDay.fiber").value(8.000))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("PHASE"))
            .andExpect(jsonPath("$.points[0].date").value("2026-06-01"))
            .andExpect(jsonPath("$.points[0].weightKg").value(82.000))
            .andExpect(jsonPath("$.points[0].hasMeasurement").value(true))
            .andExpect(jsonPath("$.points[1].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[1].hasMeasurement").value(false))
            .andExpect(jsonPath("$.summary.measurementCount").value(2))
            .andExpect(jsonPath("$.summary.missingDayCount").value(1))
            // Two measured days do not earn a fitted line: it would carry exactly the
            // information absoluteChangeKg already reports. The endpoint summary stays.
            .andExpect(jsonPath("$.trend").isEmpty)
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(-0.500))

        mockMvc.perform(
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "snapshot", "period": "PHASE", "from": "2026-06-03", "to": "2026-06-03" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results[0].requestId").value("snapshot"))
            .andExpect(jsonPath("$.results[0].period").value("PHASE"))
            .andExpect(jsonPath("$.results[0].points[0].totals.sodium").value(450.000))
            .andExpect(jsonPath("$.results[0].summary.loggedDayCount").value(1))
    }

    @Test
    fun `analytics responses apply documented rounding for nutrition weight target weight percent and adherence`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "analytics-rounding-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(
            userId = userId,
            startDate = "2026-06-01",
            calories = "333.33",
            protein = "90.000",
            carbs = "120.000",
            fat = "50.000",
            fiber = "25.000",
            targetWeight = "168.654",
            targetWeightUnit = "LB",
        )
        val firstDayId = seedDiaryDay(userId, "2026-06-01")
        seedManualNutritionEntry(
            dayId = firstDayId,
            userId = userId,
            date = "2026-06-01",
            calories = "100.01",
            protein = "10.111",
            carbs = "20.222",
            fat = "3.333",
            fiber = "4.444",
            sugar = "5.555",
            sodium = "100.555",
        )
        val secondDayId = seedDiaryDay(userId, "2026-06-02")
        seedManualNutritionEntry(
            dayId = secondDayId,
            userId = userId,
            date = "2026-06-02",
            calories = "100.02",
            protein = "10.112",
            carbs = "20.223",
            fat = "3.334",
            fiber = "4.445",
            sugar = "5.556",
            sodium = "100.556",
        )
        seedWeightEntry(userId, "2026-06-01", "82.100")
        seedWeightEntry(userId, "2026-06-07", "81.000")

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.totals.calories").value(200.03))
            .andExpect(jsonPath("$.summary.totals.protein").value(20.220))
            .andExpect(jsonPath("$.summary.averagePerDay.calories").value(28.58))
            .andExpect(jsonPath("$.summary.averagePerLoggedDay.calories").value(100.02))
            .andExpect(jsonPath("$.summary.averagePerLoggedDay.protein").value(10.110))
            .andExpect(jsonPath("$.points[0].goal.adherence.caloriesDelta").value(-233.32))
            .andExpect(jsonPath("$.points[0].goal.adherence.proteinDelta").value(-79.890))
            .andExpect(jsonPath("$.points[0].goal.adherence.fiberDelta").value(-20.560))

        mockMvc.perform(
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nutrition.calories.average").value(100.02))
            .andExpect(jsonPath("$.nutrition.calories.goalAveragePercent").value(30.01))
            .andExpect(jsonPath("$.nutrition.macros.protein.goalAveragePercent").value(11.233))
            .andExpect(jsonPath("$.nutrition.micronutrients.fiber.goalAveragePercent").value(17.780))
            .andExpect(jsonPath("$.weight.absoluteChangeKg").value(-1.100))
            .andExpect(jsonPath("$.weight.targetWeightKg").value(76.500))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(-1.100))
            .andExpect(jsonPath("$.summary.percentChange").value(-1.340))
    }

    @Test
    fun `analytics query counts stay bounded for month phase weekly summary and multi range batches`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "analytics-query-count-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-06-01")
        repeat(6) { index ->
            val date = "2026-06-${(index + 1).toString().padStart(2, '0')}"
            val dayId = seedDiaryDay(userId, date)
            seedManualNutritionEntry(
                dayId = dayId,
                userId = userId,
                date = date,
                calories = "100.00",
                protein = "10.000",
            )
            seedWeightEntry(userId, date, "8${index}.000")
        }

        assertBoundedQueryCount("nutrition month", maxQueries = 12) {
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "MONTH")
                .param("month", "2026-06")
        }
        assertBoundedQueryCount("nutrition phase", maxQueries = 12) {
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        }
        assertBoundedQueryCount("weekly summary", maxQueries = 18) {
            get("/api/v1/progress/weekly")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        }
        assertBoundedQueryCount("nutrition batch", maxQueries = 14) {
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "week", "period": "WEEK", "anchor": "2026-06-03" },
                        { "requestId": "month", "period": "MONTH", "month": "2026-06" },
                        { "requestId": "phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        }
        assertBoundedQueryCount("weight batch", maxQueries = 10) {
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "week", "period": "WEEK", "anchor": "2026-06-03" },
                        { "requestId": "phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun `get nutrition progress resolves month boundaries and daily goal adherence`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-month-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-02-01")
        val firstDayId = seedDiaryDay(userId, "2026-02-01")
        seedDiaryDay(userId, "2026-02-02")
        seedManualNutritionEntry(
            dayId = firstDayId,
            userId = userId,
            date = "2026-02-01",
            calories = "1900.00",
            protein = "120.000",
            carbs = "210.000",
            fat = "60.000",
            fiber = "30.000",
        )
        val lastDayId = seedDiaryDay(userId, "2026-02-28")
        seedManualNutritionEntry(
            dayId = lastDayId,
            userId = userId,
            date = "2026-02-28",
            calories = "2100.00",
            protein = "130.000",
            carbs = "240.000",
            fat = "70.000",
            fiber = "25.000",
        )

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "MONTH")
                .param("month", "2026-02")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("MONTH"))
            .andExpect(jsonPath("$.from").value("2026-02-01"))
            .andExpect(jsonPath("$.to").value("2026-02-28"))
            .andExpect(jsonPath("$.points.length()").value(28))
            .andExpect(jsonPath("$.points[0].date").value("2026-02-01"))
            .andExpect(jsonPath("$.points[0].goal.configured").value(true))
            .andExpect(jsonPath("$.points[0].goal.targets.calories").value(2000.00))
            .andExpect(jsonPath("$.points[0].goal.adherence.caloriesDelta").value(-100.00))
            .andExpect(jsonPath("$.points[0].goal.adherence.proteinDelta").value(0.000))
            .andExpect(jsonPath("$.points[1].logged").value(false))
            .andExpect(jsonPath("$.points[1].goal.configured").value(true))
            .andExpect(jsonPath("$.points[1].goal.adherence").isEmpty)
            .andExpect(jsonPath("$.points[27].date").value("2026-02-28"))
            .andExpect(jsonPath("$.summary.totals.calories").value(4000.00))
            .andExpect(jsonPath("$.summary.averagePerLoggedDay.calories").value(2000.00))
            .andExpect(jsonPath("$.summary.loggedDayCount").value(2))
            .andExpect(jsonPath("$.summary.missingDayCount").value(26))
    }

    @Test
    fun `get nutrition progress aggregates immutable diary snapshots after food edits`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-snapshot-${System.nanoTime()}@example.com")
        val foodId = seedFood(userId)
        val dayId = seedDiaryDay(userId, "2026-06-03")
        seedFoodNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = "2026-06-03",
            foodId = foodId,
            calories = "120.00",
            protein = "10.000",
        )
        jdbcTemplate.update(
            "update food_nutrition_facts set calories = 999, protein = 99, updated_at = now() where food_id = ?",
            foodId,
        )

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("PHASE"))
            .andExpect(jsonPath("$.points[2].date").value("2026-06-03"))
            .andExpect(jsonPath("$.points[2].totals.calories").value(120.00))
            .andExpect(jsonPath("$.points[2].totals.protein").value(10.000))
            .andExpect(jsonPath("$.summary.totals.calories").value(120.00))
            .andExpect(jsonPath("$.summary.minDailyTotals.calories").value(0.00))
            .andExpect(jsonPath("$.summary.maxDailyTotals.calories").value(120.00))
    }

    @Test
    fun `get nutrition progress rejects invalid month parameters`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/progress/nutrition")
                .with(authentication(testAuthentication(userId)))
                .param("period", "MONTH")
                .param("anchor", "2026-06-25")
                .param("month", "2026-06")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("anchor, from, and to are not allowed when period=MONTH."))
    }

    @Test
    fun `post nutrition progress batch returns week month and phase results keyed by request id`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-batch-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "nutrition-progress-batch-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedNutritionPlan(userId, "2026-06-01")
        val weekDayId = seedDiaryDay(userId, "2026-06-21")
        seedManualNutritionEntry(
            dayId = weekDayId,
            userId = userId,
            date = "2026-06-21",
            calories = "500.00",
            protein = "30.000",
        )
        val monthDayId = seedDiaryDay(userId, "2026-06-28")
        seedManualNutritionEntry(
            dayId = monthDayId,
            userId = userId,
            date = "2026-06-28",
            calories = "700.00",
            protein = "45.000",
        )
        val phaseDayId = seedDiaryDay(userId, "2026-06-03")
        seedManualNutritionEntry(
            dayId = phaseDayId,
            userId = userId,
            date = "2026-06-03",
            calories = "300.00",
            protein = "20.000",
        )
        val otherDayId = seedDiaryDay(otherUserId, "2026-06-03")
        seedManualNutritionEntry(
            dayId = otherDayId,
            userId = otherUserId,
            date = "2026-06-03",
            calories = "900.00",
            protein = "90.000",
        )

        mockMvc.perform(
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "current-week", "period": "WEEK", "anchor": "2026-06-25" },
                        { "requestId": "current-month", "period": "MONTH", "month": "2026-06" },
                        { "requestId": "cut-phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results.length()").value(3))
            .andExpect(jsonPath("$.results[0].requestId").value("current-week"))
            .andExpect(jsonPath("$.results[0].period").value("WEEK"))
            .andExpect(jsonPath("$.results[0].from").value("2026-06-20"))
            .andExpect(jsonPath("$.results[0].to").value("2026-06-26"))
            .andExpect(jsonPath("$.results[0].summary.totals.calories").value(500.00))
            .andExpect(jsonPath("$.results[0].summary.loggedDayCount").value(1))
            .andExpect(jsonPath("$.results[0].points[1].goal.targets.calories").value(2000.00))
            .andExpect(jsonPath("$.results[1].requestId").value("current-month"))
            .andExpect(jsonPath("$.results[1].period").value("MONTH"))
            .andExpect(jsonPath("$.results[1].from").value("2026-06-01"))
            .andExpect(jsonPath("$.results[1].to").value("2026-06-30"))
            .andExpect(jsonPath("$.results[1].points.length()").value(30))
            .andExpect(jsonPath("$.results[1].summary.totals.calories").value(1500.00))
            .andExpect(jsonPath("$.results[1].summary.loggedDayCount").value(3))
            .andExpect(jsonPath("$.results[2].requestId").value("cut-phase"))
            .andExpect(jsonPath("$.results[2].period").value("PHASE"))
            .andExpect(jsonPath("$.results[2].from").value("2026-06-01"))
            .andExpect(jsonPath("$.results[2].to").value("2026-06-07"))
            .andExpect(jsonPath("$.results[2].summary.totals.calories").value(300.00))
            .andExpect(jsonPath("$.results[2].summary.loggedDayCount").value(1))
    }

    @Test
    fun `post nutrition progress batch rejects duplicate request ids`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-batch-duplicate-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "duplicate", "period": "WEEK", "anchor": "2026-06-25" },
                        { "requestId": "duplicate", "period": "MONTH", "month": "2026-06" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("requestId 'duplicate' must be unique within the batch."))
    }

    @Test
    fun `post nutrition progress batch rejects excessive total covered days`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "nutrition-progress-batch-span-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/progress/nutrition/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "phase-a", "period": "PHASE", "from": "2026-01-01", "to": "2026-07-19" },
                        { "requestId": "phase-b", "period": "PHASE", "from": "2026-07-20", "to": "2027-02-04" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Nutrition progress batch cannot cover more than 366 total days."))
    }

    @Test
    fun `post weight progress batch returns week and phase results keyed by request id`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "weight-progress-batch-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "weight-progress-batch-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedWeightEntry(userId, "2026-06-21", "79.000")
        seedWeightEntry(userId, "2026-06-25", "78.400")
        seedWeightEntry(userId, "2026-06-01", "82.100")
        seedWeightEntry(userId, "2026-06-07", "81.000")
        seedWeightEntry(otherUserId, "2026-06-07", "95.000")

        mockMvc.perform(
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "current-week", "period": "WEEK", "anchor": "2026-06-25" },
                        { "requestId": "cut-phase", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].requestId").value("current-week"))
            .andExpect(jsonPath("$.results[0].period").value("WEEK"))
            .andExpect(jsonPath("$.results[0].timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.results[0].points.length()").value(7))
            .andExpect(jsonPath("$.results[0].summary.startWeightKg").value(79.000))
            .andExpect(jsonPath("$.results[0].summary.endWeightKg").value(78.400))
            .andExpect(jsonPath("$.results[1].requestId").value("cut-phase"))
            .andExpect(jsonPath("$.results[1].period").value("PHASE"))
            .andExpect(jsonPath("$.results[1].from").value("2026-06-01"))
            .andExpect(jsonPath("$.results[1].to").value("2026-06-07"))
            .andExpect(jsonPath("$.results[1].summary.startWeightKg").value(82.100))
            .andExpect(jsonPath("$.results[1].summary.endWeightKg").value(81.000))
            .andExpect(jsonPath("$.results[1].summary.absoluteChangeKg").value(-1.100))
            .andExpect(jsonPath("$.results[1].summary.percentChange").value(-1.340))
            .andExpect(jsonPath("$.results[1].summary.trendDirection").value("DOWN"))
    }

    @Test
    fun `post weight progress batch rejects duplicate request ids`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-batch-duplicate-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "duplicate", "period": "WEEK", "anchor": "2026-06-25" },
                        { "requestId": "duplicate", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-07" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("requestId 'duplicate' must be unique within the batch."))
    }

    @Test
    fun `post weight progress batch rejects excessive total covered days`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-batch-span-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "phase-a", "period": "PHASE", "from": "2026-01-01", "to": "2026-07-19" },
                        { "requestId": "phase-b", "period": "PHASE", "from": "2026-07-20", "to": "2027-02-04" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Weight progress batch cannot cover more than 366 total days."))
    }


    @Test
    fun `get weight progress returns a Saturday-start week with explicit missing days`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "weight-progress-week-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "weight-progress-week-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedWeightEntry(userId, "2026-06-21", "79.000")
        seedWeightEntry(userId, "2026-06-23", "78.900")
        seedWeightEntry(userId, "2026-06-25", "78.400")
        seedWeightEntry(otherUserId, "2026-06-24", "91.500")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "WEEK")
                .param("anchor", "2026-06-25")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("WEEK"))
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.from").value("2026-06-20"))
            .andExpect(jsonPath("$.to").value("2026-06-26"))
            .andExpect(jsonPath("$.points.length()").value(7))
            .andExpect(jsonPath("$.points[0].date").value("2026-06-20"))
            .andExpect(jsonPath("$.points[0].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[0].hasMeasurement").value(false))
            .andExpect(jsonPath("$.points[1].date").value("2026-06-21"))
            .andExpect(jsonPath("$.points[1].weightKg").value(79.000))
            .andExpect(jsonPath("$.points[1].hasMeasurement").value(true))
            .andExpect(jsonPath("$.points[4].date").value("2026-06-24"))
            .andExpect(jsonPath("$.points[4].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[5].date").value("2026-06-25"))
            .andExpect(jsonPath("$.points[5].weightKg").value(78.400))
            .andExpect(jsonPath("$.summary.startWeightKg").value(79.000))
            .andExpect(jsonPath("$.summary.endWeightKg").value(78.400))
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(-0.600))
            .andExpect(jsonPath("$.summary.percentChange").value(-0.759))
            .andExpect(jsonPath("$.summary.trendDirection").value("DOWN"))
            .andExpect(jsonPath("$.summary.measurementCount").value(3))
            .andExpect(jsonPath("$.summary.missingDayCount").value(4))
    }

    @Test
    fun `get weight progress returns explicit phase ranges without interpolating values`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-phase-${System.nanoTime()}@example.com")
        seedProfile(userId, "Europe/Berlin")
        seedWeightEntry(userId, "2026-06-01", "82.100")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value("PHASE"))
            .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
            .andExpect(jsonPath("$.from").value("2026-06-01"))
            .andExpect(jsonPath("$.to").value("2026-06-03"))
            .andExpect(jsonPath("$.points.length()").value(3))
            .andExpect(jsonPath("$.points[0].weightKg").value(82.100))
            .andExpect(jsonPath("$.points[1].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[2].weightKg").isEmpty)
            .andExpect(jsonPath("$.summary.startWeightKg").value(82.100))
            .andExpect(jsonPath("$.summary.endWeightKg").value(82.100))
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(0.000))
            .andExpect(jsonPath("$.summary.percentChange").value(0.000))
            .andExpect(jsonPath("$.summary.trendDirection").value("INSUFFICIENT_DATA"))
            .andExpect(jsonPath("$.summary.measurementCount").value(1))
            .andExpect(jsonPath("$.summary.missingDayCount").value(2))
    }

    @Test
    fun `get weight progress exposes the latest measurement outside the requested month`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-latest-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedWeightEntry(userId, "2026-07-31", "80.000")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-08-01")
                .param("to", "2026-08-31")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.latestMeasurementDate").value("2026-07-31"))
            .andExpect(jsonPath("$.summary.measurementCount").value(0))
    }

    @Test
    fun `get weight progress summarizes first and last measurements even when range endpoints are empty`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-boundary-gap-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-03", "82.100")
        seedWeightEntry(userId, "2026-06-05", "81.400")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.points.length()").value(7))
            .andExpect(jsonPath("$.summary.startWeightKg").value(82.100))
            .andExpect(jsonPath("$.summary.endWeightKg").value(81.400))
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(-0.700))
            .andExpect(jsonPath("$.summary.percentChange").value(-0.853))
            .andExpect(jsonPath("$.summary.trendDirection").value("DOWN"))
            .andExpect(jsonPath("$.summary.measurementCount").value(2))
            .andExpect(jsonPath("$.summary.missingDayCount").value(5))
    }

    @Test
    fun `get weight progress fits a trend line once three days are measured`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-trend-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-01", "82.000")
        seedWeightEntry(userId, "2026-06-03", "81.500")
        seedWeightEntry(userId, "2026-06-05", "81.000")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-07")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.trend.method").value("OLS"))
            // -0.25 kg/day over the fitted line.
            .andExpect(jsonPath("$.trend.slopeKgPerWeek").value(-1.750))
            .andExpect(jsonPath("$.trend.direction").value("DOWN"))
            .andExpect(jsonPath("$.trend.startValueKg").value(82.000))
            .andExpect(jsonPath("$.trend.endValueKg").value(81.000))
            // Measured dates only. The two unmeasured days inside the span, and the two
            // empty days at the end of the range, produce no fitted points.
            .andExpect(jsonPath("$.trend.points.length()").value(3))
            .andExpect(jsonPath("$.trend.points[0].date").value("2026-06-01"))
            .andExpect(jsonPath("$.trend.points[1].date").value("2026-06-03"))
            .andExpect(jsonPath("$.trend.points[1].fittedWeightKg").value(81.500))
            // Diagnostics stay internal: no product surface has been designed for them.
            .andExpect(jsonPath("$.trend.rSquared").doesNotExist())
            .andExpect(jsonPath("$.trend.slopeStdError").doesNotExist())
            // Endpoint facts are untouched and answer a different question.
            .andExpect(jsonPath("$.summary.trendDirection").value("DOWN"))
            .andExpect(jsonPath("$.summary.absoluteChangeKg").value(-1.000))
    }

    @Test
    fun `weight trend reports flat inside the dead zone that endpoint direction misses`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-trend-flat-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-01", "80.000")
        seedWeightEntry(userId, "2026-06-08", "80.010")
        seedWeightEntry(userId, "2026-06-15", "80.020")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-15")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.trend.slopeKgPerWeek").value(0.010))
            // 10 g/week is noise, not a direction.
            .andExpect(jsonPath("$.trend.direction").value("FLAT"))
            // The endpoint comparison takes a strict sign and calls the same data UP.
            .andExpect(jsonPath("$.summary.trendDirection").value("UP"))
    }

    @Test
    fun `weight trend direction comes from the unrounded slope at the dead zone boundary`() {
        // Every case below serializes as the same 0.050 kg/week. Classifying the rounded
        // value would call all four a gain or a loss; only the unrounded slope separates
        // "inside the dead zone" from "just outside it". The Persian description reads
        // direction rather than the number, so this is user-visible copy.
        assertTrendBoundary(
            label = "positive-inside",
            days = listOf("2026-06-01" to "80.000", "2026-06-06" to "80.036", "2026-06-11" to "80.071"),
            to = "2026-06-11",
            expectedSlope = 0.050,
            expectedDirection = "FLAT",
        )
        assertTrendBoundary(
            label = "positive-outside",
            days = listOf("2026-06-01" to "80.000", "2026-06-08" to "80.050", "2026-06-15" to "80.100"),
            to = "2026-06-15",
            expectedSlope = 0.050,
            expectedDirection = "UP",
        )
        assertTrendBoundary(
            label = "negative-inside",
            days = listOf("2026-06-01" to "80.071", "2026-06-06" to "80.036", "2026-06-11" to "80.000"),
            to = "2026-06-11",
            expectedSlope = -0.050,
            expectedDirection = "FLAT",
        )
        assertTrendBoundary(
            label = "negative-outside",
            days = listOf("2026-06-01" to "80.100", "2026-06-08" to "80.050", "2026-06-15" to "80.000"),
            to = "2026-06-15",
            expectedSlope = -0.050,
            expectedDirection = "DOWN",
        )
    }

    private fun assertTrendBoundary(
        label: String,
        days: List<Pair<String, String>>,
        to: String,
        expectedSlope: Double,
        expectedDirection: String,
    ) {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-trend-$label-${System.nanoTime()}@example.com")
        days.forEach { (date, weight) -> seedWeightEntry(userId, date, weight) }

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", to)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.trend.slopeKgPerWeek").value(expectedSlope))
            .andExpect(jsonPath("$.trend.direction").value(expectedDirection))
    }

    @Test
    fun `weight batch results carry the fitted trend per range`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-trend-batch-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-01", "82.000")
        seedWeightEntry(userId, "2026-06-03", "81.500")
        seedWeightEntry(userId, "2026-06-05", "81.000")

        mockMvc.perform(
            post("/api/v1/progress/weight/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType("application/json")
                .content(
                    """
                    {
                      "ranges": [
                        { "requestId": "fitted", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-05" },
                        { "requestId": "too-short", "period": "PHASE", "from": "2026-06-01", "to": "2026-06-03" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results[0].requestId").value("fitted"))
            .andExpect(jsonPath("$.results[0].trend.slopeKgPerWeek").value(-1.750))
            // Each range is fitted over its own window, so a shorter range can fall below
            // the three-day floor while a longer one over the same data does not.
            .andExpect(jsonPath("$.results[1].requestId").value("too-short"))
            .andExpect(jsonPath("$.results[1].trend").isEmpty)
    }

    @Test
    fun `get weight progress returns empty summaries when no measurements exist`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-empty-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-01")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.points.length()").value(3))
            .andExpect(jsonPath("$.points[0].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[1].weightKg").isEmpty)
            .andExpect(jsonPath("$.points[2].weightKg").isEmpty)
            .andExpect(jsonPath("$.summary.startWeightKg").isEmpty)
            .andExpect(jsonPath("$.summary.endWeightKg").isEmpty)
            .andExpect(jsonPath("$.summary.absoluteChangeKg").isEmpty)
            .andExpect(jsonPath("$.summary.percentChange").isEmpty)
            .andExpect(jsonPath("$.summary.trendDirection").value("INSUFFICIENT_DATA"))
            .andExpect(jsonPath("$.summary.measurementCount").value(0))
            .andExpect(jsonPath("$.summary.missingDayCount").value(3))
    }

    @Test
    fun `get weight progress rejects invalid period parameters and ranges`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-progress-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "WEEK")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("anchor is required when period=WEEK."))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("anchor", "2026-06-25")
                .param("from", "2026-06-01")
                .param("to", "2026-06-03")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("anchor is not allowed when period=PHASE."))

        mockMvc.perform(
            get("/api/v1/progress/weight")
                .with(authentication(testAuthentication(userId)))
                .param("period", "PHASE")
                .param("from", "2026-06-03")
                .param("to", "2026-06-01")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("to must be on or after from."))
    }

    private fun String.toJson(): JsonNode {
        assertTrue(isNotBlank())
        return objectMapper.readTree(this)
    }

    private fun assertProgressPayloadEquals(
        single: JsonNode,
        batchResult: JsonNode,
    ) {
        assertEquals(single["period"], batchResult["period"])
        assertEquals(single["timezone"], batchResult["timezone"])
        assertEquals(single["from"], batchResult["from"])
        assertEquals(single["to"], batchResult["to"])
        assertEquals(single["points"], batchResult["points"])
        assertEquals(single["summary"], batchResult["summary"])
    }

    private fun assertBoundedQueryCount(
        label: String,
        maxQueries: Int,
        request: () -> MockHttpServletRequestBuilder,
    ) {
        QueryCounter.reset()
        mockMvc.perform(request())
            .andExpect(status().isOk)
        val queryCount = QueryCounter.count()
        assertTrue(
            queryCount > 0,
            "$label did not record any SQL statements; query-count guard is not active",
        )
        assertTrue(
            queryCount <= maxQueries,
            "$label executed $queryCount SQL statements, expected at most $maxQueries",
        )
    }

    private fun seedUser(id: UUID, email: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id,
                email,
                password_hash,
                role,
                email_verification_status,
                phone_verification_status,
                status,
                created_at,
                updated_at
            )
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
        )
    }

    private fun seedProfile(
        userId: UUID,
        timezone: String,
    ) {
        jdbcTemplate.update(
            """
            insert into user_profiles (
                user_id,
                timezone,
                locale,
                created_at,
                updated_at
            )
            values (?, ?, 'fa-IR', now(), now())
            on conflict (user_id) do update
            set timezone = excluded.timezone,
                locale = excluded.locale,
                updated_at = now()
            """.trimIndent(),
            userId,
            timezone,
        )
    }

    private fun seedWeightEntry(
        userId: UUID,
        recordedDate: String,
        weightKg: String,
    ) {
        jdbcTemplate.update(
            """
            insert into weight_entries (
                user_id,
                recorded_date,
                recorded_at,
                weight_kg,
                display_weight,
                display_unit,
                source,
                created_at,
                updated_at
            )
            values (?, ?::date, ?, ?::numeric, ?::numeric, 'KG', 'MANUAL', ?, ?)
            """.trimIndent(),
            userId,
            recordedDate,
            Timestamp.from(FIXED_INSTANT),
            weightKg,
            weightKg,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
    }

    private fun seedNutritionPlan(
        userId: UUID,
        startDate: String,
        calories: String = "2000.00",
        protein: String = "120.000",
        carbs: String = "220.000",
        fat: String = "65.000",
        fiber: String = "28.000",
        targetWeight: String? = null,
        targetWeightUnit: String? = null,
    ) {
        jdbcTemplate.update(
            """
            insert into nutrition_plans (
                id,
                user_id,
                start_date,
                timezone,
                calories,
                protein,
                carbs,
                fat,
                fiber,
                target_weight,
                target_weight_unit,
                created_at,
                updated_at
            )
            values (?, ?, ?::date, 'Asia/Tehran', ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            startDate,
            calories,
            protein,
            carbs,
            fat,
            fiber,
            targetWeight,
            targetWeightUnit,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
    }

    private fun seedDiaryDay(
        userId: UUID,
        date: String,
    ): UUID {
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, ?::date, 'Asia/Tehran', ?, ?)
            """.trimIndent(),
            dayId,
            userId,
            date,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
        return dayId
    }

    private fun seedManualNutritionEntry(
        dayId: UUID,
        userId: UUID,
        date: String,
        calories: String = "0.00",
        protein: String = "0.000",
        carbs: String = "0.000",
        fat: String = "0.000",
        fiber: String = "0.000",
        sugar: String = "0.000",
        sodium: String = "0.000",
    ) {
        seedNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = date,
            sourceType = "MANUAL",
            sourceFoodId = null,
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber,
            sugar = sugar,
            sodium = sodium,
        )
    }

    private fun seedFoodNutritionEntry(
        dayId: UUID,
        userId: UUID,
        date: String,
        foodId: UUID,
        calories: String,
        protein: String,
    ) {
        seedNutritionEntry(
            dayId = dayId,
            userId = userId,
            date = date,
            sourceType = "FOOD",
            sourceFoodId = foodId,
            calories = calories,
            protein = protein,
        )
    }

    private fun seedNutritionEntry(
        dayId: UUID,
        userId: UUID,
        date: String,
        sourceType: String,
        sourceFoodId: UUID?,
        calories: String,
        protein: String,
        carbs: String = "0.000",
        fat: String = "0.000",
        fiber: String = "0.000",
        sugar: String = "0.000",
        sodium: String = "0.000",
    ) {
        jdbcTemplate.update(
            """
            insert into diary_entries (
                id,
                diary_day_id,
                user_id,
                diary_date,
                meal_type,
                source_type,
                source_food_id,
                display_name_snapshot,
                serving_quantity_snapshot,
                serving_unit_code_snapshot,
                serving_unit_name_snapshot,
                calories_snapshot,
                protein_snapshot,
                carbs_snapshot,
                fat_snapshot,
                fiber_snapshot,
                sugar_snapshot,
                sodium_snapshot,
                sort_order,
                created_at,
                updated_at
            )
            values (
                ?, ?, ?, ?::date, 'LUNCH', ?, ?, 'Progress Snapshot',
                1.0000, 'SERVING', 'Serving',
                ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric,
                0, ?, ?
            )
            """.trimIndent(),
            UUID.randomUUID(),
            dayId,
            userId,
            date,
            sourceType,
            sourceFoodId,
            calories,
            protein,
            carbs,
            fat,
            fiber,
            sugar,
            sodium,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
    }

    private fun seedFood(ownerUserId: UUID): UUID {
        val foodId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into foods (
                id,
                public_id,
                owner_user_id,
                type,
                source,
                name,
                normalized_name,
                data_quality,
                curation_status,
                is_searchable,
                created_at,
                updated_at
            )
            values (?, ?, ?, 'CUSTOM', 'USER_CURATED', 'Progress Food', 'progress food', 'USER_SUBMITTED', 'REVIEWED', true, ?, ?)
            """.trimIndent(),
            foodId,
            "progress_food_${foodId.toString().replace("-", "")}",
            ownerUserId,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (
                food_id,
                base_quantity,
                base_unit_id,
                calories,
                protein,
                carbs,
                fat,
                fiber,
                sugar,
                sodium,
                created_at,
                updated_at
            )
            values (?, 100, (select id from serving_units where code = 'GRAM'), 120, 10, 0, 0, 0, 0, 0, ?, ?)
            """.trimIndent(),
            foodId,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
        return foodId
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    @TestConfiguration
    class QueryCountingDataSourceConfiguration {
        @Bean
        fun queryCountingDataSourcePostProcessor(): BeanPostProcessor {
            return object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any {
                    if (bean is DataSource && beanName == "dataSource") {
                        return QueryCountingDataSource(bean)
                    }
                    return bean
                }
            }
        }
    }

    private class QueryCountingDataSource(
        private val delegate: DataSource,
    ) : DataSource {
        override fun getConnection(): Connection {
            return delegate.connection.countingProxy()
        }

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection {
            return delegate.getConnection(username, password).countingProxy()
        }

        override fun getLogWriter(): PrintWriter? = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) {
            delegate.logWriter = out
        }

        override fun setLoginTimeout(seconds: Int) {
            delegate.loginTimeout = seconds
        }

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger(): Logger = delegate.parentLogger

        override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
    }

    private class QueryCountingConnectionHandler(
        private val delegate: Connection,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val result = invokeDelegate(delegate, method, args)
            return when (result) {
                is CallableStatement -> result.countingProxy(CallableStatement::class.java)
                is PreparedStatement -> result.countingProxy(PreparedStatement::class.java)
                is Statement -> result.countingProxy(Statement::class.java)
                else -> result
            }
        }
    }

    private class QueryCountingStatementHandler(
        private val delegate: Statement,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            if (method.name.startsWith("execute")) {
                QueryCounter.increment()
            }
            return invokeDelegate(delegate, method, args)
        }
    }

    private object QueryCounter {
        private val count = ThreadLocal.withInitial { 0 }

        fun reset() {
            count.set(0)
        }

        fun increment() {
            count.set(count.get() + 1)
        }

        fun count(): Int = count.get()
    }

    companion object {
        private val FIXED_INSTANT: Instant = Instant.parse("2026-06-25T04:30:00Z")

        private fun Connection.countingProxy(): Connection {
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                QueryCountingConnectionHandler(this),
            ) as Connection
        }

        private fun <T : Statement> T.countingProxy(statementInterface: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                statementInterface.classLoader,
                arrayOf(statementInterface),
                QueryCountingStatementHandler(this),
            ) as T
        }

        private fun invokeDelegate(
            delegate: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

}
