package com.gyro.api.subscription

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.auth.application.UserRegisteredEvent
import com.gyro.api.common.error.TrialAlreadyRedeemedException
import com.gyro.api.common.error.TrialNotAvailableException
import com.gyro.api.subscription.application.ManualGrantService
import com.gyro.api.subscription.application.trial.TrialReconciliationJob
import com.gyro.api.subscription.application.trial.TrialService
import com.gyro.api.subscription.domain.Entitlement
import com.gyro.api.subscription.domain.EntitlementSource
import com.gyro.api.subscription.domain.EntitlementStatus
import com.gyro.api.subscription.domain.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class TrialIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val manualGrantService: ManualGrantService,
    @Autowired private val trialService: TrialService,
    @Autowired private val reconciliationJob: TrialReconciliationJob,
    @Autowired private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    @Autowired private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
) {
    private lateinit var userId: UUID
    private lateinit var userEmail: String
    private var advancedPlanId: Long = 0

    @BeforeEach
    fun setUp() {
        userEmail = "trial-${System.nanoTime()}@example.com"
        userId = createUser(userEmail)
        advancedPlanId = planId("ADVANCED")
    }

    @Test
    fun `signup trial grants ADVANCED access exactly once`() {
        val redemption = trialService.grantSignupTrial(userId, userEmail)

        assertNotNull(redemption.id)
        assertTrue(redemption.expiresAt.isAfter(Instant.now()))

        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("ADVANCED") }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.features[?(@ == 'premium_schedules')]") { exists() }
            jsonPath("$.trial.active") { value(true) }
            jsonPath("$.trial.eligible") { value(false) }
            jsonPath("$.trial.expiresAt") { exists() }
        }

        assertThrows(TrialAlreadyRedeemedException::class.java) {
            trialService.grantSignupTrial(userId, userEmail)
        }
    }

    @Test
    fun `same identifier cannot redeem a second trial from another account`() {
        trialService.grantSignupTrial(userId, userEmail)

        val secondUser = createUser("second-${System.nanoTime()}@example.com")
        assertThrows(TrialAlreadyRedeemedException::class.java) {
            trialService.grantSignupTrial(secondUser, userEmail)
        }
    }

    @Test
    fun `free account with a reused contact is not advertised as trial eligible`() {
        val reusedIdentifier = userEmail
        trialService.grantSignupTrial(userId, reusedIdentifier)
        jdbcTemplate.update(
            "update users set email = ?, updated_at = now() where id = ?",
            "original-${System.nanoTime()}@example.com",
            userId,
        )
        val reusedContactUser = createUser(reusedIdentifier)

        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(reusedContactUser)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("FREE") }
            jsonPath("$.trial.active") { value(false) }
            jsonPath("$.trial.eligible") { value(false) }
        }

        mockMvc.post("/api/v1/billing/me/trial/claim") {
            with(authentication(testAuthentication(reusedContactUser)))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("TRIAL_ALREADY_REDEEMED") }
        }
    }

    @Test
    fun `accounts with any subscription history are not trial eligible`() {
        seedSubscription(userId, SubscriptionStatus.EXPIRED)

        assertThrows(TrialNotAvailableException::class.java) {
            trialService.grantSignupTrial(userId, userEmail)
        }

        val status = trialService.statusFor(
            userId,
            entitlementWith(EntitlementStatus.EXPIRED, Instant.now().minus(10, ChronoUnit.DAYS)),
        )
        assertFalse(status.eligible)
        assertFalse(status.active)
    }

    @Test
    fun `eligible free user sees claimable trial and can claim it`() {
        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("FREE") }
            jsonPath("$.trial.active") { value(false) }
            jsonPath("$.trial.eligible") { value(true) }
        }

        mockMvc.post("/api/v1/billing/me/trial/claim") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.expiresAt") { exists() }
            jsonPath("$.entitlement.planKey") { value("ADVANCED") }
            jsonPath("$.entitlement.trial.active") { value(true) }
        }

        mockMvc.post("/api/v1/billing/me/trial/claim") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("TRIAL_ALREADY_REDEEMED") }
        }
    }

    @Test
    fun `trial reads as inactive once the entitlement period moves past it`() {
        val redemption = trialService.grantSignupTrial(userId, userEmail)

        // Simulate a purchase mid-trial: the subscription period extends past
        // the trial expiry, so the entitlement is no longer trial-flavored.
        val purchasedPeriodEnd = redemption.expiresAt.plus(30, ChronoUnit.DAYS)
        val status = trialService.statusFor(
            userId,
            entitlementWith(EntitlementStatus.ACTIVE, purchasedPeriodEnd),
        )

        assertFalse(status.active)
        assertFalse(status.eligible)
        assertEquals(redemption.expiresAt, status.expiresAt)

        // Period-end precision drift (e.g. truncation during normalization)
        // must not misclassify a genuinely active trial.
        val truncated = redemption.expiresAt.minusMillis(3)
        val stillTrial = trialService.statusFor(
            userId,
            entitlementWith(EntitlementStatus.ACTIVE, truncated),
        )
        assertTrue(stillTrial.active)
    }

    @Test
    fun `revoked trial is not presented as active before its redemption window ends`() {
        val redemption = trialService.grantSignupTrial(userId, userEmail)
        val adminId = createUser("trial-admin-${System.nanoTime()}@example.com", role = "ADMIN")

        manualGrantService.revokeGrant(
            grantId = redemption.manualGrantId,
            revokedBy = adminId,
            reason = "Local downgrade verification",
        )

        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("EXPIRED") }
            jsonPath("$.trial.active") { value(false) }
            jsonPath("$.trial.eligible") { value(false) }
            jsonPath("$.trial.expiresAt") { exists() }
        }
    }

    @Test
    fun `only active entitlement status can present an unexpired redemption as an active trial`() {
        val redemption = trialService.grantSignupTrial(userId, userEmail)
        val nonActiveStatuses = listOf(
            EntitlementStatus.GRACE_PERIOD,
            EntitlementStatus.EXPIRED,
            EntitlementStatus.CANCELED,
            EntitlementStatus.BILLED_BLOCKED,
            EntitlementStatus.ADMIN_OVERRIDE,
        )

        nonActiveStatuses.forEach { entitlementStatus ->
            val status = trialService.statusFor(
                userId,
                entitlementWith(entitlementStatus, redemption.expiresAt.minusMillis(1)),
            )

            assertFalse(status.active, "Expected $entitlementStatus not to be trial-flavored")
            assertFalse(status.eligible)
            assertEquals(redemption.expiresAt, status.expiresAt)
        }
    }

    @Test
    fun `registration event grants the trial after the signup transaction commits`() {
        transactionTemplate.executeWithoutResult {
            eventPublisher.publishEvent(
                UserRegisteredEvent(userId = userId, email = userEmail, phoneNumber = null),
            )
            // Still inside the transaction: the AFTER_COMMIT listener must
            // not have run yet.
            assertFalse(jdbcTrialExists())
        }

        assertTrue(jdbcTrialExists())
        mockMvc.get("/api/v1/billing/me/entitlement") {
            with(authentication(testAuthentication(userId)))
        }.andExpect {
            status { isOk() }
            jsonPath("$.planKey") { value("ADVANCED") }
            jsonPath("$.trial.active") { value(true) }
        }

        // A duplicate event (redelivery) is idempotently harmless.
        transactionTemplate.executeWithoutResult {
            eventPublisher.publishEvent(
                UserRegisteredEvent(userId = userId, email = userEmail, phoneNumber = null),
            )
        }
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from trial_redemptions where user_id = ?",
                Int::class.java,
                userId,
            ),
        )
    }

    @Test
    fun `reconciliation job grants trials the signup event missed`() {
        // The user exists and is verified, but no listener ever ran.
        assertFalse(jdbcTrialExists())

        reconciliationJob.run()

        assertTrue(jdbcTrialExists())
        val status = trialService.statusFor(
            userId,
            entitlementWith(EntitlementStatus.ACTIVE, Instant.now().plus(14, ChronoUnit.DAYS)),
        )
        assertFalse(status.eligible)

        // Re-running is a no-op.
        reconciliationJob.run()
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from trial_redemptions where user_id = ?",
                Int::class.java,
                userId,
            ),
        )
    }

    @Test
    fun `permanently rejected candidate does not starve a newer reconciliation candidate`() {
        val reusedIdentifier = userEmail
        trialService.grantSignupTrial(userId, reusedIdentifier)
        jdbcTemplate.update(
            "update users set email = ?, updated_at = now() where id = ?",
            "redeemed-${System.nanoTime()}@example.com",
            userId,
        )

        // Keep this one-row batch deterministic even when another test has
        // left an eligible user in the shared integration-test database.
        jdbcTemplate.update(
            "update users set created_at = now() - interval '4 days' where id <> ? and status = 'ACTIVE'",
            userId,
        )
        val rejectedUser = createUser(reusedIdentifier, Instant.now().minus(2, ChronoUnit.HOURS))
        val recoverableUser = createUser(
            "recoverable-${System.nanoTime()}@example.com",
            Instant.now().minus(1, ChronoUnit.HOURS),
        )

        reconciliationJob.reconcile(limit = 1)

        assertEquals(
            "TRIAL_ALREADY_REDEEMED",
            jdbcTemplate.queryForObject(
                "select outcome from trial_reconciliation_outcomes where user_id = ?",
                String::class.java,
                rejectedUser,
            ),
        )
        assertFalse(jdbcTrialExists(recoverableUser))

        reconciliationJob.reconcile(limit = 1)

        assertTrue(jdbcTrialExists(recoverableUser))
    }

    private fun jdbcTrialExists(candidateUserId: UUID = userId): Boolean =
        jdbcTemplate.queryForObject(
            "select exists(select 1 from trial_redemptions where user_id = ?)",
            Boolean::class.java,
            candidateUserId,
        ) == true

    private fun entitlementWith(status: EntitlementStatus, periodEnd: Instant) = Entitlement(
        status = status,
        planKey = "ADVANCED",
        features = setOf("premium_schedules"),
        currentPeriodEnd = periodEnd,
        gracePeriodEnd = null,
        cancelAtPeriodEnd = false,
        supportReasonCode = null,
        source = EntitlementSource.SUBSCRIPTION,
        premiumGatingDisabled = false,
    )

    private fun createUser(
        email: String,
        createdAt: Instant = Instant.now(),
        role: String = "USER",
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at
            )
            values (?, ?, 'hash', ?, 'VERIFIED', 'UNVERIFIED', 'ACTIVE', ?::timestamptz, now())
            """.trimIndent(),
            id,
            email,
            role,
            createdAt.toString(),
        )
        return id
    }

    private fun planId(code: String): Long {
        return jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = ?",
            Long::class.java,
            code,
        ) ?: error("Expected plan $code")
    }

    private fun seedSubscription(userId: UUID, status: SubscriptionStatus) {
        val now = Instant.now()
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end,
                cancel_at_period_end, grace_period_end
            )
            values (?, ?, ?, ?::timestamptz, ?::timestamptz, false, null)
            """.trimIndent(),
            userId,
            advancedPlanId,
            status.name,
            now.minus(40, ChronoUnit.DAYS).toString(),
            now.minus(10, ChronoUnit.DAYS).toString(),
        )
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
