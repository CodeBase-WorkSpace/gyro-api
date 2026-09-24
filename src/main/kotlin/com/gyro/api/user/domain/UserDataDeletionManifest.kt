package com.gyro.api.user.domain

/**
 * How a user-referencing relation is handled by admin permanent deletion.
 */
enum class UserDataPolicy {
    /** Rows are removed by the deletion orchestrator (directly or through a declared FK cascade). */
    DELETE,

    /**
     * Rows are kept for legal/financial/audit evidence. They stay keyed to the tombstoned user id,
     * which no longer carries any identifying attributes after deletion.
     */
    RETAIN_WITH_TOMBSTONE,
}

data class UserDataReference(
    val table: String,
    val column: String,
    val policy: UserDataPolicy,
    val note: String,
    val writeBarrierRequired: Boolean = policy == UserDataPolicy.DELETE,
)

/**
 * The single source of truth for which tables hold user data and what deletion does with them.
 *
 * Every foreign key (and FK-less user-id column) that points at `users` MUST be declared here.
 * `UserDeletionManifestContractTest` queries the live schema and fails when a new user-owned
 * relation is added without an explicit policy, so new tables cannot silently escape deletion.
 */
object UserDataDeletionManifest {

    val entries: List<UserDataReference> = listOf(
        // Deleted domains (private user data).
        UserDataReference("refresh_tokens", "user_id", UserDataPolicy.DELETE, "Sessions are revoked and removed first."),
        UserDataReference("idempotency_keys", "user_id", UserDataPolicy.DELETE, "Idempotent mutation responses owned by the user."),
        UserDataReference("user_profiles", "user_id", UserDataPolicy.DELETE, "Profile and preferences."),
        UserDataReference("nutrition_plans", "user_id", UserDataPolicy.DELETE, "Goals and calculator snapshots."),
        UserDataReference("plan_recalibration_suggestions", "user_id", UserDataPolicy.DELETE, "Recalibration suggestions and their audit basis."),
        UserDataReference("plan_target_regime_boundaries", "user_id", UserDataPolicy.DELETE, "Intentional target-regime boundaries for recalibration evidence."),
        UserDataReference("plan_schedules", "user_id", UserDataPolicy.DELETE, "Goal schedules."),
        UserDataReference("weight_entries", "user_id", UserDataPolicy.DELETE, "Weight history."),
        UserDataReference("daily_scores", "user_id", UserDataPolicy.DELETE, "Daily score history."),
        UserDataReference("coach_insight_impressions", "user_id", UserDataPolicy.DELETE, "Coach observation display history."),
        UserDataReference("diary_days", "user_id", UserDataPolicy.DELETE, "Diary days."),
        UserDataReference("diary_entries", "user_id", UserDataPolicy.DELETE, "Diary entries (no FK; deleted by user_id)."),
        UserDataReference("meals", "owner_user_id", UserDataPolicy.DELETE, "Meal templates; meal_items cascade with the meal."),
        UserDataReference("foods", "owner_user_id", UserDataPolicy.DELETE, "Custom foods; localization/alias/nutrition/portion/search-term children cascade with the food."),
        UserDataReference("food_favorites", "user_id", UserDataPolicy.DELETE, "Favorites."),
        UserDataReference("recent_foods", "user_id", UserDataPolicy.DELETE, "Recent food usage."),
        UserDataReference("notification_user_settings", "user_id", UserDataPolicy.DELETE, "Notification quiet-hour settings."),
        UserDataReference("notification_preferences", "user_id", UserDataPolicy.DELETE, "User-controlled notification consent."),
        UserDataReference("notification_preferences", "actor_user_id", UserDataPolicy.DELETE, "Self-service preference audit actor."),
        UserDataReference("notification_endpoint_health", "user_id", UserDataPolicy.DELETE, "Endpoint health keyed only by a destination fingerprint."),
        UserDataReference("notification_schedules", "user_id", UserDataPolicy.DELETE, "User-owned food reminder schedule and consent."),
        UserDataReference("notification_schedules", "actor_user_id", UserDataPolicy.DELETE, "Self-service schedule consent actor."),
        UserDataReference("notification_push_subscriptions", "user_id", UserDataPolicy.DELETE, "Encrypted Push endpoint material."),
        UserDataReference("notification_telegram_link_tokens", "user_id", UserDataPolicy.DELETE, "Short-lived Telegram account-link tokens."),
        UserDataReference("notification_telegram_link_codes", "claimed_user_id", UserDataPolicy.DELETE, "Claimed short-lived Telegram account-link codes."),
        UserDataReference("notification_telegram_endpoints", "user_id", UserDataPolicy.DELETE, "Encrypted personal Telegram endpoint material."),

        // Retained domains (financial and audit evidence keyed to the tombstoned principal).
        UserDataReference("user_subscriptions", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Subscription history is financial evidence."),
        UserDataReference("subscription_events", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Subscription transition audit."),
        UserDataReference("invoices", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Invoices and downstream payment attempts/events are financial evidence."),
        UserDataReference("manual_grants", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Grant history."),
        UserDataReference("trial_redemptions", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "One-trial-per-account/contact evidence; the contact identifier is stored only as a hash."),
        UserDataReference("trial_reconciliation_outcomes", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Terminal trial eligibility decisions prevent repeated reconciliation attempts."),
        UserDataReference("manual_grants", "granted_by", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Acting-admin reference."),
        UserDataReference("manual_grants", "revoked_by", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Acting-admin reference."),
        UserDataReference("promotion_redemptions", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Promotion usage is billing evidence."),
        UserDataReference("affiliates", "linked_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Optional affiliate account linkage remains keyed only to the tombstoned principal."),
        UserDataReference("affiliate_commissions", "referred_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Immutable affiliate earning evidence."),
        UserDataReference("subscription_prices", "created_by", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Price-change operator provenance."),
        UserDataReference("subscription_prices", "deactivated_by", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Price-retirement operator provenance."),
        UserDataReference("account_audit_events", "actor_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Audit trail integrity."),
        UserDataReference("account_audit_events", "target_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Audit trail integrity."),
        UserDataReference("notification_intents", "user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Notification delivery evidence and aggregate outcomes."),
        UserDataReference("admin_announcements", "recipient_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Targeted announcement audit retains only the tombstoned user UUID."),
        UserDataReference("admin_user_deletion_operations", "target_user_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Deletion evidence."),
        UserDataReference("admin_user_deletion_operations", "acting_admin_id", UserDataPolicy.RETAIN_WITH_TOMBSTONE, "Deletion evidence."),
    )

    fun policyFor(table: String, column: String): UserDataPolicy? =
        entries.firstOrNull { it.table == table && it.column == column }?.policy
}
