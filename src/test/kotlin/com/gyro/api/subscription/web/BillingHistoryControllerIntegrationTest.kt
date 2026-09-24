package com.gyro.api.subscription.web

import com.gyro.api.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class BillingHistoryControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val invoiceId = UUID.randomUUID()
    private val otherInvoiceId = UUID.randomUUID()
    private val attemptId = UUID.randomUUID()
    private var planId: Long = 0
    private val userEmail = "billing-history-${userId.toString().take(12)}@example.com"
    private val userPhone = "+989120001234"

    @BeforeEach
    fun setUp() {
        planId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("ADVANCED plan is required")
        insertUser(userId, userEmail, userPhone, "USER")
        insertUser(otherUserId, "other-${UUID.randomUUID()}@example.com", null, "USER")
        insertUser(adminId, "admin-${UUID.randomUUID()}@example.com", null, "ADMIN")
        insertInvoice(invoiceId, userId, "PAID", "OFF20", false)
        insertInvoice(otherInvoiceId, otherUserId, "OPEN", null, false)
        insertAttempt()
        insertPaymentEvent()
        insertSubscriptionEvent()
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.update(
            "delete from account_audit_events where actor_user_id in (?, ?) or target_user_id in (?, ?)",
            adminId,
            userId,
            adminId,
            userId,
        )
        jdbcTemplate.update("delete from subscription_events where source_id = ?", invoiceId.toString())
        jdbcTemplate.update(
            "delete from payment_events where payment_attempt_id in (select pa.id from payment_attempts pa join invoices i on i.id = pa.invoice_id where i.user_id in (?, ?))",
            userId,
            otherUserId,
        )
        jdbcTemplate.update(
            "delete from payment_attempts where invoice_id in (select id from invoices where user_id in (?, ?))",
            userId,
            otherUserId,
        )
        jdbcTemplate.update("delete from invoices where user_id in (?, ?)", userId, otherUserId)
        jdbcTemplate.update("delete from users where id in (?, ?, ?)", userId, otherUserId, adminId)
    }

    @Test
    fun `user billing history requires authentication`() {
        mockMvc.get("/api/v1/billing/me/history").andExpect {
            status { isUnauthorized() }
            jsonPath("$.requestId") { exists() }
        }
    }

    @Test
    fun `user billing history is owner scoped and redacts provider metadata`() {
        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.invoices.length()") { value(1) }
            jsonPath("$.invoices[0].invoiceId") { value(invoiceId.toString()) }
            jsonPath("$.invoices[0].planCode") { value("ADVANCED") }
            jsonPath("$.invoices[0].status") { value("PAID") }
            jsonPath("$.invoices[0].promotionCode") { value("OFF20") }
            jsonPath("$.invoices[0].latestPayment.status") { value("VERIFIED") }
            jsonPath("$.invoices[0].latestPayment.supportReference") { value("request-user-safe") }
            content { string(not(containsString(otherInvoiceId.toString()))) }
            content { string(not(containsString("PAY-CODE-SECRET"))) }
            content { string(not(containsString("PROVIDER-REF-SECRET"))) }
            content { string(not(containsString(userEmail))) }
            content { string(not(containsString(userPhone))) }
        }
    }

    @Test
    fun `admin invoice detail requires admin and returns safe timeline with audited read`() {
        mockMvc.get("/api/v1/admin/billing/invoices/$invoiceId") {
            with(authentication(authentication(userId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/api/v1/admin/billing/invoices/$invoiceId") {
            with(authentication(authentication(adminId, "ROLE_ADMIN")))
            header("X-Request-Id", "billing-history-test-request")
        }.andExpect {
            status { isOk() }
            jsonPath("$.user.userId") { value(userId.toString()) }
            jsonPath("$.user.email") { value(userEmail) }
            jsonPath("$.user.phoneNumber") { value(userPhone) }
            jsonPath("$.paymentAttempts[0].providerCode") { value("PAY-CODE-SECRET") }
            jsonPath("$.paymentAttempts[0].events[0].eventType") { value("PAYPING_VERIFIED") }
            jsonPath("$.paymentAttempts[0].events[0].safeSummary") { value(containsString("verified")) }
            jsonPath("$.subscriptionEvents[0].transitionType") { value("FIRST_PURCHASE") }
            content { string(not(containsString("raw-provider-secret"))) }
        }

        val auditCount = jdbcTemplate.queryForObject(
            """
            select count(*) from account_audit_events
            where actor_user_id = ? and target_user_id = ? and event_type = 'ADMIN_BILLING_READ'
              and metadata ->> 'scope' = 'invoice_detail'
              and metadata ->> 'outcome' = 'SUCCESS'
              and request_id = 'billing-history-test-request'
            """.trimIndent(),
            Long::class.java,
            adminId,
            userId,
        )
        kotlin.test.assertEquals(1L, auditCount)
    }

    @Test
    fun `admin invoice detail audits missing invoices and excludes unrelated lifecycle sources`() {
        val missingInvoiceId = UUID.randomUUID()
        mockMvc.get("/api/v1/admin/billing/invoices/$missingInvoiceId") {
            with(authentication(authentication(adminId, "ROLE_ADMIN")))
            header("X-Request-Id", "billing-history-not-found-request")
        }.andExpect { status { isNotFound() } }

        val notFoundAuditCount = jdbcTemplate.queryForObject(
            """
            select count(*) from account_audit_events
            where actor_user_id = ? and event_type = 'ADMIN_BILLING_READ'
              and metadata ->> 'scope' = 'invoice_detail'
              and metadata ->> 'outcome' = 'NOT_FOUND'
              and metadata ->> 'invoiceId' = ?
              and request_id = 'billing-history-not-found-request'
            """.trimIndent(),
            Long::class.java,
            adminId,
            missingInvoiceId.toString(),
        )
        kotlin.test.assertEquals(1L, notFoundAuditCount)

        insertSubscriptionEvent(sourceType = "ADMIN", transitionType = "ADMIN_GRANT")

        mockMvc.get("/api/v1/admin/billing/invoices/$invoiceId") {
            with(authentication(authentication(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.subscriptionEvents.length()") { value(1) }
            jsonPath("$.subscriptionEvents[0].sourceType") { value("PAYPING") }
            content { string(not(containsString("ADMIN_GRANT"))) }
        }
    }

    @Test
    fun `billing history paginates with a stable created_at and id cursor`() {
        val olderInvoiceIds = (1..3).map { offsetDays ->
            UUID.randomUUID().also { insertInvoice(it, userId, "PAID", null, false, ageDays = offsetDays) }
        }

        val firstPage = mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
            param("limit", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.invoices.length()") { value(2) }
            jsonPath("$.invoices[0].invoiceId") { value(invoiceId.toString()) }
            jsonPath("$.invoices[1].invoiceId") { value(olderInvoiceIds[0].toString()) }
            jsonPath("$.hasMore") { value(true) }
            jsonPath("$.nextBeforeInvoiceId") { value(olderInvoiceIds[0].toString()) }
        }.andReturn()

        val nextBeforeCreatedAt = com.jayway.jsonpath.JsonPath.read<String>(
            firstPage.response.contentAsString,
            "$.nextBeforeCreatedAt",
        )

        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
            param("limit", "2")
            param("beforeCreatedAt", nextBeforeCreatedAt)
            param("beforeInvoiceId", olderInvoiceIds[0].toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.invoices.length()") { value(2) }
            jsonPath("$.invoices[0].invoiceId") { value(olderInvoiceIds[1].toString()) }
            jsonPath("$.invoices[1].invoiceId") { value(olderInvoiceIds[2].toString()) }
            jsonPath("$.hasMore") { value(false) }
            jsonPath("$.nextBeforeCreatedAt") { doesNotExist() }
            jsonPath("$.nextBeforeInvoiceId") { doesNotExist() }
        }
    }

    @Test
    fun `billing history rejects partial or malformed cursors`() {
        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
            param("beforeCreatedAt", "2026-07-01T00:00:00Z")
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
            param("beforeCreatedAt", "not-a-date")
            param("beforeInvoiceId", invoiceId.toString())
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
            param("beforeCreatedAt", "2026-07-01T00:00:00Z")
            param("beforeInvoiceId", "not-a-uuid")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `history represents pending abandoned manual and promotion discounted invoices`() {
        val pendingInvoiceId = UUID.randomUUID()
        val abandonedInvoiceId = UUID.randomUUID()
        val manualInvoiceId = UUID.randomUUID()
        insertInvoice(pendingInvoiceId, userId, "OPEN", null, false)
        insertInvoice(abandonedInvoiceId, userId, "OPEN", null, false)
        insertInvoice(manualInvoiceId, userId, "PAID", null, true)
        insertAttempt(UUID.randomUUID(), pendingInvoiceId, "VERIFY_PENDING", "pending-support-ref")
        insertAttempt(UUID.randomUUID(), abandonedInvoiceId, "CANCELLED", "abandoned-support-ref")

        mockMvc.get("/api/v1/billing/me/history") {
            with(authentication(authentication(userId, "ROLE_USER")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.invoices.length()") { value(4) }
            jsonPath("$.invoices[?(@.invoiceId == '$pendingInvoiceId')].latestPayment.status") { value("VERIFY_PENDING") }
            jsonPath("$.invoices[?(@.invoiceId == '$abandonedInvoiceId')].latestPayment.status") { value("CANCELLED") }
            jsonPath("$.invoices[?(@.invoiceId == '$manualInvoiceId')].manual") { value(true) }
            jsonPath("$.invoices[?(@.invoiceId == '$invoiceId')].promotionCode") { value("OFF20") }
        }
    }

    private fun insertUser(id: UUID, email: String, phone: String?, role: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, phone_number, password_hash, role,
                email_verification_status, phone_verification_status, status, created_at, updated_at
            ) values (?, ?, ?, 'hash', ?, 'VERIFIED', 'VERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
            phone,
            role,
        )
    }

    private fun insertInvoice(
        id: UUID,
        ownerId: UUID,
        status: String,
        promotion: String?,
        manual: Boolean,
        ageDays: Int = 0,
    ) {
        jdbcTemplate.update(
            """
            insert into invoices (
                id, user_id, plan_id, period_start, period_end, amount_due, currency,
                amount_after_discount, discount_currency, status, promotion_code, manual, created_at, updated_at
            ) values (
                ?, ?, ?, now(), now() + interval '90 days', 3000000, 'IRR',
                2400000, 'IRR', ?, ?, ?, now() - make_interval(days => ?), now()
            )
            """.trimIndent(),
            id,
            ownerId,
            planId,
            status,
            promotion,
            manual,
            ageDays,
        )
    }

    private fun insertAttempt() {
        insertAttempt(attemptId, invoiceId, "VERIFIED", "request-user-safe")
    }

    private fun insertAttempt(id: UUID, targetInvoiceId: UUID, status: String, requestId: String) {
        jdbcTemplate.update(
            """
            insert into payment_attempts (
                id, invoice_id, provider, client_ref_id, provider_code, provider_ref_id,
                provider_request_id, amount, currency, status, reversible, created_at, updated_at
            ) values (
                ?, ?, 'PAYPING', ?, ?, ?,
                ?, 2400000, 'IRR', ?, true, now(), now()
            )
            """.trimIndent(),
            id,
            targetInvoiceId,
            "client-$id",
            if (id == attemptId) "PAY-CODE-SECRET" else "PAY-CODE-$id",
            if (id == attemptId) "PROVIDER-REF-SECRET" else "PROVIDER-REF-$id",
            requestId,
            status,
        )
    }

    private fun insertPaymentEvent() {
        jdbcTemplate.update(
            """
            insert into payment_events (
                payment_attempt_id, provider, event_type, provider_ref_id, raw_payload, safe_summary, created_at
            ) values (
                ?, 'PAYPING', 'PAYPING_VERIFIED', 'PROVIDER-REF-SECRET',
                '{"secret":"raw-provider-secret"}'::jsonb, '{"outcome":"verified"}'::jsonb, now()
            )
            """.trimIndent(),
            attemptId,
        )
    }

    private fun insertSubscriptionEvent(
        sourceType: String = "PAYPING",
        transitionType: String = "FIRST_PURCHASE",
    ) {
        jdbcTemplate.update(
            """
            insert into subscription_events (
                user_id, transition_type, source_type, source_id, idempotency_key,
                status_after, plan_id_after, period_start_after, period_end_after, created_at
            ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, now(), now() + interval '90 days', now())
            """.trimIndent(),
            userId,
            transitionType,
            sourceType,
            invoiceId.toString(),
            "billing-history-$sourceType-$invoiceId",
            planId,
        )
    }

    private fun authentication(id: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(id.toString(), null, listOf(SimpleGrantedAuthority(role)))
    }
}
