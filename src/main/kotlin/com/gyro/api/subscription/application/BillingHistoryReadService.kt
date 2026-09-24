package com.gyro.api.subscription.application

import com.gyro.api.common.error.InvoiceNotFoundException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.*

@Service
class BillingHistoryReadService(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun userHistory(
        userId: UUID,
        limit: Int = 20,
        before: BillingHistoryCursor? = null,
    ): BillingHistoryResponse {
        val boundedLimit = limit.coerceIn(1, 50)
        return try {
            val cursorClause = if (before != null) " and (i.created_at, i.id) < (?, ?)" else ""
            val arguments = buildList {
                add(userId)
                before?.let {
                    add(Timestamp.from(it.createdAt))
                    add(it.invoiceId)
                }
                add(boundedLimit + 1)
            }
            val rows = jdbcTemplate.query(
                "$INVOICE_SUMMARY_SELECT where i.user_id = ?$cursorClause order by i.created_at desc, i.id desc limit ?",
                { rs, _ -> rs.toInvoiceSummary() },
                *arguments.toTypedArray(),
            )
            val invoices = rows.take(boundedLimit)
            val hasMore = rows.size > boundedLimit
            recordRead("user", "success")
            log.info(
                "event=billing_history_read surface=user outcome=success invoice_count={} has_more={}",
                invoices.size,
                hasMore,
            )
            BillingHistoryResponse(
                invoices = invoices,
                hasMore = hasMore,
                nextBeforeCreatedAt = if (hasMore) invoices.last().createdAt else null,
                nextBeforeInvoiceId = if (hasMore) invoices.last().invoiceId else null,
            )
        } catch (exception: RuntimeException) {
            recordRead("user", "failure")
            log.warn(
                "event=billing_history_read surface=user outcome=failure reason={}",
                exception::class.simpleName,
            )
            throw exception
        }
    }

    @Transactional(readOnly = true)
    fun adminInvoice(invoiceId: UUID): AdminBillingInvoiceDetail {
        return try {
            val invoiceRows = jdbcTemplate.query(
                "$INVOICE_SUMMARY_SELECT where i.id = ?",
                { rs, _ ->
                    InvoiceWithUser(
                        invoice = rs.toInvoiceSummary(),
                        user = AdminBillingUser(
                            userId = UUID.fromString(rs.getString("user_id")),
                            email = rs.getString("email"),
                            phoneNumber = rs.getString("phone_number"),
                        ),
                    )
                },
                invoiceId,
            )
            val invoice = invoiceRows.singleOrNull() ?: run {
                recordRead("admin", "not_found")
                throw InvoiceNotFoundException()
            }
            val attempts = loadAttempts(invoiceId)
            val lifecycleEvents = loadLifecycleEvents(invoiceId)
            recordRead("admin", "success")
            log.info(
                "event=billing_history_read surface=admin outcome=success attempt_count={} lifecycle_event_count={}",
                attempts.size,
                lifecycleEvents.size,
            )
            AdminBillingInvoiceDetail(
                invoice = invoice.invoice,
                user = invoice.user,
                paymentAttempts = attempts,
                subscriptionEvents = lifecycleEvents,
            )
        } catch (exception: InvoiceNotFoundException) {
            log.info("event=billing_history_read surface=admin outcome=not_found")
            throw exception
        } catch (exception: RuntimeException) {
            recordRead("admin", "failure")
            log.warn(
                "event=billing_history_read surface=admin outcome=failure reason={}",
                exception::class.simpleName,
            )
            throw exception
        }
    }

    private fun loadAttempts(invoiceId: UUID): List<AdminBillingPaymentAttempt> {
        val eventsByAttempt = jdbcTemplate.query(
            """
            select pe.payment_attempt_id, pe.event_type, pe.safe_summary::text as safe_summary, pe.created_at
            from payment_events pe
            join payment_attempts pa on pa.id = pe.payment_attempt_id
            where pa.invoice_id = ?
            order by pe.created_at desc
            limit $MAX_TIMELINE_PAYMENT_EVENTS
            """.trimIndent(),
            { rs, _ ->
                UUID.fromString(rs.getString("payment_attempt_id")) to AdminBillingPaymentEvent(
                    eventType = rs.getString("event_type"),
                    safeSummary = rs.getString("safe_summary"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            invoiceId,
        ).groupBy({ it.first }, { it.second })

        return jdbcTemplate.query(
            """
            select id, provider, client_ref_id, provider_code, provider_ref_id, provider_request_id,
                   status, amount, currency, reversible, created_at, updated_at
            from payment_attempts
            where invoice_id = ?
            order by created_at desc
            limit $MAX_TIMELINE_ATTEMPTS
            """.trimIndent(),
            { rs, _ ->
                val attemptId = UUID.fromString(rs.getString("id"))
                AdminBillingPaymentAttempt(
                    paymentAttemptId = attemptId,
                    provider = rs.getString("provider"),
                    clientRefId = rs.getString("client_ref_id"),
                    providerCode = rs.getString("provider_code"),
                    providerRefId = rs.getString("provider_ref_id"),
                    providerRequestId = rs.getString("provider_request_id"),
                    status = rs.getString("status"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency"),
                    reversible = rs.getBoolean("reversible"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    events = eventsByAttempt[attemptId].orEmpty(),
                )
            },
            invoiceId,
        )
    }

    private fun loadLifecycleEvents(invoiceId: UUID): List<AdminBillingSubscriptionEvent> {
        return jdbcTemplate.query(
            """
            select transition_type, source_type, status_before, status_after, reason, created_at
            from subscription_events
            where source_id = ? and source_type = 'PAYPING'
            order by created_at desc
            limit $MAX_TIMELINE_LIFECYCLE_EVENTS
            """.trimIndent(),
            { rs, _ ->
                AdminBillingSubscriptionEvent(
                    transitionType = rs.getString("transition_type"),
                    sourceType = rs.getString("source_type"),
                    statusBefore = rs.getString("status_before"),
                    statusAfter = rs.getString("status_after"),
                    reason = rs.getString("reason"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            invoiceId.toString(),
        )
    }

    private fun ResultSet.toInvoiceSummary(): BillingInvoiceSummary {
        val attemptId = getString("latest_attempt_id")
        return BillingInvoiceSummary(
            invoiceId = UUID.fromString(getString("invoice_id")),
            planCode = getString("plan_code"),
            periodStart = getTimestamp("period_start").toInstant(),
            periodEnd = getTimestamp("period_end").toInstant(),
            amountDue = getBigDecimal("amount_due"),
            amountAfterDiscount = getBigDecimal("amount_after_discount"),
            currency = getString("currency"),
            status = getString("invoice_status"),
            promotionCode = getString("promotion_code"),
            manual = getBoolean("manual"),
            createdAt = getTimestamp("invoice_created_at").toInstant(),
            updatedAt = getTimestamp("invoice_updated_at").toInstant(),
            latestPayment = attemptId?.let {
                BillingPaymentSummary(
                    status = getString("latest_attempt_status"),
                    updatedAt = getTimestamp("latest_attempt_updated_at").toInstant(),
                    supportReference = getString("latest_provider_request_id") ?: attemptId,
                )
            },
        )
    }

    private fun recordRead(surface: String, outcome: String) {
        meterRegistryProvider.ifAvailable { registry ->
            Counter.builder("gyro.billing.history.reads")
                .tag("surface", surface)
                .tag("outcome", outcome)
                .description("Billing history reads by surface and outcome")
                .register(registry)
                .increment()
        }
    }

    private data class InvoiceWithUser(
        val invoice: BillingInvoiceSummary,
        val user: AdminBillingUser,
    )

    private companion object {
        private const val MAX_TIMELINE_ATTEMPTS = 50
        private const val MAX_TIMELINE_PAYMENT_EVENTS = 100
        private const val MAX_TIMELINE_LIFECYCLE_EVENTS = 100

        private val INVOICE_SUMMARY_SELECT = """
            select
                i.id as invoice_id,
                i.user_id,
                u.email,
                u.phone_number,
                sp.code as plan_code,
                i.period_start,
                i.period_end,
                i.amount_due,
                i.amount_after_discount,
                i.currency,
                i.status as invoice_status,
                i.promotion_code,
                i.manual,
                i.created_at as invoice_created_at,
                i.updated_at as invoice_updated_at,
                latest.id as latest_attempt_id,
                latest.status as latest_attempt_status,
                latest.provider_request_id as latest_provider_request_id,
                latest.updated_at as latest_attempt_updated_at
            from invoices i
            join users u on u.id = i.user_id
            join subscription_plans sp on sp.id = i.plan_id
            left join lateral (
                select pa.id, pa.status, pa.provider_request_id, pa.updated_at
                from payment_attempts pa
                where pa.invoice_id = i.id
                order by pa.updated_at desc, pa.created_at desc
                limit 1
            ) latest on true
        """.trimIndent()
    }
}

data class BillingHistoryCursor(
    val createdAt: Instant,
    val invoiceId: UUID,
)

data class BillingHistoryResponse(
    val invoices: List<BillingInvoiceSummary>,
    val hasMore: Boolean = false,
    val nextBeforeCreatedAt: Instant? = null,
    val nextBeforeInvoiceId: UUID? = null,
)

data class BillingInvoiceSummary(
    val invoiceId: UUID,
    val planCode: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val amountDue: BigDecimal,
    val amountAfterDiscount: BigDecimal,
    val currency: String,
    val status: String,
    val promotionCode: String?,
    val manual: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val latestPayment: BillingPaymentSummary?,
)

data class BillingPaymentSummary(
    val status: String,
    val updatedAt: Instant,
    val supportReference: String,
)

data class AdminBillingInvoiceDetail(
    val invoice: BillingInvoiceSummary,
    val user: AdminBillingUser,
    val paymentAttempts: List<AdminBillingPaymentAttempt>,
    val subscriptionEvents: List<AdminBillingSubscriptionEvent>,
)

data class AdminBillingUser(
    val userId: UUID,
    val email: String?,
    val phoneNumber: String?,
)

data class AdminBillingPaymentAttempt(
    val paymentAttemptId: UUID,
    val provider: String,
    val clientRefId: String,
    val providerCode: String?,
    val providerRefId: String?,
    val providerRequestId: String?,
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    val reversible: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val events: List<AdminBillingPaymentEvent>,
)

data class AdminBillingPaymentEvent(
    val eventType: String,
    val safeSummary: String?,
    val createdAt: Instant,
)

data class AdminBillingSubscriptionEvent(
    val transitionType: String,
    val sourceType: String,
    val statusBefore: String?,
    val statusAfter: String?,
    val reason: String?,
    val createdAt: Instant,
)
