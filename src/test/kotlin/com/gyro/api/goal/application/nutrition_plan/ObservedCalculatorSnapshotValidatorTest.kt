package com.gyro.api.goal.application.nutrition_plan

import com.gyro.api.common.error.FieldValidationException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.goal.application.calculator.ActivityCalculator
import com.gyro.api.goal.application.calculator.GoalCalculator
import com.gyro.api.goal.application.calculator.GoalChangeSpeed
import com.gyro.api.goal.application.calculator.GoalPreviewInput
import com.gyro.api.goal.application.calculator.GoalPreviewService
import com.gyro.api.goal.application.calculator.GoalProfileInputNormalizer
import com.gyro.api.goal.application.calculator.GoalSafetyValidator
import com.gyro.api.goal.application.calculator.MacroCalculator
import com.gyro.api.goal.application.calculator.MifflinStJeorFormula
import com.gyro.api.goal.application.calculator.RmrCalculator
import com.gyro.api.goal.application.calculator.TdeeCalculator
import com.gyro.api.goal.application.calculator.TimelineCalculator
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysis
import com.gyro.api.goal.application.recalibration.ObservedEnergyAnalysisService
import com.gyro.api.goal.application.recalibration.ObservedEnergyEvidenceStatus
import com.gyro.api.goal.application.recalibration.RecalibrationConfidence
import com.gyro.api.goal.domain.CalculatorMaintenanceSource
import com.gyro.api.goal.domain.DailyMovementLevel
import com.gyro.api.goal.domain.GoalCalculatorSex
import com.gyro.api.goal.domain.GoalType
import com.gyro.api.goal.domain.WorkoutFrequency
import com.gyro.api.subscription.application.EntitlementGateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ObservedCalculatorSnapshotValidatorTest {
    private val userId = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Tehran")
    private val calculationDate = LocalDate.parse("2026-08-01")
    private val observedEnergy = Mockito.mock(ObservedEnergyAnalysisService::class.java)
    private val entitlement = Mockito.mock(EntitlementGateService::class.java)
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
    private val validator = ObservedCalculatorSnapshotValidator(
        observedEnergyAnalysisService = observedEnergy,
        entitlementGateService = entitlement,
        timeProvider = TimeProvider(
            Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), zone),
        ),
        goalPreviewService = previewService,
    )

    @BeforeEach
    fun setUp() {
        Mockito.`when`(entitlement.hasFeatureAccess(userId, "goal_recalibration")).thenReturn(true)
        Mockito.`when`(observedEnergy.analyze(userId, calculationDate.minusDays(1), null))
            .thenReturn(evidence())
    }

    @Test
    fun `observed snapshots are replaced with authoritative calculator output`() {
        val supplied = validSnapshot().copy(
            observationBasis = mapOf("windowEnd" to calculationDate.minusDays(1).toString(), "client" to "ignored"),
        )

        val validated = validator.validate(
            userId = userId,
            timezone = zone.id,
            goalType = GoalType.MAINTAIN_WEIGHT,
            targetWeightKg = null,
            targetDate = null,
            snapshot = supplied,
        )

        assertEquals(evidence().auditBasis(), validated?.observationBasis)
        assertEquals("MIFFLIN_ST_JEOR", validated?.formula)
        assertEquals(BigDecimal("2750.00"), validated?.formulaMaintenanceCalories)
        assertEquals(BigDecimal("2825.00"), validated?.targetCalories)
    }

    @Test
    fun `observed snapshots reject client controlled calculator derivations`() {
        val valid = validSnapshot()
        val mutations = listOf(
            "formula maintenance" to valid.copy(formulaMaintenanceCalories = BigDecimal("4000")),
            "daily energy delta" to valid.copy(dailyEnergyDelta = BigDecimal("-500")),
            "activity factor" to valid.copy(activityFactor = BigDecimal("1.900")),
            "formula name" to valid.copy(formula = "CLIENT_FORMULA"),
            "formula version" to valid.copy(formulaVersion = "999"),
            "profile weight" to valid.copy(
                profile = valid.profile?.copy(currentWeightKg = BigDecimal("90")),
            ),
            "profile height" to valid.copy(
                profile = valid.profile?.copy(heightCm = BigDecimal("190")),
            ),
            "profile activity" to valid.copy(
                profile = valid.profile?.copy(dailyMovementLevel = DailyMovementLevel.VERY_ACTIVE),
            ),
            "protein recommendation" to valid.copy(recommendedProtein = BigDecimal("200")),
            "carbohydrate recommendation" to valid.copy(recommendedCarbs = BigDecimal("500")),
            "fat recommendation" to valid.copy(recommendedFat = BigDecimal("120")),
        )

        mutations.forEach { (label, mutated) ->
            val error = assertThrows(FieldValidationException::class.java, {
                validator.validate(
                    userId = userId,
                    timezone = zone.id,
                    goalType = GoalType.MAINTAIN_WEIGHT,
                    targetWeightKg = null,
                    targetDate = null,
                    snapshot = mutated,
                )
            }, label)
            assertTrue(
                error.fieldErrors.single().code in setOf(
                    "OBSERVED_CALIBRATION_MISMATCH",
                    "OBSERVED_CALIBRATION_NOT_ACTIONABLE",
                ),
                label,
            )
        }
    }

    @Test
    fun `observed snapshots reject an actual target weight mismatch`() {
        val supplied = validSnapshot().let { snapshot ->
            snapshot.copy(
                profile = requireNotNull(snapshot.profile).copy(
                    targetWeightKg = BigDecimal("74.843"),
                ),
            )
        }

        val error = assertThrows(FieldValidationException::class.java) {
            validator.validate(
                userId = userId,
                timezone = zone.id,
                goalType = GoalType.LOSE_WEIGHT,
                targetWeightKg = BigDecimal("75.000"),
                targetDate = null,
                snapshot = supplied,
            )
        }

        assertEquals("OBSERVED_CALCULATOR_PROFILE_MISMATCH", error.fieldErrors.single().code)
    }

    private fun validSnapshot(): NutritionPlanCalculatorSnapshot {
        val profile = profile()
        val input = GoalPreviewInput(
            sex = profile.sex,
            birthDate = profile.birthDate,
            heightCm = profile.heightCm,
            currentWeightKg = profile.currentWeightKg,
            targetWeightKg = profile.targetWeightKg,
            dailyMovementLevel = profile.dailyMovementLevel,
            workoutFrequency = profile.workoutFrequency,
            goalType = GoalType.MAINTAIN_WEIGHT,
            speed = requireNotNull(profile.speed),
        )
        val formula = previewService.preview(input, calculationDate)
        val target = formula.targetCalories.add(requireNotNull(evidence().confidence).maxAdjustment)
        val calibrated = previewService.preview(
            input = input,
            calculationDate = calculationDate,
            maintenanceOverride = target.subtract(formula.dailyEnergyDelta),
        )
        return NutritionPlanCalculatorSnapshot(
            formula = formula.formula.name,
            formulaVersion = formula.formula.version,
            maintenanceCalories = calibrated.maintenanceCalories,
            targetCalories = calibrated.targetCalories,
            activityFactor = formula.activityFactor,
            dailyEnergyDelta = formula.dailyEnergyDelta,
            weeklyWeightChangeKg = formula.weeklyWeightChangeKg,
            estimatedWeeksMin = calibrated.timeline.estimatedWeeksMin,
            estimatedWeeksMax = calibrated.timeline.estimatedWeeksMax,
            estimatedTargetDate = calibrated.timeline.estimatedTargetDate,
            recommendedProtein = calibrated.macros.proteinGrams,
            recommendedCarbs = calibrated.macros.carbsGrams,
            recommendedFat = calibrated.macros.fatGrams,
            recommendedFiber = BigDecimal("28"),
            profile = profile,
            maintenanceSource = CalculatorMaintenanceSource.OBSERVED,
            formulaMaintenanceCalories = formula.maintenanceCalories,
            observationBasis = evidence().auditBasis(),
        )
    }

    private fun profile() = NutritionPlanCalculatorProfile(
        sex = GoalCalculatorSex.MALE,
        birthDate = LocalDate.parse("1990-01-01"),
        heightCm = BigDecimal("175"),
        currentWeightKg = BigDecimal("80"),
        targetWeightKg = null,
        dailyMovementLevel = DailyMovementLevel.MODERATE,
        workoutFrequency = WorkoutFrequency.THREE_TO_FOUR_DAYS,
        speed = GoalChangeSpeed.BALANCED,
    )

    private fun evidence() = ObservedEnergyAnalysis(
        status = ObservedEnergyEvidenceStatus.SUFFICIENT,
        windowDays = 14,
        windowStart = calculationDate.minusDays(14),
        windowEnd = calculationDate.minusDays(1),
        loggedDays = 14,
        loggedDaysRequired = 7,
        recentLoggedDays = 7,
        weighInDays = 4,
        weightSpanDays = 13,
        averageLoggedCalories = BigDecimal("3200"),
        observedKgPerWeek = BigDecimal.ZERO,
        estimatedTdee = BigDecimal("3200"),
        confidence = RecalibrationConfidence.LOW,
        trendRSquared = BigDecimal.ONE,
        trendStdErrorKgPerDay = BigDecimal.ZERO,
    )
}
