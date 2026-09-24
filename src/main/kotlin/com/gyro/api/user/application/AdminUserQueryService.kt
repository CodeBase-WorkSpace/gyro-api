package com.gyro.api.user.application

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.id.UuidParser
import com.gyro.api.common.pagination.PageResponse
import com.gyro.api.common.pagination.Pagination
import com.gyro.api.user.web.dto.AdminUserAuditCounts
import com.gyro.api.user.web.dto.AdminUserAuthenticationCounts
import com.gyro.api.user.web.dto.AdminUserBillingCounts
import com.gyro.api.user.web.dto.AdminUserDetailResponse
import com.gyro.api.user.web.dto.AdminUserFoodDiaryCounts
import com.gyro.api.user.web.dto.AdminUserHealthCounts
import com.gyro.api.user.web.dto.AdminUserIdentityResponse
import com.gyro.api.user.web.dto.AdminUserSummaryResponse
import com.gyro.api.user.web.dto.AdminUserSubscriptionSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

@Service
class AdminUserQueryService(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun searchUsers(
        query: String?,
        role: String?,
        status: String?,
        page: Int?,
        size: Int?,
    ): PageResponse<AdminUserSummaryResponse> {
        val pagination = Pagination.normalize(page, size)
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any>()

        val trimmedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        if (trimmedQuery != null) {
            val queryUuid = UuidParser.parse(trimmedQuery)
            if (queryUuid != null) {
                conditions += "u.id = ?"
                parameters += queryUuid
            } else {
                conditions += "(lower(coalesce(u.email, '')) like ? escape '\\' " +
                    "or lower(coalesce(u.phone_number, '')) like ? escape '\\' " +
                    "or lower(coalesce(p.display_name, '')) like ? escape '\\')"
                val pattern = "%${escapeLike(trimmedQuery.lowercase())}%"
                parameters += pattern
                parameters += pattern
                parameters += pattern
            }
        }

        role?.trim()?.takeIf { it.isNotBlank() }?.uppercase()?.let {
            conditions += "u.role = ?"
            parameters += it
        }
        status?.trim()?.takeIf { it.isNotBlank() }?.uppercase()?.let {
            conditions += "u.status = ?"
            parameters += it
        }

        val whereClause = if (conditions.isEmpty()) "" else "where ${conditions.joinToString(" and ")}"
        val fromClause = """
            from users u
            left join user_profiles p on p.user_id = u.id
        """.trimIndent()

        val totalItems = jdbcTemplate.queryForObject(
            "select count(*) $fromClause $whereClause",
            Long::class.java,
            *parameters.toTypedArray(),
        ) ?: 0L

        val items = jdbcTemplate.query(
            """
            select
                u.id, u.email, u.phone_number, p.display_name, u.role, u.status,
                u.email_verification_status, u.phone_verification_status, u.created_at,
                (
                    select s.status from user_subscriptions s
                    where s.user_id = u.id
                    order by s.created_at desc limit 1
                ) as subscription_status,
                (select count(*) from promotion_redemptions pr where pr.user_id = u.id and pr.status = 'REDEEMED') as promotion_redemptions
            $fromClause
            $whereClause
            order by u.created_at desc, u.id asc
            limit ? offset ?
            """.trimIndent(),
            { rs, _ -> rs.toSummary() },
            *parameters.toTypedArray(),
            pagination.size,
            pagination.page * pagination.size,
        )

        val totalPages = if (totalItems == 0L) 0 else ((totalItems + pagination.size - 1) / pagination.size).toInt()
        return PageResponse(
            items = items,
            page = pagination.page,
            size = pagination.size,
            totalItems = totalItems,
            totalPages = totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getUserDetail(userId: UUID): AdminUserDetailResponse {
        val identity = findIdentity(userId) ?: throw ResourceNotFoundException("User")
        val currentSubscription = findCurrentSubscription(userId)

        return AdminUserDetailResponse(
            identity = identity,
            hasProfile = countBy("user_profiles", "user_id", userId) > 0,
            health = AdminUserHealthCounts(
                nutritionPlans = countBy("nutrition_plans", "user_id", userId),
                planSchedules = countBy("plan_schedules", "user_id", userId),
                weightEntries = countBy("weight_entries", "user_id", userId),
                dailyScores = countBy("daily_scores", "user_id", userId),
            ),
            foodAndDiary = AdminUserFoodDiaryCounts(
                diaryDays = countBy("diary_days", "user_id", userId),
                diaryEntries = countBy("diary_entries", "user_id", userId),
                meals = countBy("meals", "owner_user_id", userId),
                mealItems = queryCount(
                    "select count(*) from meal_items mi join meals m on m.id = mi.meal_id where m.owner_user_id = ?",
                    userId,
                ),
                customFoods = countBy("foods", "owner_user_id", userId),
                foodFavorites = countBy("food_favorites", "user_id", userId),
                recentFoods = countBy("recent_foods", "user_id", userId),
            ),
            authentication = AdminUserAuthenticationCounts(
                activeSessions = queryCount(
                    "select count(*) from refresh_tokens where user_id = ? and revoked_at is null and expires_at > now()",
                    userId,
                ),
                totalSessions = countBy("refresh_tokens", "user_id", userId),
            ),
            billing = AdminUserBillingCounts(
                subscriptions = countBy("user_subscriptions", "user_id", userId),
                invoices = countBy("invoices", "user_id", userId),
                paymentAttempts = queryCount(
                    "select count(*) from payment_attempts pa join invoices i on i.id = pa.invoice_id where i.user_id = ?",
                    userId,
                ),
                manualGrants = countBy("manual_grants", "user_id", userId),
                promotionRedemptions = countBy("promotion_redemptions", "user_id", userId),
                currentSubscriptionStatus = currentSubscription?.status,
                currentSubscription = currentSubscription,
            ),
            auditAndOperations = AdminUserAuditCounts(
                auditEvents = queryCount(
                    "select count(*) from account_audit_events where actor_user_id = ? or target_user_id = ?",
                    userId,
                    userId,
                ),
                deletionOperations = countBy("admin_user_deletion_operations", "target_user_id", userId),
            ),
        )
    }

    fun findIdentity(userId: UUID): AdminUserIdentityResponse? {
        return jdbcTemplate.query(
            """
            select
                u.id, u.email, u.phone_number, p.display_name, p.timezone, p.locale,
                u.role, u.status, u.email_verification_status, u.phone_verification_status,
                u.created_at, u.updated_at, u.deactivated_at
            from users u
            left join user_profiles p on p.user_id = u.id
            where u.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toIdentity() },
            userId,
        ).firstOrNull()
    }

    private fun findCurrentSubscription(userId: UUID): AdminUserSubscriptionSummary? {
        return jdbcTemplate.query(
            """
            select p.code as plan_code, p.name as plan_name, s.status, s.period_start, s.period_end,
                   s.grace_period_end, s.cancel_at_period_end
            from user_subscriptions s
            join subscription_plans p on p.id = s.plan_id
            where s.user_id = ?
            order by s.updated_at desc, s.id desc
            limit 1
            """.trimIndent(),
            { rs, _ ->
                AdminUserSubscriptionSummary(
                    planCode = rs.getString("plan_code"),
                    planName = rs.getString("plan_name"),
                    status = rs.getString("status"),
                    periodStart = rs.getTimestamp("period_start").toInstant(),
                    periodEnd = rs.getTimestamp("period_end")?.toInstant(),
                    gracePeriodEnd = rs.getTimestamp("grace_period_end")?.toInstant(),
                    cancelAtPeriodEnd = rs.getBoolean("cancel_at_period_end"),
                )
            },
            userId,
        ).firstOrNull()
    }

    fun countBy(table: String, column: String, userId: UUID): Long {
        return queryCount("select count(*) from $table where $column = ?", userId)
    }

    fun queryCount(sql: String, vararg parameters: Any): Long {
        return jdbcTemplate.queryForObject(sql, Long::class.java, *parameters) ?: 0L
    }

    private fun ResultSet.toSummary(): AdminUserSummaryResponse {
        return AdminUserSummaryResponse(
            id = getString("id"),
            email = getString("email"),
            phoneNumber = getString("phone_number"),
            displayName = getString("display_name"),
            role = getString("role"),
            status = getString("status"),
            emailVerificationStatus = getString("email_verification_status"),
            phoneVerificationStatus = getString("phone_verification_status"),
            subscriptionStatus = getString("subscription_status"),
            promotionRedemptions = getLong("promotion_redemptions"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    private fun ResultSet.toIdentity(): AdminUserIdentityResponse {
        return AdminUserIdentityResponse(
            id = getString("id"),
            email = getString("email"),
            phoneNumber = getString("phone_number"),
            displayName = getString("display_name"),
            timezone = getString("timezone"),
            locale = getString("locale"),
            role = getString("role"),
            status = getString("status"),
            emailVerificationStatus = getString("email_verification_status"),
            phoneVerificationStatus = getString("phone_verification_status"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            deactivatedAt = getTimestamp("deactivated_at")?.toInstant(),
        )
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
