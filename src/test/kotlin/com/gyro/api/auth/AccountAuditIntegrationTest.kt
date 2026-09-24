package com.gyro.api.auth

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class AccountAuditIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val accountAuditService: AccountAuditService,
) {
    private val verificationPepper = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="

    @Test
    fun `profile update logout password reset and deactivation create audit events`() {
        val userId = UUID.randomUUID()
        val email = "audit-${System.nanoTime()}@example.com"
        seedUser(userId, email)
        seedRefreshToken(userId, "logout-token", null)
        seedRefreshToken(userId, "password-reset-token", null)

        mockMvc.perform(
            patch("/api/v1/users/me/profile")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "displayName": "Audited User",
                      "timezone": "Europe/Berlin"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"logout-token"}""")
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/auth/password-reset/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email"}""")
        )
            .andExpect(status().isOk)

        seedPasswordResetCode(email, "123456")

        mockMvc.perform(
            post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "identifier": "$email",
                      "code": "123456",
                      "newPassword": "NewPassword123"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            delete("/api/v1/users/me")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isNoContent)

        assertEquals(1, auditCount(userId, AccountAuditEventType.PROFILE_UPDATED))
        assertEquals(1, auditCount(userId, AccountAuditEventType.LOGOUT))
        assertEquals(1, auditCount(userId, AccountAuditEventType.PASSWORD_RESET_REQUESTED))
        assertEquals(1, auditCount(userId, AccountAuditEventType.PASSWORD_RESET_COMPLETED))
        assertEquals(1, auditCount(userId, AccountAuditEventType.ACCOUNT_DEACTIVATED))

        val profileMetadata = auditMetadata(userId, AccountAuditEventType.PROFILE_UPDATED)
        assertTrue(profileMetadata.contains("changedFields"))
        assertTrue(profileMetadata.contains("displayName"))
        assertTrue(profileMetadata.contains("timezone"))

        val logoutMetadata = auditMetadata(userId, AccountAuditEventType.LOGOUT)
        assertTrue(logoutMetadata.contains("sessionRevoked"))
        assertFalse(logoutMetadata.contains("logout-token"))

        val passwordResetMetadata = auditMetadata(userId, AccountAuditEventType.PASSWORD_RESET_COMPLETED)
        assertTrue(passwordResetMetadata.contains("revokedSessionCount"))
        assertFalse(passwordResetMetadata.contains("NewPassword123"))
        assertFalse(passwordResetMetadata.contains("123456"))
    }

    @Test
    fun `audit service redacts sensitive metadata fields`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "audit-redaction-${System.nanoTime()}@example.com")

        accountAuditService.record(
            actorUserId = userId,
            targetUserId = userId,
            eventType = AccountAuditEventType.PROFILE_UPDATED,
            metadata = mapOf(
                "safeField" to "visible",
                "refreshToken" to "must-not-persist",
                "nested" to mapOf(
                    "newPassword" to "must-not-persist",
                    "timezone" to "UTC",
                ),
            ),
        )

        val metadata = auditMetadata(userId, AccountAuditEventType.PROFILE_UPDATED)
        assertTrue(metadata.contains("visible"))
        assertTrue(metadata.contains("timezone"))
        assertFalse(metadata.contains("must-not-persist"))
        assertFalse(metadata.contains("refreshToken"))
        assertFalse(metadata.contains("newPassword"))
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

    private fun seedPasswordResetCode(email: String, code: String) {
        redisTemplate.opsForValue().set(
            "password-reset:${email.trim().lowercase()}",
            hashVerificationCode(code),
            Duration.ofMinutes(10),
        )
    }

    private fun auditCount(
        userId: UUID,
        eventType: AccountAuditEventType,
    ): Int {
        return jdbcTemplate.queryForObject(
            """
            select count(*)
            from account_audit_events
            where target_user_id = ?
              and event_type = ?
            """.trimIndent(),
            Int::class.java,
            userId,
            eventType.name,
        ) ?: 0
    }

    private fun auditMetadata(
        userId: UUID,
        eventType: AccountAuditEventType,
    ): String {
        return jdbcTemplate.queryForObject(
            """
            select metadata::text
            from account_audit_events
            where target_user_id = ?
              and event_type = ?
            order by created_at desc
            limit 1
            """.trimIndent(),
            String::class.java,
            userId,
            eventType.name,
        ) ?: ""
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

    private fun hashVerificationCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$verificationPepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }
}
