package com.gyro.api.subscription.web

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.billing.BillingProvider
import com.gyro.api.subscription.billing.CheckoutRequest
import com.gyro.api.subscription.billing.CheckoutResult
import com.gyro.api.subscription.billing.LocalDemoBillingProvider
import com.gyro.api.subscription.domain.Money
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.math.BigDecimal
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.billing.demo.enabled=true",
        "app.billing.payping.enabled=false",
        "app.billing.lifecycle.jobs-enabled=false",
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class LocalDemoBillingControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val billingProvider: BillingProvider,
    @Autowired private val demoProvider: LocalDemoBillingProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val objectMapper = JsonMapper.builder().build()
    private lateinit var testUserId: UUID

    @BeforeEach
    fun createUser() {
        testUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into users (id, email, password_hash, role, email_verification_status,
                phone_verification_status, status, created_at, updated_at)
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            testUserId,
            "demo-${UUID.randomUUID()}@example.invalid",
        )
    }

    @AfterEach
    fun deleteUser() {
        jdbcTemplate.update(
            "delete from outbox_event_consumptions where event_id in " +
                "(select id from outbox_events where aggregate_type = 'UserSubscription')",
        )
        jdbcTemplate.update("delete from outbox_events where aggregate_type = 'UserSubscription'")
        jdbcTemplate.update("delete from subscription_events where user_id = ?", testUserId)
        jdbcTemplate.update("delete from user_subscriptions where user_id = ?", testUserId)
        jdbcTemplate.update(
            "delete from payment_events where payment_attempt_id in " +
                "(select pa.id from payment_attempts pa join invoices i on i.id = pa.invoice_id where i.user_id = ?)",
            testUserId,
        )
        jdbcTemplate.update(
            "delete from payment_attempts where invoice_id in (select id from invoices where user_id = ?)",
            testUserId,
        )
        jdbcTemplate.update("delete from invoices where user_id = ?", testUserId)
        jdbcTemplate.update("delete from users where id = ?", testUserId)
    }

    @Test
    fun `unauthenticated local checkout offers outcomes and routes to the normal callback`() {
        assertSame(demoProvider, billingProvider)
        val attemptId = UUID.randomUUID()
        val checkout = demoProvider.createCheckout(
            CheckoutRequest(
                paymentAttemptId = attemptId,
                clientRefId = "demo-client-$attemptId",
                amount = Money(BigDecimal("125000.00"), "IRR"),
                returnUrl = "http://localhost:8080/api/v1/billing/payping/callback",
                description = "Local demo",
            ),
        ) as CheckoutResult.Success
        val token = checkout.gatewayUrl.substringAfter("token=")
        val path = "/api/v1/billing/demo/checkout/$attemptId"

        mockMvc.get(path) { param("token", token) }.andExpect {
            status { isOk() }
            content { string(containsString("Simulate success")) }
            content { string(containsString("Simulate failure")) }
            content { string(containsString("Simulate pending")) }
        }
        mockMvc.get(path) { param("token", "invalid") }.andExpect { status { isNotFound() } }
        mockMvc.post(path) {
            param("token", token)
            param("outcome", "SUCCESS")
        }.andExpect {
            status { isSeeOther() }
            header { string("Location", startsWith("http://localhost:8080/api/v1/billing/payping/callback?")) }
        }
        mockMvc.post(path) {
            param("token", "invalid")
            param("outcome", "SUCCESS")
        }.andExpect { status { isNotFound() } }
    }

    @ParameterizedTest
    @CsvSource("SUCCESS,VERIFIED,PAID", "FAILED,FAILED,OPEN", "PENDING,VERIFY_PENDING,OPEN")
    fun `success failure and pending choices use normal payment verification`(
        choice: String,
        expectedAttemptStatus: String,
        expectedInvoiceStatus: String,
    ) {
        val priceId = jdbcTemplate.queryForObject(
            """
            select sp.id from subscription_prices sp
            join subscription_plans p on p.id = sp.plan_id
            where p.code = 'ADVANCED' and sp.billing_period_days = 30 and sp.active = true
            """.trimIndent(),
            Long::class.java,
        ) ?: error("Seeded ADVANCED price is required")

        val checkoutBody = mockMvc.post("/api/v1/billing/checkout") {
            with(authentication(testAuthentication()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("priceId" to priceId))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val checkout = objectMapper.readTree(checkoutBody)
        val attemptId = UUID.fromString(checkout.get("paymentAttemptId").asString())
        val invoiceId = UUID.fromString(checkout.get("invoiceId").asString())
        val token = checkout.get("gatewayUrl").asString().substringAfter("token=")

        val returnLocation = mockMvc.post("/api/v1/billing/demo/checkout/$attemptId") {
            param("token", token)
            param("outcome", choice)
        }.andExpect { status { isSeeOther() } }.andReturn().response.getHeader("Location")
            ?: error("Expected callback redirect")
        val callbackUri = URI.create(returnLocation)
        val query = UriComponentsBuilder.fromUri(callbackUri).build().queryParams
        mockMvc.get(callbackUri.path) {
            query.forEach { (name, values) -> values.forEach { value -> param(name, value) } }
        }.andExpect {
            status { isFound() }
            header { string("Location", containsString("state=$choice")) }
        }

        assertEquals(
            expectedAttemptStatus,
            jdbcTemplate.queryForObject("select status from payment_attempts where id = ?", String::class.java, attemptId),
        )
        assertEquals(
            expectedInvoiceStatus,
            jdbcTemplate.queryForObject("select status from invoices where id = ?", String::class.java, invoiceId),
        )
    }

    private fun testAuthentication() = UsernamePasswordAuthenticationToken(
        testUserId.toString(),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )
}
