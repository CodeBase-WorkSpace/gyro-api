package com.gyro.api.health

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanService
import com.gyro.api.goal.application.nutrition_plan.SaveNutritionPlanCommand
import com.gyro.api.goal.domain.GoalScheduleType
import com.gyro.api.progress.application.ProgressDateRange
import com.gyro.api.progress.application.ProgressReadService
import com.gyro.api.progress.application.WeightTrendDirection
import com.gyro.api.weight.application.SaveWeightEntryCommand
import com.gyro.api.weight.application.WeightEntryService
import com.gyro.api.weight.domain.WeightUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class GoalWeightProgressDomainBoundaryIntegrationTest(
    @Autowired private val nutritionPlanService: NutritionPlanService,
    @Autowired private val weightEntryService: WeightEntryService,
    @Autowired private val progressReadService: ProgressReadService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `nutrition plan service owns persistence and returns a read model with a flat daily target`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "goal-boundary-${System.nanoTime()}@example.com")
        val startDate = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(7)

        val saved = nutritionPlanService.savePlan(
            userId = userId,
            command = SaveNutritionPlanCommand(
                startDate = startDate,
                calories = BigDecimal("2200"),
                protein = BigDecimal("140"),
                carbs = BigDecimal("220"),
                fat = BigDecimal("70"),
                fiber = BigDecimal("30"),
            ),
        )

        assertNotNull(saved.id)
        assertEquals(BigDecimal("2200.00"), saved.calories)
        assertEquals(BigDecimal("140.000"), saved.protein)
        assertEquals(startDate, saved.startDate)
        assertEquals(GoalScheduleType.FLAT, saved.planSchedule?.type)

        val active = nutritionPlanService.findActivePlan(
            userId = userId,
            activeOn = startDate.plusDays(1),
        )
        assertEquals(saved.id, active?.id)
    }

    @Test
    fun `weight service normalizes values and keeps daily measurements owner scoped`() {
        val firstUserId = UUID.randomUUID()
        val secondUserId = UUID.randomUUID()
        seedUser(firstUserId, "weight-boundary-a-${System.nanoTime()}@example.com")
        seedUser(secondUserId, "weight-boundary-b-${System.nanoTime()}@example.com")
        val recordedDate = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(3)

        val firstSave = weightEntryService.saveEntry(
            userId = firstUserId,
            command = SaveWeightEntryCommand(
                recordedDate = recordedDate,
                weight = BigDecimal("80.4"),
                unit = WeightUnit.KG,
            ),
        )
        val replacement = weightEntryService.saveEntry(
            userId = firstUserId,
            command = SaveWeightEntryCommand(
                recordedDate = recordedDate,
                weight = BigDecimal("81.2"),
                unit = WeightUnit.KG,
            ),
        )
        val secondUserSave = weightEntryService.saveEntry(
            userId = secondUserId,
            command = SaveWeightEntryCommand(
                recordedDate = recordedDate,
                weight = BigDecimal("92.5"),
                unit = WeightUnit.KG,
            ),
        )

        assertEquals(firstSave.id, replacement.id)
        assertNotEquals(replacement.id, secondUserSave.id)
        assertEquals(BigDecimal("81.200"), replacement.weightKg)
        assertEquals(BigDecimal("92.500"), secondUserSave.weightKg)
    }

    @Test
    fun `progress reads aggregate only the authenticated user's weight data`() {
        val firstUserId = UUID.randomUUID()
        val secondUserId = UUID.randomUUID()
        seedUser(firstUserId, "progress-boundary-a-${System.nanoTime()}@example.com")
        seedUser(secondUserId, "progress-boundary-b-${System.nanoTime()}@example.com")
        val startDate = LocalDate.now(ZoneId.of("Asia/Tehran")).minusDays(7)
        val endDate = startDate.plusDays(2)

        weightEntryService.saveEntry(firstUserId, SaveWeightEntryCommand(startDate, BigDecimal("80.0"), WeightUnit.KG))
        weightEntryService.saveEntry(firstUserId, SaveWeightEntryCommand(endDate, BigDecimal("78.5"), WeightUnit.KG))
        weightEntryService.saveEntry(secondUserId, SaveWeightEntryCommand(endDate, BigDecimal("95.0"), WeightUnit.KG))

        val progress = progressReadService.weightProgress(
            userId = firstUserId,
            range = ProgressDateRange(startDate, endDate),
        )

        assertEquals(2, progress.measurements.size)
        assertEquals(BigDecimal("-1.500"), progress.absoluteChangeKg)
        assertEquals(WeightTrendDirection.DOWN, progress.trend)
        assertEquals(listOf(BigDecimal("80.000"), BigDecimal("78.500")), progress.measurements.map { it.weightKg })
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
}
