package com.gyro.api.common.security

import com.gyro.api.auth.domain.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.security.PrivateKey
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtServiceTest {
    @Test
    fun `generated access token validates and exposes subject and role`() {
        val service = jwtService()
        val token = service.generateAccessToken("user-123", UserRole.ADMIN)

        assertTrue(service.validateAccessToken(token))
        assertTrue(service.validateAccessToken("Bearer $token"))
        assertEquals("user-123", service.getUserIdFromToken(token))
        assertEquals(UserRole.ADMIN, service.getRoleFromToken("Bearer $token"))
    }

    @Test
    fun `refresh token is not accepted as access token`() {
        val service = jwtService()
        val refreshToken = service.generateRefreshToken("user-123")

        assertTrue(service.validateRefreshToken(refreshToken))
        assertFalse(service.validateAccessToken(refreshToken))
        assertNull(service.getRoleFromToken(refreshToken))
    }

    @Test
    fun `token signed for another issuer is rejected`() {
        val issuerA = jwtService(issuer = "gyro-api")
        val issuerB = jwtService(issuer = "other-api")
        val token = issuerA.generateAccessToken("user-123", UserRole.USER)

        assertFalse(issuerB.validateAccessToken(token))
        assertNull(issuerB.getUserIdFromToken(token))
    }

    @Test
    fun `blank issuer or audience fails during construction`() {
        assertFailsWith<IllegalArgumentException> { jwtService(issuer = "") }
        assertFailsWith<IllegalArgumentException> { jwtService(audience = "") }
    }

    @Test
    fun `expired token is rejected`() {
        val service = jwtService(accessTokenExpiration = Duration.ofSeconds(-1))
        val token = service.generateAccessToken("user-123", UserRole.USER)

        assertFalse(service.validateAccessToken(token))
    }

    @Test
    fun `blank secret fails during startup while HS256 remains enabled`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(secret = "")
        }
    }

    @Test
    fun `malformed legacy secret fails during construction`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(secret = "not-base64")
        }
    }

    @Test
    fun `ES256 service signs and validates its own tokens with audience claim`() {
        val service = jwtService(ecKeyPair = TEST_KEY_PAIR)
        val token = service.generateAccessToken("user-123", UserRole.ADMIN)

        assertTrue(service.validateAccessToken(token))
        assertEquals("user-123", service.getUserIdFromToken(token))
        assertEquals(UserRole.ADMIN, service.getRoleFromToken(token))
    }

    @Test
    fun `ES256 token with wrong audience is rejected`() {
        val token = es256AccessToken(TEST_KEY_PAIR.privateKey, audience = "other-service")

        assertFalse(jwtService(ecKeyPair = TEST_KEY_PAIR).validateAccessToken(token))
    }

    @Test
    fun `ES256 token without audience is rejected`() {
        val token = es256AccessToken(TEST_KEY_PAIR.privateKey, audience = null)

        assertFalse(jwtService(ecKeyPair = TEST_KEY_PAIR).validateAccessToken(token))
    }

    @Test
    fun `ES256 service still validates legacy HS256 tokens during migration`() {
        val legacyToken = legacyHs256AccessTokenWithoutAudience()

        assertTrue(jwtService(ecKeyPair = TEST_KEY_PAIR).validateAccessToken(legacyToken))
    }

    @Test
    fun `legacy HS256 token is rejected when migration support is disabled`() {
        val legacyToken = legacyHs256AccessTokenWithoutAudience()

        assertFalse(
            jwtService(ecKeyPair = TEST_KEY_PAIR, legacyHs256Enabled = false)
                .validateAccessToken(legacyToken),
        )
    }

    @Test
    fun `ES256 operation does not require legacy secret after migration`() {
        val service = jwtService(
            secret = "",
            ecKeyPair = TEST_KEY_PAIR,
            legacyHs256Enabled = false,
        )

        assertTrue(service.validateAccessToken(service.generateAccessToken("user-123", UserRole.USER)))
    }

    @Test
    fun `legacy HS256 token is rejected after configured retirement instant`() {
        val legacyToken = legacyHs256AccessTokenWithoutAudience()

        assertFalse(
            jwtService(
                ecKeyPair = TEST_KEY_PAIR,
                legacyHs256AcceptUntil = "2000-01-01T00:00:00Z",
            ).validateAccessToken(legacyToken),
        )
    }

    @Test
    fun `token signed with a different EC key is rejected`() {
        val token = jwtService(ecKeyPair = TEST_KEY_PAIR).generateAccessToken("user-123", UserRole.USER)

        assertFalse(jwtService(ecKeyPair = generateEcKeyPair()).validateAccessToken(token))
    }

    @Test
    fun `mismatched configured EC key pair fails during construction`() {
        val otherKeyPair = generateEcKeyPair()

        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = TEST_KEY_PAIR.copy(publicPem = otherKeyPair.publicPem))
        }
    }

    @Test
    fun `partial EC keypair configuration fails during construction`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = TEST_KEY_PAIR.copy(publicPem = ""))
        }
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = TEST_KEY_PAIR.copy(privatePem = ""))
        }
    }

    @Test
    fun `non P-256 EC keypair fails during construction`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = generateEcKeyPair("secp384r1"))
        }
    }

    @Test
    fun `legacy retirement settings require an EC keypair`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = null, legacyHs256Enabled = false)
        }
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = null, legacyHs256AcceptUntil = "2099-01-01T00:00:00Z")
        }
    }

    @Test
    fun `invalid legacy retirement instant fails during construction`() {
        assertFailsWith<IllegalArgumentException> {
            jwtService(ecKeyPair = TEST_KEY_PAIR, legacyHs256AcceptUntil = "30 days after rollout")
        }
    }

    private fun jwtService(
        secret: String = TEST_SECRET,
        issuer: String = "gyro-api",
        audience: String = "gyro",
        ecKeyPair: PemKeyPair? = null,
        legacyHs256Enabled: Boolean = true,
        legacyHs256AcceptUntil: String = "",
        accessTokenExpiration: Duration = Duration.ofMinutes(15),
        refreshTokenExpiration: Duration = Duration.ofDays(30),
    ) = JwtService(
        jwtSecret = secret,
        issuer = issuer,
        audience = audience,
        ecPrivateKeyPem = ecKeyPair?.privatePem.orEmpty(),
        ecPublicKeyPem = ecKeyPair?.publicPem.orEmpty(),
        legacyHs256Enabled = legacyHs256Enabled,
        legacyHs256AcceptUntilValue = legacyHs256AcceptUntil,
        accessTokenExpiration = accessTokenExpiration,
        refreshTokenExpiration = refreshTokenExpiration,
    )

    private fun es256AccessToken(privateKey: PrivateKey, audience: String?): String {
        val builder = Jwts.builder()
            .subject("user-123")
            .issuer("gyro-api")
            .claim("type", "access")
            .claim("role", UserRole.USER.name)
            .issuedAt(Date.from(Instant.now().minusSeconds(1)))
            .expiration(Date.from(Instant.now().plusSeconds(300)))
        if (audience != null) builder.audience().add(audience).and()
        return builder.signWith(privateKey, Jwts.SIG.ES256).compact()
    }

    private fun legacyHs256AccessTokenWithoutAudience(): String = Jwts.builder()
        .subject("user-123")
        .issuer("gyro-api")
        .claim("type", "access")
        .claim("role", UserRole.USER.name)
        .issuedAt(Date.from(Instant.now().minusSeconds(1)))
        .expiration(Date.from(Instant.now().plusSeconds(300)))
        .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET)), Jwts.SIG.HS256)
        .compact()

    data class PemKeyPair(
        val privatePem: String,
        val publicPem: String,
        val privateKey: PrivateKey,
    )

    companion object {
        private val TEST_SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".toByteArray())
        private val TEST_KEY_PAIR = generateEcKeyPair()

        private fun generateEcKeyPair(curve: String = "secp256r1"): PemKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(curve))
            val keyPair = generator.generateKeyPair()
            val encoder = Base64.getEncoder()
            return PemKeyPair(
                privatePem = "-----BEGIN PRIVATE KEY-----\n${encoder.encodeToString(keyPair.private.encoded)}\n-----END PRIVATE KEY-----",
                publicPem = "-----BEGIN PUBLIC KEY-----\n${encoder.encodeToString(keyPair.public.encoded)}\n-----END PUBLIC KEY-----",
                privateKey = keyPair.private,
            )
        }
    }
}
