package com.gyro.api.weight

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.*

@Import(
    TestcontainersConfiguration::class,
    WeightEntryControllerIntegrationTest.FixedClockConfiguration::class,
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class WeightEntryControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `post weight entries saves canonical and display values for the authenticated user`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-post-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")

        mockMvc.perform(
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "recordedAt": "2026-06-28T05:00:00Z",
                      "weight": 180.000,
                      "unit": "LB",
                      "source": "MANUAL",
                      "notes": " Morning weigh-in "
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isString)
            .andExpect(jsonPath("$.recordedDate").value("2026-06-28"))
            .andExpect(jsonPath("$.recordedAt").value("2026-06-28T05:00:00Z"))
            .andExpect(jsonPath("$.weightKg").value(81.647))
            .andExpect(jsonPath("$.displayWeight").value(180.000))
            .andExpect(jsonPath("$.displayUnit").value("LB"))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.notes").value("Morning weigh-in"))
            .andExpect(jsonPath("$.createdAt").value(FIXED_INSTANT.toString()))
            .andExpect(jsonPath("$.updatedAt").value(FIXED_INSTANT.toString()))

        val persistedOwner = jdbcTemplate.queryForObject(
            "select user_id from weight_entries where user_id = ? and recorded_date = '2026-06-28'",
            UUID::class.java,
            userId,
        )
        assertEquals(userId, persistedOwner)
    }

    @Test
    fun `post weight entries replaces the authenticated user's same-date entry only`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "weight-replace-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "weight-replace-other-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-28", "78.400")
        seedWeightEntry(otherUserId, "2026-06-28", "90.000")

        mockMvc.perform(
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 79.125,
                      "unit": "KG",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recordedDate").value("2026-06-28"))
            .andExpect(jsonPath("$.weightKg").value(79.125))
            .andExpect(jsonPath("$.displayWeight").value(79.125))
            .andExpect(jsonPath("$.displayUnit").value("KG"))

        assertEquals(1, weightEntryCount(userId))
        assertEquals(1, weightEntryCount(otherUserId))
        assertEquals("79.125", weightKg(userId, "2026-06-28"))
        assertEquals("90.000", weightKg(otherUserId, "2026-06-28"))
    }

    @Test
    fun `post weight entries replays an idempotent response for the same request`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-idempotent-${System.nanoTime()}@example.com")
        val idempotencyKey = "weight-${userId}"

        val requestBuilder = {
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 78.400,
                      "unit": "KG",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        }

        mockMvc.perform(requestBuilder())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isString)

        mockMvc.perform(requestBuilder())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isString)

        assertEquals(1, weightEntryCount(userId))
    }

    @Test
    fun `post weight entries rejects future local dates`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-future-${System.nanoTime()}@example.com")
        seedProfile(userId, "Pacific/Pago_Pago")

        mockMvc.perform(
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 78.400,
                      "unit": "KG",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("recordedDate cannot be in the future."))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("recordedDate"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("FUTURE_DATE"))
    }

    @Test
    fun `post weight entries rejects invalid value and unit`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 1.000,
                      "unit": "KG",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("weight"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("OUT_OF_RANGE"))

        mockMvc.perform(
            post("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 78.400,
                      "unit": "STONE",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("unit"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID"))
    }

    @Test
    fun `post weight entries requires authentication`() {
        mockMvc.perform(
            post("/api/v1/weight-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "recordedDate": "2026-06-28",
                      "weight": 78.400,
                      "unit": "KG",
                      "source": "MANUAL"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `get weight entries returns an owner scoped paginated date range sorted newest first`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(userId, "weight-list-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "weight-list-other-${System.nanoTime()}@example.com")
        seedWeightEntry(userId, "2026-06-26", "78.900", notes = "older")
        seedWeightEntry(userId, "2026-06-27", "78.700", notes = "middle")
        seedWeightEntry(userId, "2026-06-28", "78.400", notes = "newest")
        seedWeightEntry(otherUserId, "2026-06-28", "91.000", notes = "other user")

        mockMvc.perform(
            get("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-30")
                .param("page", "0")
                .param("size", "2")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].recordedDate").value("2026-06-28"))
            .andExpect(jsonPath("$.items[0].weightKg").value(78.400))
            .andExpect(jsonPath("$.items[0].notes").value("newest"))
            .andExpect(jsonPath("$.items[1].recordedDate").value("2026-06-27"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))

        mockMvc.perform(
            get("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-30")
                .param("page", "2")
                .param("size", "2")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.totalItems").value(3))
    }

    @Test
    fun `get weight entries rejects invalid ranges and page sizes`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-list-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-30")
                .param("to", "2026-06-01")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2025-01-01")
                .param("to", "2026-06-30")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            get("/api/v1/weight-entries")
                .with(authentication(testAuthentication(userId)))
                .param("from", "2026-06-01")
                .param("to", "2026-06-30")
                .param("size", "51")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `post weight entries batch saves all entries for the authenticated user`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-batch-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "weight-batch-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entries": [
                        {
                          "clientEntryId": "import-2026-06-27",
                          "recordedDate": "2026-06-27",
                          "recordedAt": "2026-06-27T05:00:00Z",
                          "weight": 78.700,
                          "unit": "KG",
                          "source": "IMPORT"
                        },
                        {
                          "clientEntryId": "import-2026-06-28",
                          "recordedDate": "2026-06-28",
                          "weight": 173.000,
                          "unit": "LB",
                          "source": "IMPORT",
                          "notes": "Imported from CSV"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted.length()").value(2))
            .andExpect(jsonPath("$.accepted[0].clientEntryId").value("import-2026-06-27"))
            .andExpect(jsonPath("$.accepted[0].entry.recordedDate").value("2026-06-27"))
            .andExpect(jsonPath("$.accepted[0].entry.source").value("IMPORT"))
            .andExpect(jsonPath("$.accepted[1].entry.recordedDate").value("2026-06-28"))
            .andExpect(jsonPath("$.accepted[1].entry.weightKg").value(78.471))
            .andExpect(jsonPath("$.accepted[1].entry.displayUnit").value("LB"))
            .andExpect(jsonPath("$.accepted[1].entry.notes").value("Imported from CSV"))
            .andExpect(jsonPath("$.diagnostics.length()").value(0))

        assertEquals(2, weightEntryCount(userId))
        assertEquals("78.700", weightKg(userId, "2026-06-27"))
        assertEquals("78.471", weightKg(userId, "2026-06-28"))
    }

    @Test
    fun `post weight entries batch returns diagnostics and writes nothing when any row is invalid`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-batch-invalid-${System.nanoTime()}@example.com")
        seedProfile(userId, "Asia/Tehran")
        val idempotencyKey = "weight-batch-invalid-${userId}"
        val requestBody =
            """
            {
              "entries": [
                {
                  "clientEntryId": "valid-row",
                  "recordedDate": "2026-06-27",
                  "weight": 78.700,
                  "unit": "KG",
                  "source": "IMPORT"
                },
                {
                  "clientEntryId": "future-date",
                  "recordedDate": "2026-06-29",
                  "weight": 78.700,
                  "unit": "KG",
                  "source": "IMPORT"
                },
                {
                  "clientEntryId": "missing-weight",
                  "recordedDate": "2026-06-28",
                  "unit": "KG",
                  "source": "IMPORT"
                },
                {
                  "clientEntryId": "duplicate-date",
                  "recordedDate": "2026-06-27",
                  "weight": 79.100,
                  "unit": "KG",
                  "source": "IMPORT"
                },
                {
                  "clientEntryId": "zero-weight",
                  "recordedDate": "2026-06-26",
                  "weight": 0,
                  "unit": "KG",
                  "source": "IMPORT"
                }
              ]
            }
            """.trimIndent()

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.accepted.length()").value(0))
            .andExpect(jsonPath("$.diagnostics.length()").value(4))
            .andExpect(jsonPath("$.diagnostics[0].index").value(1))
            .andExpect(jsonPath("$.diagnostics[0].clientEntryId").value("future-date"))
            .andExpect(jsonPath("$.diagnostics[0].field").value("recordedDate"))
            .andExpect(jsonPath("$.diagnostics[0].code").value("FUTURE_DATE"))
            .andExpect(jsonPath("$.diagnostics[1].index").value(2))
            .andExpect(jsonPath("$.diagnostics[1].clientEntryId").value("missing-weight"))
            .andExpect(jsonPath("$.diagnostics[1].field").value("weight"))
            .andExpect(jsonPath("$.diagnostics[1].code").value("REQUIRED"))
            .andExpect(jsonPath("$.diagnostics[2].index").value(3))
            .andExpect(jsonPath("$.diagnostics[2].clientEntryId").value("duplicate-date"))
            .andExpect(jsonPath("$.diagnostics[2].field").value("recordedDate"))
            .andExpect(jsonPath("$.diagnostics[2].code").value("DUPLICATE_RECORDED_DATE"))
            .andExpect(jsonPath("$.diagnostics[3].index").value(4))
            .andExpect(jsonPath("$.diagnostics[3].clientEntryId").value("zero-weight"))
            .andExpect(jsonPath("$.diagnostics[3].field").value("weight"))
            .andExpect(jsonPath("$.diagnostics[3].code").value("INVALID_WEIGHT_ENTRY"))

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.accepted.length()").value(0))
            .andExpect(jsonPath("$.diagnostics.length()").value(4))

        assertEquals(0, weightEntryCount(userId))
    }

    @Test
    fun `post weight entries batch rejects oversized batches`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-batch-size-${System.nanoTime()}@example.com")
        val entries = (1..101).joinToString(separator = ",") { offset ->
            val day = "%02d".format((offset % 28) + 1)
            """
            {
              "clientEntryId": "entry-$offset",
              "recordedDate": "2026-03-$day",
              "weight": 78.700,
              "unit": "KG",
              "source": "IMPORT"
            }
            """.trimIndent()
        }

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"entries": [$entries]}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        assertEquals(0, weightEntryCount(userId))
    }

    @Test
    fun `post weight entries batch replays same idempotent request and rejects mismatched body`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "weight-batch-idempotent-${System.nanoTime()}@example.com")
        val idempotencyKey = "weight-batch-${userId}"
        val originalBody = """
            {
              "entries": [
                {
                  "clientEntryId": "import-2026-06-28",
                  "recordedDate": "2026-06-28",
                  "weight": 78.700,
                  "unit": "KG",
                  "source": "IMPORT"
                }
              ]
            }
        """.trimIndent()
        val changedBody = originalBody.replace("78.700", "79.000")

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(originalBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted.length()").value(1))

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(originalBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted.length()").value(1))

        mockMvc.perform(
            post("/api/v1/weight-entries/batch")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(changedBody)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))

        assertEquals(1, weightEntryCount(userId))
        assertEquals("78.700", weightKg(userId, "2026-06-28"))
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

    private fun seedWeightEntry(
        userId: UUID,
        recordedDate: String,
        weightKg: String,
        notes: String? = null,
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
                notes,
                created_at,
                updated_at
            )
            values (?, ?::date, ?, ?::numeric, ?::numeric, 'KG', 'MANUAL', ?, ?, ?)
            """.trimIndent(),
            userId,
            recordedDate,
            Timestamp.from(FIXED_INSTANT),
            weightKg,
            weightKg,
            notes,
            Timestamp.from(FIXED_INSTANT),
            Timestamp.from(FIXED_INSTANT),
        )
    }

    private fun weightEntryCount(userId: UUID): Int {
        return jdbcTemplate.queryForObject(
            "select count(*) from weight_entries where user_id = ?",
            Int::class.java,
            userId,
        ) ?: 0
    }

    private fun weightKg(
        userId: UUID,
        recordedDate: String,
    ): String {
        return jdbcTemplate.queryForObject(
            "select weight_kg from weight_entries where user_id = ? and recorded_date = ?::date",
            String::class.java,
            userId,
            recordedDate,
        ) ?: error("Expected weight entry")
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            "n/a",
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
        private val FIXED_INSTANT = Instant.parse("2026-06-28T10:00:00Z")
    }
}
