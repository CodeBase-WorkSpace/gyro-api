package com.gyro.api.goal

import com.gyro.api.goal.application.calculator.*
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class GoalCalculatorServiceTest {
    private val previewService = GoalPreviewService(
        inputNormalizer = GoalProfileInputNormalizer(),
        rmrCalculator = RmrCalculator(MifflinStJeorFormula()),
        activityCalculator = ActivityCalculator(),
        tdeeCalculator = TdeeCalculator(),
        goalCalculator = GoalCalculator(),
        timelineCalculator = TimelineCalculator(),
        macroCalculator = MacroCalculator(),
        goalSafetyValidator = GoalSafetyValidator(),
    )

    @Test
    fun `preview uses mifflin st jeor activity factors and balanced weekly loss`() {
        val result = previewService.preview(
            input = baseInput(),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        assertEquals("MIFFLIN_ST_JEOR", result.formula.name)
        assertEquals("1", result.formula.version)
        assertEquals(BigDecimal("2581.00"), result.maintenanceCalories)
        assertEquals(BigDecimal("1.450"), result.activityFactor)
        assertEquals(BigDecimal("-660.00"), result.dailyEnergyDelta)
        assertEquals(BigDecimal("-0.600"), result.weeklyWeightChangeKg)
        assertEquals(BigDecimal("1921.00"), result.targetCalories)
        assertEquals(BigDecimal("144.000"), result.macros.proteinGrams)
        assertEquals(BigDecimal("216.188"), result.macros.carbsGrams)
        assertEquals(BigDecimal("53.361"), result.macros.fatGrams)
        assertEquals(15, result.timeline.estimatedWeeksMax)
        assertEquals(LocalDate.of(2026, 10, 8), result.timeline.estimatedTargetDate)
    }

    @Test
    fun `maintain weight returns maintenance target and zero energy delta`() {
        val result = previewService.preview(
            input = baseInput().copy(
                targetWeightKg = null,
                goalType = GoalType.MAINTAIN_WEIGHT,
                speed = GoalChangeSpeed.AGGRESSIVE,
            ),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        assertEquals(result.maintenanceCalories, result.targetCalories)
        assertEquals(BigDecimal("0.00"), result.dailyEnergyDelta)
        assertEquals(BigDecimal("0.000"), result.weeklyWeightChangeKg)
        assertEquals(0, result.timeline.estimatedWeeksMax)
    }

    @Test
    fun `preview is deterministic for the same inputs formula version and date`() {
        val first = previewService.preview(baseInput(), LocalDate.of(2026, 6, 25))
        val second = previewService.preview(baseInput(), LocalDate.of(2026, 6, 25))

        assertEquals(first, second)
    }

    @Test
    fun `safety rules block under age users`() {
        val result = previewService.preview(
            input = baseInput().copy(
                birthDate = LocalDate.of(2011, 6, 25),
            ),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        val warning = result.warning(GoalSafetyWarningCode.UNDER_18)
        assertTrue(warning.blocking)
    }

    @Test
    fun `safety rules block weight loss when current or target bmi is below healthy range`() {
        val result = previewService.preview(
            input = baseInput().copy(
                heightCm = BigDecimal("180"),
                currentWeightKg = BigDecimal("52"),
                targetWeightKg = BigDecimal("50"),
                speed = GoalChangeSpeed.CONSERVATIVE,
            ),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        assertTrue(result.warning(GoalSafetyWarningCode.LOW_CURRENT_BMI).blocking)
        assertTrue(result.warning(GoalSafetyWarningCode.LOW_TARGET_BMI).blocking)
    }

    @Test
    fun `safety rules block target calories below configured thresholds`() {
        val result = previewService.preview(
            input = baseInput().copy(
                sex = GoalCalculatorSex.FEMALE,
                heightCm = BigDecimal("165"),
                currentWeightKg = BigDecimal("60"),
                targetWeightKg = BigDecimal("54"),
                dailyMovementLevel = DailyMovementLevel.SEDENTARY,
                workoutFrequency = WorkoutFrequency.ZERO_DAYS,
                speed = GoalChangeSpeed.AGGRESSIVE,
            ),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        assertTrue(result.warning(GoalSafetyWarningCode.LOW_TARGET_CALORIES).blocking)
        assertFalse(result.warning(GoalSafetyWarningCode.AGGRESSIVE_WEIGHT_LOSS).blocking)
    }

    @Test
    fun `safety rules warn for unrealistic target weight change without blocking`() {
        val result = previewService.preview(
            input = baseInput().copy(
                currentWeightKg = BigDecimal("100"),
                targetWeightKg = BigDecimal("60"),
                heightCm = BigDecimal("170"),
                speed = GoalChangeSpeed.CONSERVATIVE,
            ),
            calculationDate = LocalDate.of(2026, 6, 25),
        )

        val warning = result.warning(GoalSafetyWarningCode.UNREALISTIC_TARGET_WEIGHT_CHANGE)
        assertFalse(warning.blocking)
    }

    private fun baseInput(): GoalPreviewInput {
        return GoalPreviewInput(
            sex = GoalCalculatorSex.MALE,
            birthDate = LocalDate.of(1996, 6, 25),
            heightCm = BigDecimal("180"),
            currentWeightKg = BigDecimal("80"),
            targetWeightKg = BigDecimal("72"),
            dailyMovementLevel = DailyMovementLevel.LIGHT,
            workoutFrequency = WorkoutFrequency.THREE_TO_FOUR_DAYS,
            goalType = GoalType.LOSE_WEIGHT,
            speed = GoalChangeSpeed.BALANCED,
        )
    }

    private fun com.gyro.api.goal.application.calculator.GoalPreviewResult.warning(
        code: GoalSafetyWarningCode,
    ) = warnings.first { it.code == code }
}
