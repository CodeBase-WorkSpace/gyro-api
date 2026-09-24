package com.gyro.api.goal

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

@Import(
    TestcontainersConfiguration::class,
    GoalControllerIntegrationTest.FixedClockConfiguration::class,
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
        "app.goal-schedules.premium-goal-schedules-enabled=false",
        "app.premium-gating.free-future-diary-days=3",
    ]
)
class GoalControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `get goals returns an empty state when the authenticated user has no goal`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-empty-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UNCONFIGURED"))
            .andExpect(jsonPath("$.goal").doesNotExist())
            .andExpect(jsonPath("$.activePlan").doesNotExist())
            .andExpect(jsonPath("$.todayTarget").doesNotExist())
    }

    @Test
    fun `put goals accepts the maintain weight calculator result without target fields`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-maintain-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(maintainWeightGoalJson(localToday))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.activePlan.goalType").value("MAINTAIN_WEIGHT"))
            .andExpect(jsonPath("$.goal.targetWeight").doesNotExist())
            .andExpect(jsonPath("$.goal.targetDate").doesNotExist())
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(0.00))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("FLAT"))
    }

    @Test
    fun `get goals resolves the latest active plan on or before the user's local today`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-timezone-${System.nanoTime()}@example.com")
        seedProfile(userId, "Pacific/Pago_Pago")

        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Pacific/Pago_Pago")).toLocalDate()
        seedGoal(
            userId = userId,
            startDate = localToday,
            timezone = "Pacific/Pago_Pago",
            calories = BigDecimal("2200.00"),
            protein = BigDecimal("140.000"),
            carbs = BigDecimal("220.000"),
            fat = BigDecimal("70.000"),
            fiber = BigDecimal("28.000"),
        )
        seedGoal(
            userId = userId,
            startDate = localToday.plusDays(1),
            timezone = "Pacific/Pago_Pago",
            calories = BigDecimal("2400.00"),
            protein = BigDecimal("160.000"),
            carbs = BigDecimal("250.000"),
            fat = BigDecimal("80.000"),
            fiber = BigDecimal("30.000"),
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.activePlan.startDate").value(localToday.toString()))
            .andExpect(jsonPath("$.todayTarget.date").value(localToday.toString()))
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2200.00))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("FLAT"))
            .andExpect(jsonPath("$.activePlan.schedule.activeFrom").value(localToday.toString()))
            .andExpect(jsonPath("$.todayTarget.source").value("BASE_PLAN"))
    }

    @Test
    fun `get goals resolves the schedule attached to the selected goal instead of another active user schedule`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-schedule-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        // Premium schedules only resolve in full for entitled users; without
        // this the resolver degrades the target to the weekly average.
        seedAdvancedSubscription(userId)
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        val currentGoalId = seedGoal(
            userId = userId,
            startDate = localToday.minusDays(7),
            timezone = "Asia/Tehran",
            calories = BigDecimal("2200.00"),
            protein = BigDecimal("140.000"),
            carbs = BigDecimal("220.000"),
            fat = BigDecimal("70.000"),
            fiber = BigDecimal("28.000"),
        )
        val futureGoalId = seedGoal(
            userId = userId,
            startDate = localToday.plusDays(4),
            timezone = "Asia/Tehran",
            calories = BigDecimal("2600.00"),
            protein = BigDecimal("180.000"),
            carbs = BigDecimal("280.000"),
            fat = BigDecimal("90.000"),
            fiber = BigDecimal("32.000"),
        )

        seedSchedule(
            userId = userId,
            nutritionPlanId = currentGoalId,
            scheduleType = "WEEKDAY_WEEKEND",
            activeFrom = localToday.minusDays(7),
            activeTo = null,
            weekdayTargetsJson = """
                {"${localToday.dayOfWeek.name}":{"calories":2100.00,"protein":150.000,"carbs":180.000,"fat":60.000,"fiber":25.000}}
            """.trimIndent(),
        )
        seedSchedule(
            userId = userId,
            nutritionPlanId = futureGoalId,
            scheduleType = "FLAT",
            activeFrom = localToday.minusDays(2),
            activeTo = null,
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.goal").doesNotExist())
            .andExpect(jsonPath("$.activePlan.id").value(currentGoalId.toString()))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("WEEKDAY_WEEKEND"))
            .andExpect(jsonPath("$.todayTarget.source").value("WEEKDAY_RULE"))
            .andExpect(jsonPath("$.todayTarget.sourceDetail").value(localToday.dayOfWeek.name))
            .andExpect(jsonPath("$.todayTarget.targets.calories").value(2100.00))
            .andExpect(jsonPath("$.todayTarget.targets.protein").value(150.000))
            .andExpect(jsonPath("$.todayTarget.targets.carbs").value(180.000))
            .andExpect(jsonPath("$.todayTarget.targets.fat").value(60.000))
    }

    @Test
    fun `get and put goals stay scoped to the authenticated user`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "goal-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "goal-owner-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedProfile(otherUserId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        seedGoal(
            userId = otherUserId,
            startDate = localToday,
            timezone = "Asia/Tehran",
            calories = BigDecimal("3200.00"),
            protein = BigDecimal("210.000"),
            carbs = BigDecimal("360.000"),
            fat = BigDecimal("100.000"),
            fiber = BigDecimal("40.000"),
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UNCONFIGURED"))
            .andExpect(jsonPath("$.goal").doesNotExist())
            .andExpect(jsonPath("$.activePlan").doesNotExist())
            .andExpect(jsonPath("$.todayTarget").doesNotExist())

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday, calories = "2200.00"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2200.00))

        assertEquals(1, nutritionPlanCount(userId))
        assertEquals(1, nutritionPlanCount(otherUserId))
        assertEquals("3200.00", caloriesFor(otherUserId, localToday))
    }

    @Test
    fun `delete goals clears only the authenticated user's plans`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "goal-delete-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "goal-delete-other-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        seedProfile(otherUserId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        val deletedPlanId = seedGoal(
            userId = userId,
            startDate = localToday,
            timezone = "Asia/Tehran",
            calories = BigDecimal("2200.00"),
            protein = BigDecimal("140.000"),
            carbs = BigDecimal("220.000"),
            fat = BigDecimal("70.000"),
            fiber = BigDecimal("28.000"),
        )
        val retainedPlanId = seedGoal(
            userId = otherUserId,
            startDate = localToday,
            timezone = "Asia/Tehran",
            calories = BigDecimal("2400.00"),
            protein = BigDecimal("150.000"),
            carbs = BigDecimal("250.000"),
            fat = BigDecimal("80.000"),
            fiber = BigDecimal("30.000"),
        )

        mockMvc.perform(
            delete("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UNCONFIGURED"))
            .andExpect(jsonPath("$.goal").doesNotExist())
            .andExpect(jsonPath("$.activePlan").doesNotExist())
            .andExpect(jsonPath("$.todayTarget").doesNotExist())

        assertEquals(0, nutritionPlanCount(userId))
        assertEquals(1, nutritionPlanCount(otherUserId))
        assertEquals(0, rowCountById("nutrition_plans", deletedPlanId))
        assertEquals(0, rowCountById("plan_schedules", deletedPlanId, "nutrition_plan_id"))
        assertEquals(1, rowCountById("nutrition_plans", retainedPlanId))
    }

    @Test
    fun `get goals returns composed goal outcome active plan and daily target when outcome data exists`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-composed-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        seedGoal(
            userId = userId,
            startDate = localToday.minusDays(3),
            timezone = "Asia/Tehran",
            calories = BigDecimal("2250.00"),
            protein = BigDecimal("145.000"),
            carbs = BigDecimal("215.000"),
            fat = BigDecimal("72.000"),
            fiber = BigDecimal("29.000"),
            targetWeight = BigDecimal("76.500"),
            targetWeightUnit = "KG",
            targetDate = localToday.plusDays(90),
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.goal.targetWeight.value").value(76.500))
            .andExpect(jsonPath("$.goal.targetWeight.unit").value("KG"))
            .andExpect(jsonPath("$.activePlan.startDate").value(localToday.minusDays(3).toString()))
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2250.00))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("FLAT"))
            .andExpect(jsonPath("$.todayTarget.targets.calories").value(2250.00))
    }

    @Test
    fun `put goals saves a flat effective dated goal and returns the canonical composed response`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-save-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        localToday,
                        warningCodes = """["AGGRESSIVE_WEIGHT_LOSS"]""",
                        acceptedWarningCodes = """["AGGRESSIVE_WEIGHT_LOSS"]""",
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.goal.targetWeight.value").value(76.500))
            .andExpect(jsonPath("$.goal.targetWeight.unit").value("KG"))
            .andExpect(jsonPath("$.goal.targetDate").value(localToday.plusDays(90).toString()))
            .andExpect(jsonPath("$.activePlan.goalType").value("LOSE_WEIGHT"))
            .andExpect(jsonPath("$.activePlan.startDate").value(localToday.toString()))
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2200.00))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("FLAT"))
            .andExpect(jsonPath("$.todayTarget.date").value(localToday.toString()))
            .andExpect(jsonPath("$.todayTarget.source").value("BASE_PLAN"))
            .andExpect(jsonPath("$.todayTarget.targets.protein").value(140.000))

        val persistedWarningCode = jdbcTemplate.queryForObject(
            """
            select safety_warning_codes[1]
            from nutrition_plans
            where user_id = ? and start_date = ?
            """.trimIndent(),
            String::class.java,
            userId,
            localToday,
        )
        assertEquals("AGGRESSIVE_WEIGHT_LOSS", persistedWarningCode)
    }

    @Test
    fun `ordinary save preserves every calculator snapshot field`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-preserve-calculator-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday, calculatorUpdateMode = "REPLACE"))
        ).andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        localToday,
                        includeCalculator = false,
                        calculatorUpdateMode = "PRESERVE",
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.calculator.formula").value("mifflin-st-jeor"))
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
            .andExpect(jsonPath("$.activePlan.calculator.profile.currentWeightKg").value(82.000))

        val snapshot = jdbcTemplate.queryForMap(
            """
            select calculator_formula, calculator_formula_version, maintenance_calories,
                   target_calories, activity_factor, daily_energy_delta,
                   weekly_weight_change_kg, estimated_weeks_min, estimated_weeks_max,
                   calculator_current_weight_kg
            from nutrition_plans where user_id = ? and start_date = ?
            """.trimIndent(),
            userId,
            localToday,
        )
        assertEquals("mifflin-st-jeor", snapshot["calculator_formula"])
        assertEquals("1", snapshot["calculator_formula_version"])
        assertEquals(BigDecimal("-400.00"), snapshot["daily_energy_delta"])
        assertEquals(BigDecimal("82.000"), snapshot["calculator_current_weight_kg"])
    }

    @Test
    fun `preserve rejects changed calculator inputs and clear explicitly downgrades`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-explicit-clear-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday, calculatorUpdateMode = "REPLACE"))
        ).andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        localToday,
                        calories = "2250.00",
                        includeCalculator = false,
                        calculatorUpdateMode = "PRESERVE",
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].code").value("CALCULATOR_INPUTS_CHANGED"))

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        localToday,
                        calories = "2250.00",
                        includeCalculator = false,
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].code").value("CALCULATOR_UPDATE_MODE_REQUIRED"))

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        localToday,
                        calories = "2250.00",
                        includeCalculator = false,
                        calculatorUpdateMode = "CLEAR",
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2250.00))
            .andExpect(jsonPath("$.activePlan.calculator").doesNotExist())

        val dailyDelta = jdbcTemplate.queryForObject(
            "select daily_energy_delta from nutrition_plans where user_id = ? and start_date = ?",
            BigDecimal::class.java,
            userId,
            localToday,
        )
        assertEquals(null, dailyDelta)
    }

    @Test
    fun `legacy unchanged save without calculator preserves the snapshot`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-legacy-preserve-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday))
        ).andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday, includeCalculator = false))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
    }

    @Test
    fun `free users can save flat goals with target dates outside the diary window`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-free-long-target-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        val longTermTargetDate = localToday.plusDays(120)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        startDate = localToday,
                        targetDate = longTermTargetDate,
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.goal.targetDate").value(longTermTargetDate.toString()))
            .andExpect(jsonPath("$.activePlan.schedule.type").value("FLAT"))
    }

    @Test
    fun `free users cannot activate flat goals outside the diary window`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-free-future-start-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        val futureStartDate = localToday.plusDays(4)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(startDate = futureStartDate))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FUTURE_DATE_LIMIT"))
            .andExpect(jsonPath("$.metadata.maximumDate").value(localToday.plusDays(3).toString()))
    }

    @Test
    fun `saved goal snapshot stays stable after profile planning values change`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-snapshot-stability-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.calculator.formula").value("mifflin-st-jeor"))
            .andExpect(jsonPath("$.activePlan.calculator.formulaVersion").value("1"))
            .andExpect(jsonPath("$.activePlan.calculator.maintenanceCalories").value(2600.00))
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
            .andExpect(jsonPath("$.activePlan.calculator.expectedWeeklyWeightChangeKg").value(-0.363))
            .andExpect(jsonPath("$.activePlan.calculator.profile.sex").value("FEMALE"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.birthDate").value("1995-04-12"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.heightCm").value(165.00))
            .andExpect(jsonPath("$.activePlan.calculator.profile.currentWeightKg").value(82.000))
            .andExpect(jsonPath("$.activePlan.calculator.profile.targetWeightKg").value(76.500))
            .andExpect(jsonPath("$.activePlan.calculator.profile.dailyMovementLevel").value("MODERATE"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.workoutFrequency").value("THREE_TO_FOUR_DAYS"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.speed").value("BALANCED"))

        jdbcTemplate.update(
            """
            update user_profiles
            set sex = 'MALE',
                birth_date = ?,
                height_cm = 188.00,
                current_weight_kg = 105.000,
                target_weight_kg = 90.000,
                daily_movement_level = 'VERY_ACTIVE',
                workout_frequency = 'DAILY',
                goal_type = 'GAIN_WEIGHT',
                updated_at = now()
            where user_id = ?
            """.trimIndent(),
            LocalDate.of(1988, 2, 10),
            userId,
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIGURED"))
            .andExpect(jsonPath("$.goal.targetWeight.value").value(76.500))
            .andExpect(jsonPath("$.goal.targetWeight.unit").value("KG"))
            .andExpect(jsonPath("$.goal.targetDate").value(localToday.plusDays(90).toString()))
            .andExpect(jsonPath("$.activePlan.goalType").value("LOSE_WEIGHT"))
            .andExpect(jsonPath("$.activePlan.startDate").value(localToday.toString()))
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2200.00))
            .andExpect(jsonPath("$.activePlan.baseTargets.protein").value(140.000))
            .andExpect(jsonPath("$.activePlan.baseTargets.carbs").value(220.000))
            .andExpect(jsonPath("$.activePlan.baseTargets.fat").value(70.000))
            .andExpect(jsonPath("$.activePlan.calculator.formula").value("mifflin-st-jeor"))
            .andExpect(jsonPath("$.activePlan.calculator.formulaVersion").value("1"))
            .andExpect(jsonPath("$.activePlan.calculator.maintenanceCalories").value(2600.00))
            .andExpect(jsonPath("$.activePlan.calculator.dailyEnergyDelta").value(-400.00))
            .andExpect(jsonPath("$.activePlan.calculator.expectedWeeklyWeightChangeKg").value(-0.363))
            .andExpect(jsonPath("$.activePlan.calculator.profile.sex").value("FEMALE"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.birthDate").value("1995-04-12"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.heightCm").value(165.00))
            .andExpect(jsonPath("$.activePlan.calculator.profile.currentWeightKg").value(82.000))
            .andExpect(jsonPath("$.activePlan.calculator.profile.targetWeightKg").value(76.500))
            .andExpect(jsonPath("$.activePlan.calculator.profile.dailyMovementLevel").value("MODERATE"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.workoutFrequency").value("THREE_TO_FOUR_DAYS"))
            .andExpect(jsonPath("$.activePlan.calculator.profile.speed").value("BALANCED"))
            .andExpect(jsonPath("$.todayTarget.targets.calories").value(2200.00))
            .andExpect(jsonPath("$.todayTarget.targets.protein").value(140.000))
    }

    @Test
    fun `older calculator snapshot without profile fields still loads`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-legacy-calculator-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        val planId = seedGoal(
            userId = userId,
            startDate = localToday,
            timezone = "Asia/Tehran",
            calories = BigDecimal("2200.00"),
            protein = BigDecimal("140.000"),
            carbs = BigDecimal("220.000"),
            fat = BigDecimal("70.000"),
            fiber = BigDecimal("28.000"),
        )
        jdbcTemplate.update(
            """
            update nutrition_plans
            set calculator_formula = 'mifflin-st-jeor',
                calculator_formula_version = '1',
                maintenance_calories = 2600.00,
                daily_energy_delta = -400.00,
                weekly_weight_change_kg = -0.363
            where id = ?
            """.trimIndent(),
            planId,
        )

        mockMvc.perform(
            get("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.calculator.formula").value("mifflin-st-jeor"))
            .andExpect(jsonPath("$.activePlan.calculator.maintenanceCalories").value(2600.00))
            .andExpect(jsonPath("$.activePlan.calculator.profile").doesNotExist())
    }

    @Test
    fun `put goals replaces an existing plan for the same start date without changing other dates`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-replace-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val localToday = FIXED_INSTANT.atZone(ZoneId.of("Asia/Tehran")).toLocalDate()
        seedGoal(
            userId = userId,
            startDate = localToday.minusDays(10),
            timezone = "Asia/Tehran",
            calories = BigDecimal("1900.00"),
            protein = BigDecimal("120.000"),
            carbs = BigDecimal("180.000"),
            fat = BigDecimal("60.000"),
            fiber = null,
        )

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(localToday, calories = "2300.00", protein = "150.000", carbs = "230.000", fat = "75.000"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activePlan.baseTargets.calories").value(2300.00))

        val planCount = jdbcTemplate.queryForObject(
            "select count(*) from nutrition_plans where user_id = ?",
            Int::class.java,
            userId,
        )
        assertEquals(2, planCount)
    }

    @Test
    fun `put goals rejects macro targets that do not match total calories within tolerance`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-invalid-macros-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        startDate = LocalDate.of(2026, 6, 27),
                        calories = "2200.00",
                        protein = "400.000",
                        carbs = "800.000",
                        fat = "300.000",
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("activePlan.baseTargets"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("MACRO_CALORIES_OUT_OF_RANGE"))
            .andExpect(jsonPath("$.fieldErrors[0].errorMessage").value("Macro calories must be within 25 percent of total calories."))
    }

    @Test
    fun `put goals rejects target dates before the active plan start date with field diagnostics`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-invalid-target-date-${System.nanoTime()}@example.com")
        val startDate = LocalDate.of(2026, 6, 27)

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        startDate = startDate,
                        targetDate = startDate.minusDays(1),
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("goal.targetDate"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("TARGET_DATE_BEFORE_START_DATE"))
            .andExpect(jsonPath("$.fieldErrors[0].errorMessage").value("targetDate must be on or after startDate."))
    }

    @Test
    fun `put goals rejects incomplete target weight objects with field diagnostics`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-incomplete-target-weight-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        startDate = LocalDate.of(2026, 6, 27),
                        targetWeightJson = """{ "value": 76.500 }""",
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("goal.targetWeight.unit"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("NOT_NULL"))
    }

    @Test
    fun `put goals rejects calculator snapshots with blocking safety warnings`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-blocking-warning-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), blockingWarningCodes = """["LOW_TARGET_CALORIES"]"""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `put goals rejects calculator warnings that were not accepted`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-unaccepted-warning-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), warningCodes = """["AGGRESSIVE_WEIGHT_LOSS"]"""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `put goals rejects blank warning codes before persistence`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-blank-warning-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), acceptedWarningCodes = """[""]"""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), blockingWarningCodes = """[" "]"""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), warningCodes = """[""]"""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `put goals cascades validation into calculator formula snapshot`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-formula-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        LocalDate.of(2026, 6, 27),
                        formulaJson = """{ "version": "1" }""",
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `put goals rejects premium schedules while the feature flag is disabled`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-premium-disabled-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(saveGoalJson(LocalDate.of(2026, 6, 27), scheduleType = "WEEKDAY_WEEKEND"))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"))
    }

    @Test
    fun `put goals rejects invalid weekday target keys`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-invalid-weekday-${System.nanoTime()}@example.com")

        mockMvc.perform(
            put("/api/v1/goals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    saveGoalJson(
                        LocalDate.of(2026, 6, 27),
                        scheduleType = "WEEKDAY_WEEKEND",
                        weekdayTargetKey = "Saturday",
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    private fun seedUser(
        id: UUID,
        email: String,
    ) {
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

    private fun seedAdvancedSubscription(userId: UUID) {
        val advancedPlanId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("Expected seeded ADVANCED plan")
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end, cancel_at_period_end
            )
            values (?, ?, 'ACTIVE', now() - interval '1 day', now() + interval '30 days', false)
            """.trimIndent(),
            userId,
            advancedPlanId,
        )
    }

    private fun seedProfile(
        userId: UUID,
        timezone: String,
    ) {
        jdbcTemplate.update(
            """
            insert into user_profiles (user_id, timezone, locale, created_at, updated_at)
            values (?, ?, 'fa-IR', now(), now())
            """.trimIndent(),
            userId,
            timezone,
        )
    }

    private fun seedGoal(
        userId: UUID,
        startDate: LocalDate,
        timezone: String,
        calories: BigDecimal,
        protein: BigDecimal,
        carbs: BigDecimal,
        fat: BigDecimal,
        fiber: BigDecimal?,
        targetWeight: BigDecimal? = null,
        targetWeightUnit: String? = null,
        targetDate: LocalDate? = null,
    ): UUID {
        val goalId = jdbcTemplate.queryForObject(
            """
            insert into nutrition_plans (
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
                target_date,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
            startDate,
            timezone,
            calories,
            protein,
            carbs,
            fat,
            fiber,
            targetWeight,
            targetWeightUnit,
            targetDate,
        ) ?: error("Expected inserted nutrition plan id")

        seedSchedule(
            userId = userId,
            nutritionPlanId = goalId,
            scheduleType = "FLAT",
            activeFrom = startDate,
            activeTo = null,
        )

        return goalId
    }

    private fun seedSchedule(
        userId: UUID,
        nutritionPlanId: UUID,
        scheduleType: String,
        activeFrom: LocalDate,
        activeTo: LocalDate?,
        weekdayTargetsJson: String = "{}",
        dateOverridesJson: String = "{}",
        scheduleSnapshotJson: String = """{"seeded":true}""",
    ) {
        jdbcTemplate.update(
            """
            insert into plan_schedules (
                user_id,
                nutrition_plan_id,
                schedule_type,
                active_from,
                active_to,
                weekday_targets,
                date_overrides,
                macro_adjustment_mode,
                schedule_snapshot,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), 'FIXED_GRAMS', cast(? as jsonb), now(), now())
            on conflict (nutrition_plan_id) do update
            set schedule_type = excluded.schedule_type,
                active_from = excluded.active_from,
                active_to = excluded.active_to,
                weekday_targets = excluded.weekday_targets,
                date_overrides = excluded.date_overrides,
                macro_adjustment_mode = excluded.macro_adjustment_mode,
                schedule_snapshot = excluded.schedule_snapshot,
                updated_at = now()
            """.trimIndent(),
            userId,
            nutritionPlanId,
            scheduleType,
            activeFrom,
            activeTo,
            weekdayTargetsJson,
            dateOverridesJson,
            scheduleSnapshotJson,
        )
    }

    private fun saveGoalJson(
        startDate: LocalDate,
        calories: String = "2200.00",
        protein: String = "140.000",
        carbs: String = "220.000",
        fat: String = "70.000",
        targetWeightJson: String = """{ "value": 76.500, "unit": "KG" }""",
        targetDate: LocalDate = startDate.plusDays(90),
        scheduleType: String = "FLAT",
        weekdayTargetKey: String = "SATURDAY",
        formulaJson: String = """{ "name": "mifflin-st-jeor", "version": "1" }""",
        warningCodes: String = "[]",
        acceptedWarningCodes: String = "[]",
        blockingWarningCodes: String = "[]",
        includeCalculator: Boolean = true,
        calculatorUpdateMode: String? = null,
    ): String {
        val scheduleRules = if (scheduleType == "FLAT") {
            ""
        } else {
            """,
              "weekdayTargets": {
                "$weekdayTargetKey": {
                  "calories": 2300.00,
                  "protein": 145.000,
                  "carbs": 230.000,
                  "fat": 74.000,
                  "fiber": 30.000
                }
              }
            """.trimIndent()
        }
        val calculatorFragment = if (includeCalculator) {
            """
                ,"calculator": {
                  "formula": $formulaJson,
                  "maintenanceCalories": 2600.00,
                  "targetCalories": $calories,
                  "activityFactor": 1.550,
                  "dailyEnergyDelta": -400.00,
                  "weeklyWeightChangeKg": -0.363,
                  "timeline": {
                    "estimatedWeeksMin": 10,
                    "estimatedWeeksMax": 16,
                    "estimatedTargetDate": "$targetDate"
                  },
                  "warningCodes": $warningCodes,
                  "profile": {
                    "sex": "FEMALE",
                    "birthDate": "1995-04-12",
                    "heightCm": 165,
                    "currentWeightKg": 82,
                    "targetWeightKg": 76.5,
                    "dailyMovementLevel": "MODERATE",
                    "workoutFrequency": "THREE_TO_FOUR_DAYS"
                  }
                }
            """.trimIndent()
        } else {
            ""
        }
        val calculatorModeFragment = calculatorUpdateMode?.let {
            ",\"calculatorUpdateMode\": \"$it\""
        } ?: ""
        return """
            {
              "goal": {
                "type": "LOSE_WEIGHT",
                "targetWeight": $targetWeightJson,
                "targetDate": "$targetDate"
              },
              "activePlan": {
                "startDate": "$startDate",
                "baseTargets": {
                  "calories": $calories,
                  "protein": $protein,
                  "carbs": $carbs,
                  "fat": $fat,
                  "fiber": 28.000
                },
                "schedule": {
                  "type": "$scheduleType"
                  $scheduleRules
                }
                $calculatorFragment
                $calculatorModeFragment
              },
              "acceptedWarningCodes": $acceptedWarningCodes,
              "blockingWarningCodes": $blockingWarningCodes
            }
        """.trimIndent()
    }

    private fun maintainWeightGoalJson(startDate: LocalDate): String =
        """
            {
              "goal": {
                "type": "MAINTAIN_WEIGHT",
                "targetWeight": null,
                "targetDate": null
              },
              "activePlan": {
                "startDate": "$startDate",
                "baseTargets": {
                  "calories": 2750,
                  "protein": 144,
                  "carbs": 371.6,
                  "fat": 76.4,
                  "fiber": null
                },
                "schedule": {
                  "type": "FLAT",
                  "activeTo": null
                },
                "calculator": {
                  "formula": {
                    "name": "mifflin-st-jeor",
                    "version": "1"
                  },
                  "maintenanceCalories": 2750,
                  "targetCalories": 2750,
                  "activityFactor": 1.55,
                  "dailyEnergyDelta": 0,
                  "weeklyWeightChangeKg": 0,
                  "timeline": {
                    "estimatedWeeksMin": 0,
                    "estimatedWeeksMax": 0,
                    "estimatedTargetDate": null
                  },
                  "warningCodes": [],
                  "profile": {
                    "sex": "MALE",
                    "birthDate": "1990-06-15",
                    "heightCm": 180,
                    "currentWeightKg": 80,
                    "targetWeightKg": null,
                    "dailyMovementLevel": "MODERATE",
                    "workoutFrequency": "THREE_TO_FOUR_DAYS"
                  }
                }
              },
              "acceptedWarningCodes": [],
              "blockingWarningCodes": []
            }
        """.trimIndent()

    private fun nutritionPlanCount(userId: UUID): Int {
        return jdbcTemplate.queryForObject(
            "select count(*) from nutrition_plans where user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0
    }

    private fun caloriesFor(
        userId: UUID,
        startDate: LocalDate,
    ): String {
        return jdbcTemplate.queryForObject(
            "select calories from nutrition_plans where user_id = ? and start_date = ?",
            String::class.java,
            userId,
            startDate,
        ) ?: error("Expected nutrition plan")
    }

    private fun rowCountById(
        tableName: String,
        id: UUID,
        idColumn: String = "id",
    ): Int {
        return jdbcTemplate.queryForObject(
            "select count(*) from $tableName where $idColumn = ?",
            Int::class.java,
            id,
        ) ?: 0
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        @Primary
        fun fixedClock(): Clock {
            return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
        }
    }

    companion object {
        private val FIXED_INSTANT: Instant = Instant.parse("2026-06-27T01:30:00Z")
    }
}
