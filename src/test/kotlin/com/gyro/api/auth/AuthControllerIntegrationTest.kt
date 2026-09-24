package com.gyro.api.auth

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.auth.application.EmailNormalizer
import com.gyro.api.auth.application.IranianPhoneValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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
class AuthControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val objectMapper = JsonMapper.builder().build()
    private val verificationCode = "123456"
    private val verificationTtl = Duration.ofMinutes(10)
    private val verificationPepper = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="

    @Test
    fun `register login refresh and logout with email`() {
        val email = "user-${System.nanoTime()}@example.com"
        val password = "Password123"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value("Signup verification code sent.") }
            jsonPath("$.otpExpireInSeconds") { exists() }
        }

        assertEquals(
            "UNVERIFIED",
            jdbcTemplate.queryForObject(
                "select email_verification_status from users where email = ?",
                String::class.java,
                email,
            )
        )

        seedSignupEmailCode(email)

        val verifyResponse = mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
            jsonPath("$.tokenType") { value("Bearer") }
        }.andReturn().response.contentAsString.toJson()

        mockMvc.get("/api/v1/users/me") {
            header("Authorization", "Bearer ${verifyResponse["accessToken"].asText()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.emailVerificationStatus") { value("VERIFIED") }
            jsonPath("$.hasPassword") { value(false) }
        }

        // OTP-only signup leaves the account passwordless, so password login must be rejected.
        assertNull(
            jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?",
                String::class.java,
                email,
            )
        )
        mockMvc.post("/api/v1/auth/login/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$email",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }

        // Set a password from the authenticated session, then password login works.
        setPassword(verifyResponse["accessToken"].asText(), password)

        val loginResponse = mockMvc.post("/api/v1/auth/login/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$email",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
        }.andReturn().response.contentAsString.toJson()

        mockMvc.post("/api/v1/auth/login/otp/start") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$email"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.message") { value("Login code sent.") }
        }

        seedLoginEmailCode(email)

        mockMvc.post("/api/v1/auth/login/otp/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$email",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
        }

        val refreshedResponse = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "${loginResponse["refreshToken"].asText()}"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
        }.andReturn().response.contentAsString.toJson()

        assertNotEquals(loginResponse["refreshToken"].asText(), refreshedResponse["refreshToken"].asText())

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "${loginResponse["refreshToken"].asText()}"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
        }

        jdbcTemplate.update(
            "update refresh_tokens set rotation_grace_expires_at = now() - interval '1 second' where token_hash = ?",
            hashToken(loginResponse["refreshToken"].asText()),
        )

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "${loginResponse["refreshToken"].asText()}"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }

        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "${refreshedResponse["refreshToken"].asText()}"
                }
            """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "${refreshedResponse["refreshToken"].asText()}"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }

    @Test
    fun `dev signup verification accepts local bypass code`() {
        val email = "bypass-${System.nanoTime()}@example.com"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "code": "111000"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
        }
    }

    @Test
    fun `invalid refresh token returns pwa retry terminal error`() {
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "refreshToken": "not-a-jwt"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            jsonPath("$.message") { value("Refresh token is invalid or expired.") }
        }
    }

    @Test
    fun `register and login with phone number`() {
        val phoneNumber = "+98912${System.nanoTime().toString().takeLast(7)}"
        val password = "Password123"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "phoneNumber": "$phoneNumber",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value("Signup verification code sent.") }
        }

        seedSignupPhoneCode(phoneNumber)

        val verifyResponse = mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "phoneNumber": "$phoneNumber",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
        }.andReturn().response.contentAsString.toJson()

        setPassword(verifyResponse["accessToken"].asText(), password)

        mockMvc.post("/api/v1/auth/login/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$phoneNumber",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
        }
    }

    @Test
    fun `register with mixed case email stores normalized email and login accepts lowercase`() {
        val rawEmail = " Case-${System.nanoTime()}@Example.COM "
        val normalizedEmail = EmailNormalizer.normalize(rawEmail)
        val password = "Password123"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$rawEmail",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        assertEquals(
            normalizedEmail,
            jdbcTemplate.queryForObject(
                "select email from users where email = ?",
                String::class.java,
                normalizedEmail,
            )
        )

        seedSignupEmailCode(normalizedEmail)

        val verifyResponse = mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$normalizedEmail",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString.toJson()

        setPassword(verifyResponse["accessToken"].asText(), password)

        mockMvc.post("/api/v1/auth/login/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$normalizedEmail",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
        }
    }

    @Test
    fun `registering normalized duplicate email rejects casing variants`() {
        val localPart = "duplicate-case-${System.nanoTime()}"
        val email = "$localPart@test.com"
        val uppercaseEmail = email.uppercase(Locale.ROOT)

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        seedSignupEmailCode(email)

        mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$uppercaseEmail",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_ALREADY_REGISTERED") }
        }
    }

    @Test
    fun `phone registration persists canonical e164 and login accepts every supported input format`() {
        val password = "Password123"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "phoneNumber": "09121234567",
                  "password": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        seedSignupPhoneCode("989121234567")

        val verifyResponse = mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "phoneNumber": "+989121234567",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString.toJson()

        setPassword(verifyResponse["accessToken"].asText(), password)

        assertEquals(
            "+989121234567",
            jdbcTemplate.queryForObject(
                "select phone_number from users where phone_number = ?",
                String::class.java,
                "+989121234567",
            )
        )

        listOf(
            "09121234567",
            "989121234567",
            "+989121234567",
            "۰۹۱۲۱۲۳۴۵۶۷",
            "٠٩١٢١٢٣٤٥٦٧",
        ).forEach { identifier ->
            mockMvc.post("/api/v1/auth/login/password") {
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "identifier": "$identifier",
                      "password": "$password"
                    }
                """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.accessToken") { exists() }
            }
        }
    }

    @Test
    fun `registration and verification reject requests with both email and phone number`() {
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "dual-${System.nanoTime()}@example.com",
                  "phoneNumber": "+989121234567",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }

        mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "dual-${System.nanoTime()}@example.com",
                  "phoneNumber": "+989121234567",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `invalid phone numbers are rejected during registration validation`() {
        listOf(
            "08121234567",
            "09123",
            "+9809121234567",
            "abc",
            "+98abc",
        ).forEach { phoneNumber ->
            mockMvc.post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "phoneNumber": "$phoneNumber",
                      "password": "Password123"
                    }
                """.trimIndent()
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_ERROR") }
            }
        }
    }

    @Test
    fun `repeated pending email registration refreshes signup code`() {
        val email = "pending-refresh-${System.nanoTime()}@example.com"

        repeat(2) {
            mockMvc.post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "email": "$email",
                      "password": "Password123"
                    }
                """.trimIndent()
            }.andExpect {
                status { isAccepted() }
                jsonPath("$.message") { value("Signup verification code sent.") }
            }
        }
    }

    @Test
    fun `duplicate verified email registration returns conflict`() {
        val email = "duplicate-${System.nanoTime()}@example.com"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        seedSignupEmailCode(email)

        mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_ALREADY_REGISTERED") }
        }
    }

    @Test
    fun `register with same idempotency key returns cached response`() {
        val email = "idempotent-${System.nanoTime()}@example.com"
        val idempotencyKey = "register-${System.nanoTime()}"
        val requestBody = """
            {
              "email": "$email",
              "password": "Password123"
            }
        """.trimIndent()

        val firstResponse = mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = requestBody
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value("Signup verification code sent.") }
        }.andReturn().response.contentAsString.toJson()

        val replayedResponse = mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = requestBody
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value(firstResponse["message"].asText()) }
        }.andReturn().response.contentAsString.toJson()

        assertEquals(firstResponse["message"].asText(), replayedResponse["message"].asText())
        assertEquals(firstResponse["otpExpireInSeconds"].asInt(), replayedResponse["otpExpireInSeconds"].asInt())
    }

    @Test
    fun `register with reused idempotency key and different payload returns conflict`() {
        val idempotencyKey = "register-conflict-${System.nanoTime()}"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = """
                {
                  "email": "first-${System.nanoTime()}@example.com",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = """
                {
                  "email": "second-${System.nanoTime()}@example.com",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_KEY_CONFLICT") }
        }
    }

    @Test
    fun `wrong password returns unauthorized`() {
        val email = "wrong-password-${System.nanoTime()}@example.com"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "password": "Password123"
                }
            """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        seedSignupEmailCode(email)

        mockMvc.post("/api/v1/auth/register/verify") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "email": "$email",
                  "code": "$verificationCode"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/v1/auth/login/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "identifier": "$email",
                  "password": "WrongPassword123"
                }
            """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
    }

    private fun setPassword(accessToken: String, password: String) {
        // Setting a first password requires a recent OTP step-up; seed the server-side marker.
        redisTemplate.opsForValue().set("recent-verify:${userIdFromAccessToken(accessToken)}", "1", verificationTtl)
        mockMvc.post("/api/v1/users/me/password") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer $accessToken")
            content = """
                {
                  "newPassword": "$password"
                }
            """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }
    }

    private fun userIdFromAccessToken(accessToken: String): String {
        val payload = accessToken.split(".")[1]
        val json = String(Base64.getUrlDecoder().decode(payload))
        return objectMapper.readTree(json)["sub"].asText()
    }

    private fun String.toJson(): JsonNode {
        assertTrue(isNotBlank())
        return objectMapper.readTree(this)
    }

    private fun seedSignupEmailCode(email: String) {
        val key = "verify:email:${EmailNormalizer.normalize(email)}"
        assertTrue(redisTemplate.hasKey(key))
        redisTemplate.boundHashOps<String, String>(key).putAll(
            mapOf(
                "codeHash" to hashCode(verificationCode),
                "expiresAt" to java.time.Instant.now().plus(verificationTtl).toString(),
            )
        )
        redisTemplate.expire(key, verificationTtl)
    }

    private fun seedSignupPhoneCode(phoneNumber: String) {
        updatePendingRegistrationCode("pending-register:phone:${phoneNumber.normalizePhoneNumber()}")
    }

    private fun seedLoginEmailCode(email: String) {
        redisTemplate.opsForValue().set(
            "otp:login:email:${EmailNormalizer.normalize(email)}",
            hashCode(verificationCode),
            verificationTtl,
        )
    }

    private fun hashCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$code:$verificationPepper".toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun updatePendingRegistrationCode(key: String) {
        assertTrue(redisTemplate.hasKey(key))
        redisTemplate.boundHashOps<String, String>(key).put("codeHash", hashCode(verificationCode))
        redisTemplate.expire(key, verificationTtl)
    }

    private fun String.normalizePhoneNumber(): String {
        return IranianPhoneValidator.normalize(this)
    }
}
