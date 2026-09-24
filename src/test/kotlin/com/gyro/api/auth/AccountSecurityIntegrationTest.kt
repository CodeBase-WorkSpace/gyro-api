package com.gyro.api.auth

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.auth.application.EmailNormalizer
import org.junit.jupiter.api.Assertions.*
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.time.Duration
import java.util.*

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class AccountSecurityIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private val pepper = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    private val ttl = Duration.ofMinutes(10)

    @Test
    fun `otp email signup creates a verified passwordless account`() {
        val email = "pwless-${System.nanoTime()}@example.com"

        mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email"}""")
        ).andExpect(status().isAccepted)

        seedSignupEmailCode(email)

        mockMvc.perform(
            post("/api/v1/auth/register/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","code":"123456"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())

        assertNull(
            jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?",
                String::class.java,
                email,
            )
        )
        assertEquals(
            "VERIFIED",
            jdbcTemplate.queryForObject(
                "select email_verification_status from users where email = ?",
                String::class.java,
                email,
            )
        )
    }

    @Test
    fun `passwordless account can log in with otp but not with a password`() {
        val email = "pwless-otp-${System.nanoTime()}@example.com"
        val userId = seedUser(email = email, passwordHash = null, emailStatus = "VERIFIED")

        // Password login against a passwordless account fails with the same generic error as a
        // wholly unknown identifier, so it cannot be used to probe whether an account exists.
        mockMvc.perform(
            post("/api/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","password":"Password123"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))

        mockMvc.perform(
            post("/api/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"unknown-${System.nanoTime()}@example.com","password":"Password123"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))

        seedLoginEmailCode(email)
        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"123456"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())

        assertNotNull(userId)
    }

    @Test
    fun `legacy password user keeps password and otp login`() {
        val email = "legacy-${System.nanoTime()}@example.com"
        seedUser(email = email, passwordHash = passwordEncoder.encode("Password123"), emailStatus = "VERIFIED")

        mockMvc.perform(
            post("/api/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","password":"Password123"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())

        seedLoginEmailCode(email)
        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"123456"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").exists())
    }

    @Test
    fun `otp login start is enumeration safe for unknown identifier`() {
        mockMvc.perform(
            post("/api/v1/auth/login/otp/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"nobody-${System.nanoTime()}@example.com"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Login code sent."))
    }

    @Test
    fun `set password requires a recent step-up then rejects when already set`() {
        val userId = seedUser(
            email = "set-${System.nanoTime()}@example.com",
            passwordHash = null,
            emailStatus = "VERIFIED",
        )

        // Without a step-up marker the first password cannot be set.
        mockMvc.perform(
            post("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"Password123"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("RECENT_VERIFICATION_REQUIRED"))

        seedRecentVerification(userId)
        mockMvc.perform(
            post("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"Password123"}""")
        ).andExpect(status().isNoContent)

        assertNotNull(
            jdbcTemplate.queryForObject("select password_hash from users where id = ?", String::class.java, userId)
        )

        mockMvc.perform(
            post("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"Password456"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PASSWORD_ALREADY_SET"))
    }

    @Test
    fun `change password requires current password or recent verification`() {
        val userId = seedUser(
            email = "change-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "VERIFIED",
        )

        // No current password and no step-up marker → rejected.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"NewPassword123"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("RECENT_VERIFICATION_REQUIRED"))

        // Wrong current password → generic invalid credentials.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"WrongPass123","newPassword":"NewPassword123"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))

        // Correct current password → success.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"Password123","newPassword":"NewPassword123"}""")
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `change password succeeds with a recent step-up marker`() {
        val userId = seedUser(
            email = "change-stepup-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "VERIFIED",
        )
        seedRecentVerification(userId)

        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"NewPassword123"}""")
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `remove password requires recent verification and keeps a login identifier`() {
        val userId = seedUser(
            email = "remove-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "VERIFIED",
        )

        // Without a step-up marker → rejected.
        mockMvc.perform(
            delete("/api/v1/users/me/password").with(authentication(auth(userId)))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("RECENT_VERIFICATION_REQUIRED"))

        // With a marker → password removed.
        seedRecentVerification(userId)
        mockMvc.perform(
            delete("/api/v1/users/me/password").with(authentication(auth(userId)))
        ).andExpect(status().isNoContent)

        assertNull(
            jdbcTemplate.queryForObject("select password_hash from users where id = ?", String::class.java, userId)
        )
    }

    @Test
    fun `remove password is blocked when no verified identifier would remain`() {
        val userId = seedUser(
            email = "remove-blocked-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "UNVERIFIED",
        )
        seedRecentVerification(userId)

        mockMvc.perform(
            delete("/api/v1/users/me/password").with(authentication(auth(userId)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("NO_REMAINING_LOGIN_IDENTIFIER"))
    }

    @Test
    fun `security step-up confirm records a recent verification marker`() {
        val userId = seedUser(
            email = "stepup-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "VERIFIED",
        )

        redisTemplate.opsForValue().set("step-up:$userId", hashCode("123456"), ttl)

        mockMvc.perform(
            post("/api/v1/users/me/security/step-up/confirm")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}""")
        ).andExpect(status().isNoContent)

        // The marker now authorises a change without the current password.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"NewPassword123"}""")
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `otp confirm locks out after too many invalid attempts`() {
        val email = "lockout-${System.nanoTime()}@example.com"
        seedUser(email = email, passwordHash = passwordEncoder.encode("Password123"), emailStatus = "VERIFIED")
        seedLoginEmailCode(email)

        // Attempts 1-4 return an invalid-code error; the 5th trips the lockout.
        repeat(4) {
            mockMvc.perform(
                post("/api/v1/auth/login/otp/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"identifier":"$email","code":"000000"}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))
        }

        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"000000"}""")
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("TOO_MANY_VERIFICATION_ATTEMPTS"))

        // The code was invalidated by the lockout, so even the correct code no longer works.
        // Login-OTP confirm reports a generic invalid-code error (enumeration-safe).
        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"123456"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))
    }

    @Test
    fun `otp login confirm is enumeration safe for unknown and existing accounts`() {
        // Unknown identifier: no code was ever stored.
        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"ghost-${System.nanoTime()}@example.com","code":"000000"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))

        // Existing verified account with a wrong code returns the identical error/code.
        val email = "known-${System.nanoTime()}@example.com"
        seedUser(email = email, passwordHash = passwordEncoder.encode("Password123"), emailStatus = "VERIFIED")
        seedLoginEmailCode(email)
        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"000000"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))
    }

    @Test
    fun `otp login code is single use`() {
        val email = "replay-${System.nanoTime()}@example.com"
        seedUser(email = email, passwordHash = passwordEncoder.encode("Password123"), emailStatus = "VERIFIED")
        seedLoginEmailCode(email)

        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"123456"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/auth/login/otp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$email","code":"123456"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))
    }

    @Test
    fun `step-up marker authorises only a single sensitive action`() {
        val userId = seedUser(
            email = "single-use-${System.nanoTime()}@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            emailStatus = "VERIFIED",
        )
        seedRecentVerification(userId)

        // First change consumes the marker.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"NewPassword123"}""")
        ).andExpect(status().isNoContent)

        // Second change without a fresh step-up is rejected — the marker was single-use.
        mockMvc.perform(
            put("/api/v1/users/me/password")
                .with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newPassword":"AnotherPass123"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("RECENT_VERIFICATION_REQUIRED"))
    }

    private fun seedUser(
        email: String? = null,
        phone: String? = null,
        passwordHash: String?,
        emailStatus: String = "UNVERIFIED",
        phoneStatus: String = "UNVERIFIED",
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, phone_number, password_hash, role,
                email_verification_status, phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, ?, ?, 'USER', ?, ?, 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
            phone,
            passwordHash,
            emailStatus,
            phoneStatus,
        )
        return id
    }

    private fun seedSignupEmailCode(email: String) {
        val key = "verify:email:${EmailNormalizer.normalize(email)}"
        redisTemplate.boundHashOps<String, String>(key).putAll(
            mapOf(
                "codeHash" to hashCode("123456"),
                "expiresAt" to java.time.Instant.now().plus(ttl).toString(),
            )
        )
        redisTemplate.expire(key, ttl)
    }

    private fun seedLoginEmailCode(email: String) {
        redisTemplate.opsForValue().set(
            "otp:login:email:${EmailNormalizer.normalize(email)}",
            hashCode("123456"),
            ttl,
        )
    }

    private fun seedRecentVerification(userId: UUID) {
        redisTemplate.opsForValue().set("recent-verify:$userId", "1", ttl)
    }

    private fun auth(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$pepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }
}
