package com.gyro.api.weight

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.weight.domain.WeightEntry
import com.gyro.api.weight.domain.WeightEntrySource
import com.gyro.api.weight.domain.WeightUnit
import com.gyro.api.weight.infrastructure.WeightEntryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class WeightEntryPersistenceIntegrationTest(
    @Autowired private val weightEntryRepository: WeightEntryRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `weight entry persists canonical and display values`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-${System.nanoTime()}@example.com")

        val entry = weightEntryRepository.saveAndFlush(
            WeightEntry(
                userId = userId,
                recordedDate = LocalDate.of(2026, 6, 25),
                recordedAt = Instant.parse("2026-06-25T04:30:00Z"),
                weightKg = BigDecimal("78.400"),
                displayWeight = BigDecimal("78.400"),
                displayUnit = WeightUnit.KG,
                source = WeightEntrySource.MANUAL,
                notes = "Morning weigh-in",
                createdAt = Instant.parse("2026-06-25T04:30:00Z"),
                updatedAt = Instant.parse("2026-06-25T04:30:00Z"),
            )
        )

        assertNotNull(entry.id)

        val persisted = requireNotNull(
            weightEntryRepository.findByUserIdAndRecordedDate(
                userId = userId,
                recordedDate = LocalDate.of(2026, 6, 25),
            )
        )
        assertEquals("78.400", persisted.weightKg.toPlainString())
        assertEquals("78.400", persisted.displayWeight.toPlainString())
        assertEquals(WeightUnit.KG, persisted.displayUnit)
        assertEquals(WeightEntrySource.MANUAL, persisted.source)
        assertEquals("Morning weigh-in", persisted.notes)
    }

    @Test
    fun `weight entry enforces one entry per user local date`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-unique-${System.nanoTime()}@example.com")
        insertWeightEntry(userId = userId, recordedDate = "2026-06-25")

        assertThrows(DataIntegrityViolationException::class.java) {
            insertWeightEntry(userId = userId, recordedDate = "2026-06-25")
        }
    }

    @Test
    fun `weight entry schema rejects invalid units sources ranges and long notes`() {
        val invalidUnitUserId = UUID.randomUUID()
        seedUser(invalidUnitUserId, "weight-unit-${System.nanoTime()}@example.com")
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into weight_entries (
                    user_id,
                    recorded_date,
                    weight_kg,
                    display_weight,
                    display_unit,
                    source
                )
                values (?, '2026-06-25', 78.400, 78.400, 'STONE', 'MANUAL')
                """.trimIndent(),
                invalidUnitUserId,
            )
        }

        val invalidRangeUserId = UUID.randomUUID()
        seedUser(invalidRangeUserId, "weight-range-${System.nanoTime()}@example.com")
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into weight_entries (
                    user_id,
                    recorded_date,
                    weight_kg,
                    display_weight,
                    display_unit,
                    source
                )
                values (?, '2026-06-25', 0, 0, 'KG', 'MANUAL')
                """.trimIndent(),
                invalidRangeUserId,
            )
        }

        val invalidSourceUserId = UUID.randomUUID()
        seedUser(invalidSourceUserId, "weight-source-${System.nanoTime()}@example.com")
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                insert into weight_entries (
                    user_id,
                    recorded_date,
                    weight_kg,
                    display_weight,
                    display_unit,
                    source,
                    notes
                )
                values (?, '2026-06-25', 78.400, 78.400, 'KG', 'DEVICE', ?)
                """.trimIndent(),
                invalidSourceUserId,
                "x".repeat(2001),
            )
        }
    }

    private fun insertWeightEntry(
        userId: UUID,
        recordedDate: String,
    ) {
        jdbcTemplate.update(
            """
            insert into weight_entries (
                user_id,
                recorded_date,
                weight_kg,
                display_weight,
                display_unit,
                source
            )
            values (?, ?::date, 78.400, 78.400, 'KG', 'MANUAL')
            """.trimIndent(),
            userId,
            recordedDate,
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
}
