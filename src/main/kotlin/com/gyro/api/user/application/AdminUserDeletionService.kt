package com.gyro.api.user.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.gyro.api.auth.application.AccountAuditService
import com.gyro.api.auth.domain.AccountAuditEventType
import com.gyro.api.common.error.DeletionAlreadyCompletedException
import com.gyro.api.common.error.DeletionConfirmationMismatchException
import com.gyro.api.common.error.DeletionInProgressException
import com.gyro.api.common.error.DeletionPreviewStaleException
import com.gyro.api.common.error.ProtectedAccountException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.time.TimeProvider
import com.gyro.api.subscription.application.CachedEntitlementService
import com.gyro.api.subscription.application.outbox.OutboxEventWriter
import com.gyro.api.subscription.application.outbox.SubscriptionEventPayload
import com.gyro.api.user.web.dto.AdminDeletionPlanEntry
import com.gyro.api.user.web.dto.AdminUserDeletionOperationRecord
import com.gyro.api.user.web.dto.AdminUserDeletionPreviewResponse
import com.gyro.api.user.web.dto.AdminUserDeletionResultResponse
import com.gyro.api.user.web.dto.AdminUserIdentityResponse
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

@Service
class AdminUserDeletionService(
    private val jdbcTemplate: JdbcTemplate,
    private val queryService: AdminUserQueryService,
    private val accountAuditService: AccountAuditService,
    private val cachedEntitlementService: CachedEntitlementService,
    private val outboxEventWriter: OutboxEventWriter,
    private val timeProvider: TimeProvider,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val secureRandom = SecureRandom()
    private val objectMapper = ObjectMapper()

    fun previewDeletion(actingAdminId: UUID, targetUserId: UUID): AdminUserDeletionPreviewResponse {
        val target = queryService.findIdentity(targetUserId) ?: throw ResourceNotFoundException("User")
        assertDeletable(actingAdminId, targetUserId, target)

        val deletePlan = buildDeletePlan(targetUserId)
        val retainPlan = buildRetainPlan(targetUserId)

        val token = randomToken()
        val expiresAt = timeProvider.now().plus(PREVIEW_TTL)
        val operationId = requireNotNull(
            transactionTemplate.execute {
                val id = jdbcTemplate.queryForObject(
                    """
                    insert into admin_user_deletion_operations (
                        target_user_id, acting_admin_id, status, preview_token_hash, preview_expires_at
                    ) values (?, ?, 'PREVIEWED', ?, ?)
                    returning id
                    """.trimIndent(),
                    UUID::class.java,
                    targetUserId,
                    actingAdminId,
                    sha256(token),
                    java.sql.Timestamp.from(expiresAt),
                )
                accountAuditService.record(
                    actorUserId = actingAdminId,
                    targetUserId = targetUserId,
                    eventType = AccountAuditEventType.ADMIN_USER_DELETION_PREVIEWED,
                    metadata = mapOf("operationId" to id.toString()),
                )
                id
            },
        )

        return AdminUserDeletionPreviewResponse(
            operationId = operationId.toString(),
            confirmationToken = token,
            expiresAt = expiresAt,
            target = target,
            confirmationValue = confirmationValueFor(target),
            deletePlan = deletePlan,
            retainPlan = retainPlan,
        )
    }

    fun confirmDeletion(
        actingAdminId: UUID,
        targetUserId: UUID,
        operationId: UUID,
        confirmationToken: String,
        confirmation: String,
        reason: String,
    ): AdminUserDeletionResultResponse {
        beginDeletion(actingAdminId, targetUserId, operationId, confirmationToken, confirmation, reason)
        try {
            return requireNotNull(transactionTemplate.execute {
                val (deletedCounts, retainedCounts) = executeDeletion(targetUserId)
                val completedAt = timeProvider.now()
                jdbcTemplate.update(
                    """
                    update admin_user_deletion_operations
                    set status = 'COMPLETED', domain_counts = ?::jsonb, completed_at = ?,
                        error_message = null, updated_at = now()
                    where id = ?
                    """.trimIndent(),
                    objectMapper.writeValueAsString(mapOf("deleted" to deletedCounts, "retained" to retainedCounts)),
                    java.sql.Timestamp.from(completedAt),
                    operationId,
                )
                accountAuditService.record(
                    actorUserId = actingAdminId,
                    targetUserId = targetUserId,
                    eventType = AccountAuditEventType.ADMIN_USER_DELETED,
                    reason = reason,
                    metadata = mapOf(
                        "operationId" to operationId.toString(),
                        "deletedTotal" to deletedCounts.values.sum(),
                    ),
                )
                outboxEventWriter.writeSubscriptionEvent(
                    aggregateId = targetUserId.toString(),
                    payload = SubscriptionEventPayload(
                        userId = targetUserId,
                        transitionType = "ACCOUNT_DELETED",
                        planId = 0,
                        periodStart = null,
                        periodEnd = null,
                        occurredAt = completedAt,
                    ),
                )
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            cachedEntitlementService.invalidate(targetUserId)
                        } catch (exception: Exception) {
                            log.error(
                                "event=admin_user_deletion stage=entitlement_cache_invalidation operationId={}",
                                operationId,
                                exception,
                            )
                        }
                    }
                })
                AdminUserDeletionResultResponse(
                    operationId = operationId.toString(),
                    targetUserId = targetUserId.toString(),
                    status = "COMPLETED",
                    deletedCounts = deletedCounts,
                    retainedCounts = retainedCounts,
                    completedAt = completedAt,
                )
            })
        } catch (exception: Exception) {
            markFailed(operationId, exception)
            accountAuditService.record(
                actorUserId = actingAdminId,
                targetUserId = targetUserId,
                eventType = AccountAuditEventType.ADMIN_USER_DELETION_FAILED,
                metadata = mapOf("operationId" to operationId.toString()),
            )
            log.error(
                "event=admin_user_deletion outcome=failure operationId={} requestReason=redacted",
                operationId,
                exception,
            )
            throw exception
        }
    }

    private fun assertDeletable(actingAdminId: UUID, targetUserId: UUID, target: AdminUserIdentityResponse) {
        if (targetUserId == actingAdminId) {
            denyAudit(actingAdminId, targetUserId, "self_deletion")
            throw ProtectedAccountException("Admins cannot delete their own account.")
        }
        if (target.role == "ADMIN") {
            denyAudit(actingAdminId, targetUserId, "admin_target")
            throw ProtectedAccountException("Admin accounts are protected and cannot be deleted.")
        }
        if (target.status == "DELETED") {
            val lastCompleted = jdbcTemplate.query(
                """
                select id from admin_user_deletion_operations
                where target_user_id = ? and status = 'COMPLETED'
                order by created_at desc limit 1
                """.trimIndent(),
                { rs, _ -> rs.getString("id") },
                targetUserId,
            ).firstOrNull() ?: "unknown"
            throw DeletionAlreadyCompletedException(lastCompleted)
        }
        val running = jdbcTemplate.queryForObject(
            "select count(*) from admin_user_deletion_operations where target_user_id = ? and status = 'RUNNING'",
            Long::class.java,
            targetUserId,
        ) ?: 0L
        if (running > 0) {
            throw DeletionInProgressException()
        }
    }

    private fun claimOperation(
        actingAdminId: UUID,
        targetUserId: UUID,
        operationId: UUID,
        confirmationToken: String,
        confirmation: String,
        reason: String,
    ) {
        val operation = jdbcTemplate.query(
                """
                select id, target_user_id, acting_admin_id, status, preview_token_hash, preview_expires_at
                from admin_user_deletion_operations
                where id = ?
                for update
                """.trimIndent(),
                { rs, _ ->
                    AdminUserDeletionOperationRecord(
                        id = UUID.fromString(rs.getString("id")),
                        targetUserId = UUID.fromString(rs.getString("target_user_id")),
                        actingAdminId = UUID.fromString(rs.getString("acting_admin_id")),
                        status = rs.getString("status"),
                        previewTokenHash = rs.getString("preview_token_hash"),
                        previewExpiresAt = rs.getTimestamp("preview_expires_at").toInstant(),
                    )
                },
                operationId,
            ).firstOrNull() ?: throw DeletionPreviewStaleException()

            when (operation.status) {
                "COMPLETED" -> throw DeletionAlreadyCompletedException(operation.id.toString())
                "RUNNING" -> throw DeletionInProgressException()
                // The prior destructive transaction rolled back and markFailed restored the
                // write barrier, so the same still-valid preview can be safely retried.
                "FAILED" -> Unit
            }

            // Prevent target or admin substitution between preview and confirmation.
            if (operation.targetUserId != targetUserId || operation.actingAdminId != actingAdminId) {
                denyAudit(actingAdminId, targetUserId, "preview_binding_mismatch")
                throw DeletionPreviewStaleException()
            }
            if (operation.previewTokenHash != sha256(confirmationToken)) {
                denyAudit(actingAdminId, targetUserId, "token_mismatch")
                throw DeletionPreviewStaleException()
            }
            if (operation.previewExpiresAt.isBefore(timeProvider.now())) {
                throw DeletionPreviewStaleException()
            }

            val target = queryService.findIdentity(targetUserId) ?: throw ResourceNotFoundException("User")
            assertDeletable(actingAdminId, targetUserId, target)
            if (!confirmationMatches(target, confirmation)) {
                denyAudit(actingAdminId, targetUserId, "typed_confirmation_mismatch")
                throw DeletionConfirmationMismatchException()
            }

        jdbcTemplate.update(
            "update admin_user_deletion_operations set status = 'RUNNING', reason = ?, updated_at = now() where id = ?",
            reason.trim(),
            operationId,
        )
    }

    /**
     * Commits a user-visible write barrier before destructive work begins. Existing access
     * tokens are rejected by [JwtAuthFilter] as soon as this state is visible.
     */
    private fun beginDeletion(
        actingAdminId: UUID,
        targetUserId: UUID,
        operationId: UUID,
        confirmationToken: String,
        confirmation: String,
        reason: String,
    ) {
        transactionTemplate.execute {
            val previousStatus = jdbcTemplate.queryForObject(
                "select status from users where id = ? for update",
                String::class.java,
                targetUserId,
            ) ?: throw ResourceNotFoundException("User")
            claimOperation(actingAdminId, targetUserId, operationId, confirmationToken, confirmation, reason)
            val changed = jdbcTemplate.update(
                """
                update users set status = 'DELETION_IN_PROGRESS', updated_at = now()
                where id = ? and status in ('ACTIVE', 'DISABLED', 'PENDING_VERIFICATION', 'DEACTIVATED')
                """.trimIndent(),
                targetUserId,
            )
            check(changed == 1) { "Deletion barrier could not be established for user $targetUserId." }
            jdbcTemplate.update(
                "update admin_user_deletion_operations set previous_user_status = ? where id = ?",
                previousStatus,
                operationId,
            )
            jdbcTemplate.update("delete from refresh_tokens where user_id = ?", targetUserId)
        }
    }

    /**
     * Removes user-owned rows in dependency order and replaces the principal row with a
     * non-identifying tombstone. Runs inside one transaction; either everything is deleted
     * or nothing is.
     */
    private fun executeDeletion(userId: UUID): Pair<Map<String, Long>, Map<String, Long>> {
        val deleted = linkedMapOf<String, Long>()

        // Access-token invalidation and write rejection happen before this transaction begins.
        deleted["refreshTokens"] = delete("delete from refresh_tokens where user_id = ?", userId)
        deleted["idempotencyKeys"] =
            delete("delete from idempotency_keys where user_id = ?", userId)

        deleted["diaryEntries"] = delete("delete from diary_entries where user_id = ?", userId)
        deleted["diaryDays"] = delete("delete from diary_days where user_id = ?", userId)

        deleted["mealItems"] = delete(
            "delete from meal_items where meal_id in (select id from meals where owner_user_id = ?)",
            userId,
        )
        deleted["meals"] = delete("delete from meals where owner_user_id = ?", userId)

        deleted["foodFavorites"] = delete("delete from food_favorites where user_id = ?", userId)
        deleted["recentFoods"] = delete("delete from recent_foods where user_id = ?", userId)
        // Custom-food children (localizations, aliases, nutrition, portions, search terms,
        // source metadata, other users' favorites/recents) cascade with the food row.
        deleted["customFoods"] = delete("delete from foods where owner_user_id = ?", userId)

        deleted["dailyScores"] = delete("delete from daily_scores where user_id = ?", userId)
        deleted["coachInsightImpressions"] =
            delete("delete from coach_insight_impressions where user_id = ?", userId)
        deleted["planTargetRegimeBoundaries"] =
            delete("delete from plan_target_regime_boundaries where user_id = ?", userId)
        deleted["planSchedules"] = delete("delete from plan_schedules where user_id = ?", userId)
        deleted["nutritionPlans"] = delete("delete from nutrition_plans where user_id = ?", userId)
        deleted["weightEntries"] = delete("delete from weight_entries where user_id = ?", userId)
        deleted["userProfiles"] = delete("delete from user_profiles where user_id = ?", userId)
        deleted["telegramLinkTokens"] = delete("delete from notification_telegram_link_tokens where user_id = ?", userId)
        deleted["telegramLinkCodes"] = delete("delete from notification_telegram_link_codes where claimed_user_id = ?", userId)
        deleted["telegramEndpoints"] = delete("delete from notification_telegram_endpoints where user_id = ?", userId)

        val retained = linkedMapOf<String, Long>()
        retained["subscriptions"] = queryService.countBy("user_subscriptions", "user_id", userId)
        retained["subscriptionEvents"] = queryService.countBy("subscription_events", "user_id", userId)
        retained["invoices"] = queryService.countBy("invoices", "user_id", userId)
        retained["paymentAttempts"] = queryService.queryCount(
            "select count(*) from payment_attempts pa join invoices i on i.id = pa.invoice_id where i.user_id = ?",
            userId,
        )
        retained["manualGrants"] = queryService.queryCount(
            "select count(*) from manual_grants where user_id = ? or granted_by = ? or revoked_by = ?",
            userId,
            userId,
            userId,
        )
        retained["promotionRedemptions"] = queryService.countBy("promotion_redemptions", "user_id", userId)
        retained["affiliateCommissions"] = queryService.countBy("affiliate_commissions", "referred_user_id", userId)
        retained["affiliateAccountLinks"] = queryService.countBy("affiliates", "linked_user_id", userId)
        retained["notificationIntents"] = queryService.countBy("notification_intents", "user_id", userId)
        retained["notificationDeliveries"] = queryService.queryCount(
            "select count(*) from notification_deliveries nd join notification_intents ni on ni.id = nd.intent_id where ni.user_id = ?",
            userId,
        )
        retained["notificationAttempts"] = queryService.queryCount(
            """
            select count(*) from notification_attempts na
            join notification_deliveries nd on nd.id = na.delivery_id
            join notification_intents ni on ni.id = nd.intent_id
            where ni.user_id = ?
            """.trimIndent(),
            userId,
        )
        retained["auditEvents"] = queryService.queryCount(
            "select count(*) from account_audit_events where actor_user_id = ? or target_user_id = ?",
            userId,
            userId,
        )

        val tombstoned = jdbcTemplate.update(
            """
            update users
            set email = null,
                phone_number = null,
                password_hash = ?,
                status = 'DELETED',
                email_verification_status = 'UNVERIFIED',
                phone_verification_status = 'UNVERIFIED',
                deactivated_at = now(),
                updated_at = now()
            where id = ?
            """.trimIndent(),
            "{deleted}${randomToken()}",
            userId,
        )
        check(tombstoned == 1) { "Tombstone update affected $tombstoned rows for user $userId." }

        return deleted to retained
    }

    private fun markFailed(operationId: UUID, exception: Exception) {
        try {
            transactionTemplate.execute {
                jdbcTemplate.update(
                    """
                    update admin_user_deletion_operations
                    set status = 'FAILED', error_message = ?, updated_at = now()
                    where id = ?
                    """.trimIndent(),
                    "DELETE_TRANSACTION_FAILED",
                    operationId,
                )
                jdbcTemplate.update(
                    """
                    update users set status = coalesce((
                        select previous_user_status from admin_user_deletion_operations where id = ?
                    ), 'ACTIVE'), updated_at = now()
                    where id = (select target_user_id from admin_user_deletion_operations where id = ?)
                      and status = 'DELETION_IN_PROGRESS'
                    """.trimIndent(),
                    operationId,
                    operationId,
                )
            }
        } catch (markException: Exception) {
            log.error("event=admin_user_deletion stage=mark_failed operationId={}", operationId, markException)
        }
    }

    private fun buildDeletePlan(userId: UUID): List<AdminDeletionPlanEntry> {
        fun entry(domain: String, records: Long) = AdminDeletionPlanEntry(domain, "DELETE", records)
        return listOf(
            entry("sessions", queryService.countBy("refresh_tokens", "user_id", userId)),
            entry("profile", queryService.countBy("user_profiles", "user_id", userId)),
            entry("nutritionPlans", queryService.countBy("nutrition_plans", "user_id", userId)),
            entry("planTargetRegimeBoundaries", queryService.countBy("plan_target_regime_boundaries", "user_id", userId)),
            entry("planSchedules", queryService.countBy("plan_schedules", "user_id", userId)),
            entry("weightEntries", queryService.countBy("weight_entries", "user_id", userId)),
            entry("dailyScores", queryService.countBy("daily_scores", "user_id", userId)),
            entry(
                "coachInsightImpressions",
                queryService.countBy("coach_insight_impressions", "user_id", userId),
            ),
            entry("diaryDays", queryService.countBy("diary_days", "user_id", userId)),
            entry("diaryEntries", queryService.countBy("diary_entries", "user_id", userId)),
            entry("meals", queryService.countBy("meals", "owner_user_id", userId)),
            entry(
                "mealItems",
                queryService.queryCount(
                    "select count(*) from meal_items mi join meals m on m.id = mi.meal_id where m.owner_user_id = ?",
                    userId,
                ),
            ),
            entry("customFoods", queryService.countBy("foods", "owner_user_id", userId)),
            entry("foodFavorites", queryService.countBy("food_favorites", "user_id", userId)),
            entry("recentFoods", queryService.countBy("recent_foods", "user_id", userId)),
            entry("notificationSettings", queryService.countBy("notification_user_settings", "user_id", userId)),
            entry("notificationPreferences", queryService.countBy("notification_preferences", "user_id", userId)),
            entry("notificationEndpointHealth", queryService.countBy("notification_endpoint_health", "user_id", userId)),
            entry("telegramLinkTokens", queryService.countBy("notification_telegram_link_tokens", "user_id", userId)),
            entry("telegramLinkCodes", queryService.countBy("notification_telegram_link_codes", "claimed_user_id", userId)),
            entry("telegramEndpoints", queryService.countBy("notification_telegram_endpoints", "user_id", userId)),
        )
    }

    private fun buildRetainPlan(userId: UUID): List<AdminDeletionPlanEntry> {
        fun entry(domain: String, records: Long) = AdminDeletionPlanEntry(domain, "RETAIN_WITH_TOMBSTONE", records)
        return listOf(
            entry("subscriptions", queryService.countBy("user_subscriptions", "user_id", userId)),
            entry("invoices", queryService.countBy("invoices", "user_id", userId)),
            entry(
                "paymentAttempts",
                queryService.queryCount(
                    "select count(*) from payment_attempts pa join invoices i on i.id = pa.invoice_id where i.user_id = ?",
                    userId,
                ),
            ),
            entry("manualGrants", queryService.countBy("manual_grants", "user_id", userId)),
            entry("promotionRedemptions", queryService.countBy("promotion_redemptions", "user_id", userId)),
            entry("affiliateCommissions", queryService.countBy("affiliate_commissions", "referred_user_id", userId)),
            entry("affiliateAccountLinks", queryService.countBy("affiliates", "linked_user_id", userId)),
            entry("notificationIntents", queryService.countBy("notification_intents", "user_id", userId)),
            entry(
                "notificationDeliveries",
                queryService.queryCount(
                    "select count(*) from notification_deliveries nd join notification_intents ni on ni.id = nd.intent_id where ni.user_id = ?",
                    userId,
                ),
            ),
            entry(
                "notificationAttempts",
                queryService.queryCount(
                    """
                    select count(*) from notification_attempts na
                    join notification_deliveries nd on nd.id = na.delivery_id
                    join notification_intents ni on ni.id = nd.intent_id
                    where ni.user_id = ?
                    """.trimIndent(),
                    userId,
                ),
            ),
            entry(
                "auditEvents",
                queryService.queryCount(
                    "select count(*) from account_audit_events where actor_user_id = ? or target_user_id = ?",
                    userId,
                    userId,
                ),
            ),
        )
    }

    private fun confirmationValueFor(target: AdminUserIdentityResponse): String {
        return target.email ?: target.phoneNumber ?: target.id
    }

    private fun confirmationMatches(target: AdminUserIdentityResponse, confirmation: String): Boolean {
        val typed = confirmation.trim()
        return typed.equals(target.email ?: "", ignoreCase = true) ||
            typed == (target.phoneNumber ?: "") ||
            typed.equals(target.id, ignoreCase = true)
    }

    private fun denyAudit(actingAdminId: UUID, targetUserId: UUID, reasonCode: String) {
        accountAuditService.record(
            actorUserId = actingAdminId,
            targetUserId = targetUserId,
            eventType = AccountAuditEventType.ADMIN_USER_DELETION_DENIED,
            metadata = mapOf("denyReason" to reasonCode),
        )
    }

    private fun delete(sql: String, vararg parameters: Any): Long {
        return jdbcTemplate.update(sql, *parameters).toLong()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    private companion object {
        private val PREVIEW_TTL: Duration = Duration.ofMinutes(10)
    }
}
