package com.gyro.api.user

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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

@Import(
    TestcontainersConfiguration::class,
    UserAccountDeactivationIntegrationTest.FixedClockConfiguration::class,
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class UserAccountDeactivationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    @Test
    fun `delete current user soft deactivates principal and revokes only their refresh tokens`() {
        val currentUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(currentUserId, "deactivate-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "other-deactivate-${System.nanoTime()}@example.com")
        seedRefreshToken(currentUserId, "current-active-token", null)
        seedRefreshToken(currentUserId, "current-revoked-token", Instant.parse("2026-06-01T00:00:00Z"))
        seedRefreshToken(otherUserId, "other-active-token", null)

        mockMvc.perform(
            delete("/api/v1/users/me")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isNoContent)

        val currentUser = userRow(currentUserId)
        val otherUser = userRow(otherUserId)

        assertEquals("DEACTIVATED", currentUser["status"])
        assertEquals(FIXED_INSTANT, (currentUser["deactivated_at"] as Timestamp).toInstant())
        assertEquals("ACTIVE", otherUser["status"])
        assertEquals(null, otherUser["deactivated_at"])

        assertEquals(FIXED_INSTANT, refreshTokenRevokedAt("current-active-token"))
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), refreshTokenRevokedAt("current-revoked-token"))
        assertEquals(null, refreshTokenRevokedAt("other-active-token"))
    }

    @Test
    fun `delete current user requires authentication`() {
        mockMvc.perform(delete("/api/v1/users/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `deactivated user cannot log in with password`() {
        val userId = UUID.randomUUID()
        val email = "deactivated-login-${System.nanoTime()}@example.com"
        seedUser(userId, email)

        mockMvc.perform(
            delete("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/auth/login/password")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "identifier": "$email",
                      "password": "Password123"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
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
            values (?, ?, ?, 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
            passwordEncoder.encode("Password123"),
        )
    }

    private fun seedRefreshToken(
        userId: UUID,
        rawToken: String,
        revokedAt: Instant?,
    ) {
        jdbcTemplate.update(
            """
            insert into refresh_tokens (
                id,
                user_id,
                token_hash,
                family_id,
                expires_at,
                revoked_at,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            hashToken(rawToken),
            UUID.randomUUID(),
            Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")),
            revokedAt?.let { Timestamp.from(it) },
            Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")),
        )
    }

    private fun userRow(userId: UUID): Map<String, Any?> {
        return jdbcTemplate.queryForMap(
            """
            select status, deactivated_at
            from users
            where id = ?
            """.trimIndent(),
            userId,
        )
    }

    private fun refreshTokenRevokedAt(rawToken: String): Instant? {
        return jdbcTemplate.queryForObject(
            "select revoked_at from refresh_tokens where token_hash = ?",
            { rs, _ -> rs.getTimestamp("revoked_at")?.toInstant() },
            hashToken(rawToken),
        )
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
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
        private val FIXED_INSTANT: Instant = Instant.parse("2026-06-12T10:15:30Z")
    }
}
