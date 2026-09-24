package com.gyro.api.common.security

import com.gyro.api.auth.domain.UserRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@Service
class JwtService(
    @Value("\${app.security.jwt.secret:}")
    private val jwtSecret: String,
    @Value("\${app.security.jwt.issuer:gyro-api}")
    private val issuer: String,
    @Value("\${app.security.jwt.audience:gyro}")
    private val audience: String,
    @Value("\${app.security.jwt.ec-private-key:}")
    private val ecPrivateKeyPem: String,
    @Value("\${app.security.jwt.ec-public-key:}")
    private val ecPublicKeyPem: String,
    @Value("\${app.security.jwt.legacy-hs256-enabled:true}")
    private val legacyHs256Enabled: Boolean,
    @Value("\${app.security.jwt.legacy-hs256-accept-until:}")
    private val legacyHs256AcceptUntilValue: String,
    @Value("\${app.security.jwt.access-token-ttl:15m}")
    val accessTokenExpiration: Duration,
    @Value("\${app.security.jwt.refresh-token-ttl:30d}")
    val refreshTokenExpiration: Duration,
) {
    init {
        require(issuer.isNotBlank()) { "APP_JWT_ISSUER must not be blank." }
        require(audience.isNotBlank()) { "APP_JWT_AUDIENCE must not be blank." }
        require(ecPrivateKeyPem.isBlank() == ecPublicKeyPem.isBlank()) {
            "JWT_EC_PRIVATE_KEY and JWT_EC_PUBLIC_KEY must either both be configured or both be blank."
        }
    }

    private val legacyHs256AcceptUntil: Instant? = legacyHs256AcceptUntilValue
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let { value ->
            runCatching { Instant.parse(value) }.getOrElse {
                throw IllegalArgumentException(
                    "JWT_LEGACY_HS256_ACCEPT_UNTIL must be a UTC ISO-8601 instant such as 2026-09-01T00:00:00Z.",
                    it,
                )
            }
        }

    private val secretKey = jwtSecret.trim().takeIf(String::isNotEmpty)?.let { value ->
        runCatching { Keys.hmacShaKeyFor(Base64.getDecoder().decode(value)) }.getOrElse {
            throw IllegalArgumentException("JWT_SECRET must be a valid Base64 HS256 key of at least 32 bytes.", it)
        }
    }

    // The EC pair is parsed during application startup. A malformed or mismatched
    // deployment must fail before it can issue tokens the frontend cannot verify.
    private val ecPrivateKey: PrivateKey? = ecPrivateKeyPem.takeIf(String::isNotBlank)
        ?.let { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decodePem(it, "PRIVATE KEY"))) }
    private val ecPublicKey: PublicKey? = ecPublicKeyPem.takeIf(String::isNotBlank)
        ?.let { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decodePem(it, "PUBLIC KEY"))) }

    init {
        if (acceptsLegacyHs256()) {
            require(secretKey != null) {
                "JWT_SECRET is required while legacy HS256 verification remains enabled."
            }
        }
        if (ecPrivateKey != null && ecPublicKey != null) {
            requireP256(ecPrivateKey, "JWT_EC_PRIVATE_KEY")
            requireP256(ecPublicKey, "JWT_EC_PUBLIC_KEY")
            require(keysMatch(ecPrivateKey, ecPublicKey)) {
                "JWT_EC_PRIVATE_KEY and JWT_EC_PUBLIC_KEY do not form a matching key pair."
            }
        } else {
            require(legacyHs256Enabled && legacyHs256AcceptUntil == null) {
                "A P-256 JWT EC key pair is required before legacy HS256 is disabled or given a retirement deadline."
            }
        }
    }

    val accessTokenValidityMs: Long
        get() = accessTokenExpiration.toMillis()

    val refreshTokenValidityMs: Long
        get() = refreshTokenExpiration.toMillis()

    private fun generateToken(
        userId: String,
        role: UserRole,
        type: String,
        expiry: Long,
    ): String {
        val now = Date()
        val expiryDate = Date(now.time + expiry)
        val builder = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId)
            .issuer(issuer)
            .audience().add(audience).and()
            .claims()
            .apply {
                add("type", type)
                if (type == "access") add("role", role.name)
            }
            .and()
            .issuedAt(now)
            .expiration(expiryDate)

        return ecPrivateKey
            ?.let { builder.signWith(it, Jwts.SIG.ES256).compact() }
            ?: builder.signWith(requireNotNull(secretKey), Jwts.SIG.HS256).compact()
    }

    fun generateAccessToken(userId: String, role: UserRole): String = generateToken(
        userId = userId,
        role = role,
        type = "access",
        expiry = accessTokenValidityMs,
    )

    fun generateRefreshToken(userId: String): String = generateToken(
        userId = userId,
        role = UserRole.USER,
        type = "refresh",
        expiry = refreshTokenValidityMs,
    )

    fun validateAccessToken(token: String): Boolean = parseAllClaims(token)?.get("type") == "access"

    fun validateRefreshToken(token: String): Boolean = parseAllClaims(token)?.get("type") == "refresh"

    fun getUserIdFromToken(token: String): String? = parseAllClaims(token)?.subject

    fun getAccessTokenIssuedAt(token: String): Instant? {
        val claims = parseAllClaims(token) ?: return null
        return if (claims["type"] == "access") claims.issuedAt?.toInstant() else null
    }

    private fun parseAllClaims(token: String): Claims? {
        val rawToken = token.removePrefix("Bearer ")
        return try {
            val parsed = Jwts.parser()
                // Map each accepted algorithm to its own key type. This prevents
                // an HMAC/EC algorithm-confusion path during the migration window.
                .keyLocator { header ->
                    when ((header as? JwsHeader)?.algorithm) {
                        Jwts.SIG.ES256.id -> ecPublicKey
                            ?: throw UnsupportedJwtException("ES256 token without configured EC public key")
                        Jwts.SIG.HS256.id -> {
                            if (!acceptsLegacyHs256()) {
                                throw UnsupportedJwtException("Legacy HS256 token acceptance is disabled or expired")
                            }
                            requireNotNull(secretKey)
                        }
                        else -> throw UnsupportedJwtException("Unsupported JWS algorithm")
                    }
                }
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(rawToken)

            // Legacy HS256 tokens may predate the audience claim. ES256 is the
            // new contract and must always be scoped to this API audience.
            if (parsed.header.algorithm == Jwts.SIG.ES256.id && audience !in parsed.payload.audience) {
                throw UnsupportedJwtException("ES256 token audience does not match this API")
            }
            parsed.payload
        } catch (_: Exception) {
            null
        }
    }

    fun getRoleFromToken(authHeader: String): UserRole? {
        val roleName = parseAllClaims(authHeader)?.get("role") as? String ?: return null
        return runCatching { UserRole.valueOf(roleName) }.getOrNull()
    }

    private fun decodePem(pem: String, label: String): ByteArray {
        val body = pem
            .replace("\\n", "\n")
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace(Regex("\\s"), "")
        require(body.isNotBlank()) { "Invalid PEM content for $label." }
        return Base64.getDecoder().decode(body)
    }

    private fun keysMatch(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
        val probe = "gyro-jwt-key-pair-check".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
    }

    private fun acceptsLegacyHs256(): Boolean = legacyHs256Enabled &&
        (legacyHs256AcceptUntil == null || Instant.now().isBefore(legacyHs256AcceptUntil))

    private fun requireP256(key: java.security.Key, environmentName: String) {
        val actual = (key as? ECKey)?.params
            ?: throw IllegalArgumentException("$environmentName must contain an EC key.")
        require(sameEcParameters(actual, P256_PARAMETERS)) {
            "$environmentName must use the P-256 (secp256r1/prime256v1) curve required by ES256."
        }
    }

    private fun sameEcParameters(left: ECParameterSpec, right: ECParameterSpec): Boolean =
        left.curve == right.curve &&
            left.generator == right.generator &&
            left.order == right.order &&
            left.cofactor == right.cofactor

    companion object {
        private val P256_PARAMETERS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }
}
