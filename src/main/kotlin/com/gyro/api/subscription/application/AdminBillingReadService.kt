package com.gyro.api.subscription.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant

@Service
class AdminBillingReadService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun snapshot(limit: Int = 20, query: String? = null, status: String? = null): AdminBillingSnapshot {
        val boundedLimit = limit.coerceIn(1, 50)
        val normalizedQuery = query?.trim()?.takeIf { it.isNotBlank() }?.take(128)
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.take(32)
        return AdminBillingSnapshot(
            counts = loadCounts(),
            recentAttempts = loadAttempts(boundedLimit, normalizedQuery, normalizedStatus),
        )
    }

    private fun loadCounts(): AdminBillingCounts {
        return AdminBillingCounts(
            openInvoices = count("select count(*) from invoices where status = 'OPEN'"),
            paidInvoices = count("select count(*) from invoices where status = 'PAID'"),
            pendingAttempts = count("select count(*) from payment_attempts where status in ('PENDING', 'VERIFY_PENDING')"),
            failedAttempts = count("select count(*) from payment_attempts where status in ('FAILED', 'CREATE_FAILED')"),
        )
    }

    private fun loadAttempts(limit: Int, query: String?, status: String?): List<AdminPaymentAttemptSummary> {
        val arguments = mutableListOf<Any>()
        val predicates = buildList {
            query?.let {
                add(
                    """(
                        cast(pa.id as text) = ? or cast(pa.invoice_id as text) = ? or cast(i.user_id as text) = ? or
                        cast(us.id as text) = ? or lower(pa.client_ref_id) like ? escape E'\\' or
                        lower(coalesce(pa.provider_code, '')) like ? escape E'\\' or
                        lower(coalesce(pa.provider_ref_id, '')) like ? escape E'\\' or
                        lower(coalesce(pa.provider_request_id, '')) like ? escape E'\\' or
                        lower(coalesce(u.email, '')) like ? escape E'\\' or
                        lower(coalesce(u.phone_number, '')) like ? escape E'\\'
                    )""".trimIndent(),
                )
                val exact = it.lowercase()
                val pattern = "%${escapeLike(exact)}%"
                arguments.addAll(List(4) { exact })
                arguments.addAll(List(6) { pattern })
            }
            status?.let {
                add("pa.status = ?")
                arguments += it
            }
        }
        val whereClause = predicates.takeIf { it.isNotEmpty() }?.joinToString(" and ", prefix = "where ").orEmpty()
        arguments += limit
        return jdbcTemplate.query(
            """
            select
                pa.id as payment_attempt_id,
                pa.invoice_id,
                i.user_id,
                u.email,
                u.phone_number,
                us.id as subscription_id,
                pa.provider,
                pa.client_ref_id,
                pa.provider_code,
                pa.provider_ref_id,
                pa.provider_request_id,
                pa.status as attempt_status,
                i.status as invoice_status,
                us.status as subscription_status,
                pa.amount,
                pa.currency,
                pa.reversible,
                pa.created_at,
                pa.updated_at,
                pe.event_type as latest_event_type,
                pe.created_at as latest_event_at,
                pe.safe_summary as latest_safe_summary
            from payment_attempts pa
            join invoices i on i.id = pa.invoice_id
            join users u on u.id = i.user_id
            left join user_subscriptions us on us.user_id = i.user_id
            left join lateral (
                select event_type, created_at, safe_summary
                from payment_events pe
                where pe.payment_attempt_id = pa.id
                order by pe.created_at desc
                limit 1
            ) pe on true
            $whereClause
            order by pa.updated_at desc, pa.created_at desc
            limit ?
            """.trimIndent(),
            { rs, _ -> rs.toPaymentAttemptSummary() },
            *arguments.toTypedArray(),
        )
    }

    private fun escapeLike(value: String): String {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    private fun count(sql: String): Long {
        return jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0L
    }

    private fun ResultSet.toPaymentAttemptSummary(): AdminPaymentAttemptSummary {
        return AdminPaymentAttemptSummary(
            paymentAttemptId = getString("payment_attempt_id"),
            invoiceId = getString("invoice_id"),
            userId = getString("user_id"),
            email = getString("email"),
            phoneNumber = getString("phone_number"),
            subscriptionId = getString("subscription_id"),
            provider = getString("provider"),
            clientRefId = getString("client_ref_id"),
            providerCode = getString("provider_code"),
            providerRefId = getString("provider_ref_id"),
            providerRequestId = getString("provider_request_id"),
            attemptStatus = getString("attempt_status"),
            invoiceStatus = getString("invoice_status"),
            subscriptionStatus = getString("subscription_status"),
            amount = getBigDecimal("amount"),
            currency = getString("currency"),
            reversible = getBoolean("reversible"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            latestEventType = getString("latest_event_type"),
            latestEventAt = getTimestamp("latest_event_at")?.toInstant(),
            latestSafeSummary = getString("latest_safe_summary"),
        )
    }
}

data class AdminBillingSnapshot(
    val counts: AdminBillingCounts,
    val recentAttempts: List<AdminPaymentAttemptSummary>,
)

data class AdminBillingCounts(
    val openInvoices: Long,
    val paidInvoices: Long,
    val pendingAttempts: Long,
    val failedAttempts: Long,
)

data class AdminPaymentAttemptSummary(
    val paymentAttemptId: String,
    val invoiceId: String,
    val userId: String,
    val email: String?,
    val phoneNumber: String?,
    val subscriptionId: String?,
    val provider: String,
    val clientRefId: String,
    val providerCode: String?,
    val providerRefId: String?,
    val providerRequestId: String?,
    val attemptStatus: String,
    val invoiceStatus: String,
    val subscriptionStatus: String?,
    val amount: BigDecimal,
    val currency: String,
    val reversible: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val latestEventType: String?,
    val latestEventAt: Instant?,
    val latestSafeSummary: String?,
)
