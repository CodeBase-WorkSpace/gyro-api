package com.gyro.api.subscription.web

import com.gyro.api.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
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
import java.util.*
import kotlin.math.absoluteValue

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class AdminBillingControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val adminId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val invoiceId = UUID.randomUUID()
    private val paymentAttemptId = UUID.randomUUID()
    private var subscriptionId: Long = 0
    private var planId: Long = 0
    private val userEmail = "billing-search-${userId.toString().take(12)}@example.com"
    private val userPhone = "+989${userId.hashCode().toLong().absoluteValue.toString().padStart(9, '0').takeLast(9)}"

    @BeforeEach
    fun setUp() {
        planId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("ADVANCED plan is required")
        insertUser(adminId, "billing-search-admin-${UUID.randomUUID()}@example.com", null, "ADMIN")
        insertUser(userId, userEmail, userPhone, "USER")
        subscriptionId = jdbcTemplate.queryForObject(
            """
            insert into user_subscriptions (
                user_id, plan_id, status, period_start, period_end, cancel_at_period_end
            ) values (?, ?, 'ACTIVE', now(), now() + interval '30 days', false)
            returning id
            """.trimIndent(),
            Long::class.java,
            userId,
            planId,
        ) ?: error("Expected subscription id")
        insertInvoice(invoiceId)
        insertAttempt(
            id = paymentAttemptId,
            clientRefId = "client-literal%_\\marker",
            providerCode = "PAY-CODE-ABC",
            providerRefId = "REF-12345",
            providerRequestId = "request-abc",
            status = "PENDING",
        )
        insertAttempt(
            id = UUID.randomUUID(),
            clientRefId = "another-client-reference",
            providerCode = "OTHER-CODE",
            providerRefId = "OTHER-REF",
            providerRequestId = "other-request",
            status = "FAILED",
        )
        insertEvent("PAYPING_CALLBACK_RECEIVED", "2026-07-10T07:00:00Z", "older")
        insertEvent("PAYPING_VERIFICATION_PENDING", "2026-07-10T08:00:00Z", "latest")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update(
            "delete from account_audit_events where actor_user_id = ? or target_user_id = ?",
            adminId,
            adminId
        )
        jdbcTemplate.update(
            "delete from payment_events where payment_attempt_id in (select id from payment_attempts where invoice_id = ?)",
            invoiceId,
        )
        jdbcTemplate.update("delete from payment_attempts where invoice_id = ?", invoiceId)
        jdbcTemplate.update("delete from invoices where id = ?", invoiceId)
        jdbcTemplate.update("delete from user_subscriptions where user_id = ?", userId)
        jdbcTemplate.update("delete from users where id in (?, ?)", userId, adminId)
    }

    @Test
    fun `admin billing search rejects anonymous and non-admin users`() {
        mockMvc.get("/api/v1/admin/billing").andExpect { status { isUnauthorized() } }

        mockMvc.get("/api/v1/admin/billing") {
            with(authentication(authentication(userId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin searches exact payment invoice user and subscription identifiers`() {
        search(paymentAttemptId.toString()).andExpect {
            status { isOk() }
            jsonPath("$.recentAttempts.length()") { value(1) }
            jsonPath("$.recentAttempts[0].paymentAttemptId") { value(paymentAttemptId.toString()) }
        }

        listOf(invoiceId, userId, subscriptionId).forEach { query ->
            search(query.toString()).andExpect {
                status { isOk() }
                jsonPath("$.recentAttempts.length()") { value(2) }
                content { string(containsString(paymentAttemptId.toString())) }
            }
        }
    }

    @Test
    fun `admin searches partial provider user and request references and returns support contacts`() {
        listOf("literal%_\\mark", "code-ab", "ref-1234", "request-a").forEach { query ->
            search(query).andExpect {
                status { isOk() }
                jsonPath("$.recentAttempts.length()") { value(1) }
            }
        }

        listOf(userEmail.substringBefore('@').takeLast(8), userPhone.takeLast(8)).forEach { query ->
            search(query).andExpect {
                status { isOk() }
                jsonPath("$.recentAttempts.length()") { value(2) }
                content { string(containsString(userEmail)) }
                content { string(containsString(userPhone)) }
            }
        }
    }

    @Test
    fun `admin search treats SQL wildcard and escape characters literally`() {
        listOf("%", "_", "\\").forEach { query ->
            search(query).andExpect {
                status { isOk() }
                jsonPath("$.recentAttempts.length()") { value(1) }
                jsonPath("$.recentAttempts[0].paymentAttemptId") { value(paymentAttemptId.toString()) }
            }
        }
    }

    @Test
    fun `admin search applies status and caps requested limit at fifty`() {
        repeat(55) { index ->
            insertAttempt(
                id = UUID.randomUUID(),
                clientRefId = "limit-cap-$index-${UUID.randomUUID()}",
                providerCode = null,
                providerRefId = null,
                providerRequestId = null,
                status = "PENDING",
            )
        }

        mockMvc.get("/api/v1/admin/billing") {
            with(authentication(authentication(adminId, "ROLE_ADMIN")))
            param("query", "limit-cap")
            param("status", "PENDING")
            param("limit", "100")
        }.andExpect {
            status { isOk() }
            jsonPath("$.recentAttempts.length()") { value(50) }
        }

        mockMvc.get("/api/v1/admin/billing") {
            with(authentication(authentication(adminId, "ROLE_ADMIN")))
            param("query", "limit-cap")
            param("status", "FAILED")
        }.andExpect {
            status { isOk() }
            jsonPath("$.recentAttempts.length()") { value(0) }
        }
    }

    @Test
    fun `admin billing result selects the latest payment event`() {
        search(paymentAttemptId.toString()).andExpect {
            status { isOk() }
            jsonPath("$.recentAttempts[0].latestEventType") { value("PAYPING_VERIFICATION_PENDING") }
            jsonPath("$.recentAttempts[0].latestSafeSummary") { value(containsString("latest")) }
        }
    }

    private fun search(query: String) = mockMvc.get("/api/v1/admin/billing") {
        with(authentication(authentication(adminId, "ROLE_ADMIN")))
        param("query", query)
    }

    private fun insertUser(id: UUID, email: String?, phone: String?, role: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, phone_number, password_hash, role,
                email_verification_status, phone_verification_status, status, created_at, updated_at
            ) values (?, ?, ?, '{noop}Password123', ?, 'VERIFIED', 'VERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
            phone,
            role,
        )
    }

    private fun insertInvoice(id: UUID) {
        jdbcTemplate.update(
            """
            insert into invoices (
                id, user_id, plan_id, period_start, period_end, amount_due, currency,
                amount_after_discount, discount_currency, status, manual, created_at, updated_at
            ) values (?, ?, ?, now(), now() + interval '30 days', 1990000, 'IRR', 1990000, 'IRR', 'OPEN', false, now(), now())
            """.trimIndent(),
            id,
            userId,
            planId,
        )
    }

    private fun insertAttempt(
        id: UUID,
        clientRefId: String,
        providerCode: String?,
        providerRefId: String?,
        providerRequestId: String?,
        status: String,
    ) {
        jdbcTemplate.update(
            """
            insert into payment_attempts (
                id, invoice_id, provider, client_ref_id, provider_code, provider_ref_id,
                provider_request_id, amount, currency, status, reversible, created_at, updated_at
            ) values (?, ?, 'PAYPING', ?, ?, ?, ?, 1990000, 'IRR', ?, false, now(), now())
            """.trimIndent(),
            id,
            invoiceId,
            clientRefId,
            providerCode,
            providerRefId,
            providerRequestId,
            status,
        )
    }

    private fun insertEvent(eventType: String, createdAt: String, outcome: String) {
        jdbcTemplate.update(
            """
            insert into payment_events (
                payment_attempt_id, provider, event_type, provider_ref_id, raw_payload, safe_summary, created_at
            ) values (?, 'PAYPING', ?, 'REF-12345', ?::jsonb, ?::jsonb, ?::timestamptz)
            """.trimIndent(),
            paymentAttemptId,
            eventType,
            "{\"outcome\":\"$outcome\"}",
            "{\"outcome\":\"$outcome\"}",
            createdAt,
        )
    }

    private fun authentication(id: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(id.toString(), null, listOf(SimpleGrantedAuthority(role)))
    }
}
