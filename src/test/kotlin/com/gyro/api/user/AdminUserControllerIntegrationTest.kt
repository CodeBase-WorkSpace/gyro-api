package com.gyro.api.user

import com.gyro.api.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import com.gyro.api.user.application.AdminUserDeletionService
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class AdminUserControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val adminUserDeletionService: AdminUserDeletionService,
) {
    private val adminId = UUID.randomUUID()
    private val secondAdminId = UUID.randomUUID()
    private val targetId = UUID.randomUUID()
    private val bystanderId = UUID.randomUUID()
    private val targetEmail = "deletion-target-${targetId.toString().take(8)}@example.com"
    private val bystanderEmail = "bystander-${bystanderId.toString().take(8)}@example.com"

    @BeforeEach
    fun setUp() {
        insertUser(adminId, "admin-${UUID.randomUUID()}@example.com", null, "ADMIN")
        insertUser(secondAdminId, "admin2-${UUID.randomUUID()}@example.com", null, "ADMIN")
        insertUser(targetId, targetEmail, "+989121230001", "USER")
        insertUser(bystanderId, bystanderEmail, "+989121230002", "USER")
        insertProfile(targetId, "Deletion Target")
        insertProfile(bystanderId, "Bystander User")
    }

    @AfterEach
    fun tearDown() {
        listOf(targetId, bystanderId).forEach { userId ->
            jdbcTemplate.update("delete from notification_intents where user_id = ?", userId)
            jdbcTemplate.update("delete from payment_attempts where invoice_id in (select id from invoices where user_id = ?)", userId)
            jdbcTemplate.update("delete from invoices where user_id = ?", userId)
            jdbcTemplate.update("delete from user_subscriptions where user_id = ?", userId)
            jdbcTemplate.update("delete from idempotency_keys where scope like ?", "%$userId%")
            listOf(
                "diary_entries" to "user_id",
                "diary_days" to "user_id",
                "daily_scores" to "user_id",
                "coach_insight_impressions" to "user_id",
                "plan_schedules" to "user_id",
                "nutrition_plans" to "user_id",
                "weight_entries" to "user_id",
                "food_favorites" to "user_id",
                "recent_foods" to "user_id",
                "refresh_tokens" to "user_id",
                "notification_telegram_link_tokens" to "user_id",
                "notification_telegram_link_codes" to "claimed_user_id",
                "notification_telegram_endpoints" to "user_id",
                "user_profiles" to "user_id",
            ).forEach { (table, column) ->
                jdbcTemplate.update("delete from $table where $column = ?", userId)
            }
            jdbcTemplate.update("delete from meal_items where meal_id in (select id from meals where owner_user_id = ?)", userId)
            jdbcTemplate.update("delete from meals where owner_user_id = ?", userId)
            jdbcTemplate.update("delete from foods where owner_user_id = ?", userId)
        }
        jdbcTemplate.update(
            "delete from admin_user_deletion_operations where target_user_id in (?, ?, ?, ?) or acting_admin_id in (?, ?)",
            targetId, bystanderId, adminId, secondAdminId, adminId, secondAdminId,
        )
        jdbcTemplate.update(
            "delete from account_audit_events where actor_user_id in (?, ?, ?, ?) or target_user_id in (?, ?, ?, ?)",
            adminId, secondAdminId, targetId, bystanderId, adminId, secondAdminId, targetId, bystanderId,
        )
        jdbcTemplate.update("delete from users where id in (?, ?, ?, ?)", adminId, secondAdminId, targetId, bystanderId)
    }

    @Test
    fun `user endpoints reject anonymous and non-admin callers`() {
        mockMvc.get("/api/v1/admin/users").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/admin/users") {
            with(authentication(authToken(targetId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/users/$bystanderId/deletion-preview") {
            with(authentication(authToken(targetId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin searches users by email phone display name uuid and filters`() {
        listOf(
            targetEmail.substringBefore("@"),
            "+98912123000",
            "Deletion Target",
            targetId.toString(),
        ).forEach { query ->
            mockMvc.get("/api/v1/admin/users") {
                with(authentication(authToken(adminId, "ROLE_ADMIN")))
                param("query", query)
            }.andExpect {
                status { isOk() }
                content { string(containsString(targetId.toString())) }
            }
        }

        mockMvc.get("/api/v1/admin/users") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", targetEmail)
            param("role", "ADMIN")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(0) }
        }

        mockMvc.get("/api/v1/admin/users") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", targetEmail)
            param("status", "ACTIVE")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(1) }
            jsonPath("$.items[0].id") { value(targetId.toString()) }
        }
    }

    @Test
    fun `user detail returns categorized counts without secret fields`() {
        seedOwnedData(targetId, phoneSuffix = "1")

        mockMvc.get("/api/v1/admin/users/$targetId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.identity.email") { value(targetEmail) }
            jsonPath("$.hasProfile") { value(true) }
            jsonPath("$.health.nutritionPlans") { value(1) }
            jsonPath("$.health.weightEntries") { value(1) }
            jsonPath("$.foodAndDiary.diaryEntries") { value(1) }
            jsonPath("$.foodAndDiary.meals") { value(1) }
            jsonPath("$.foodAndDiary.customFoods") { value(1) }
            jsonPath("$.authentication.totalSessions") { value(1) }
            jsonPath("$.billing.invoices") { value(1) }
            jsonPath("$.billing.currentSubscriptionStatus") { value("ACTIVE") }
            content { string(not(containsString("password"))) }
            content { string(not(containsString("token_hash"))) }
        }
    }

    @Test
    fun `full deletion removes every owned record keeps evidence and does not touch other users`() {
        seedOwnedData(targetId, phoneSuffix = "1")
        seedOwnedData(bystanderId, phoneSuffix = "2")

        val preview = previewDeletion(targetId)
        val operationId = preview.field("operationId")
        val token = preview.field("confirmationToken")
        assert(preview.raw.contains("\"confirmationValue\":\"$targetEmail\""))
        assert(preview.raw.contains("\"domain\":\"notificationIntents\""))
        assert(preview.raw.contains("\"domain\":\"notificationDeliveries\""))
        assert(preview.raw.contains("\"domain\":\"notificationAttempts\""))
        assert(preview.raw.contains("\"domain\":\"telegramEndpoints\""))

        // Wrong token → stale preview.
        confirmDeletion(targetId, operationId, "0".repeat(64), targetEmail).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DELETION_PREVIEW_STALE") }
        }

        // Wrong typed confirmation → mismatch.
        confirmDeletion(targetId, operationId, token, "wrong@example.com").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("DELETION_CONFIRMATION_MISMATCH") }
        }

        // Correct confirmation completes the deletion.
        confirmDeletion(targetId, operationId, token, targetEmail).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.deletedCounts.customFoods") { value(1) }
            jsonPath("$.deletedCounts.telegramLinkTokens") { value(1) }
            jsonPath("$.deletedCounts.telegramEndpoints") { value(1) }
            jsonPath("$.retainedCounts.invoices") { value(1) }
            jsonPath("$.retainedCounts.notificationIntents") { value(1) }
            jsonPath("$.retainedCounts.notificationDeliveries") { value(1) }
            jsonPath("$.retainedCounts.notificationAttempts") { value(1) }
        }

        // Every owned domain is empty for the target.
        val ownedQueries = mapOf(
            "refresh_tokens" to "select count(*) from refresh_tokens where user_id = ?",
            "user_profiles" to "select count(*) from user_profiles where user_id = ?",
            "nutrition_plans" to "select count(*) from nutrition_plans where user_id = ?",
            "plan_schedules" to "select count(*) from plan_schedules where user_id = ?",
            "weight_entries" to "select count(*) from weight_entries where user_id = ?",
            "daily_scores" to "select count(*) from daily_scores where user_id = ?",
            "coach_insight_impressions" to "select count(*) from coach_insight_impressions where user_id = ?",
            "diary_days" to "select count(*) from diary_days where user_id = ?",
            "diary_entries" to "select count(*) from diary_entries where user_id = ?",
            "meals" to "select count(*) from meals where owner_user_id = ?",
            "foods" to "select count(*) from foods where owner_user_id = ?",
            "food_favorites" to "select count(*) from food_favorites where user_id = ?",
            "recent_foods" to "select count(*) from recent_foods where user_id = ?",
            "notification_telegram_link_tokens" to "select count(*) from notification_telegram_link_tokens where user_id = ?",
            "notification_telegram_link_codes" to "select count(*) from notification_telegram_link_codes where claimed_user_id = ?",
            "notification_telegram_endpoints" to "select count(*) from notification_telegram_endpoints where user_id = ?",
        )
        ownedQueries.forEach { (table, sql) ->
            val remaining = jdbcTemplate.queryForObject(sql, Long::class.java, targetId) ?: -1
            assert(remaining == 0L) { "Expected zero rows in $table for deleted user, found $remaining." }
        }
        val idempotencyRemaining = jdbcTemplate.queryForObject(
            "select count(*) from idempotency_keys where scope like ?",
            Long::class.java,
            "%$targetId%",
        ) ?: -1
        assert(idempotencyRemaining == 0L) { "Expected idempotency keys to be removed." }

        // Principal row is a non-identifying tombstone.
        val tombstone = jdbcTemplate.queryForMap("select email, phone_number, status from users where id = ?", targetId)
        assert(tombstone["email"] == null && tombstone["phone_number"] == null && tombstone["status"] == "DELETED")

        // Financial evidence survives, keyed to the tombstone.
        val invoices = jdbcTemplate.queryForObject("select count(*) from invoices where user_id = ?", Long::class.java, targetId)
        assert(invoices == 1L) { "Expected retained invoice." }
        val notificationIntents = jdbcTemplate.queryForObject(
            "select count(*) from notification_intents where user_id = ?",
            Long::class.java,
            targetId,
        )
        assert(notificationIntents == 1L) { "Expected retained notification evidence." }

        // The bystander's data is untouched.
        val bystanderDiary = jdbcTemplate.queryForObject(
            "select count(*) from diary_entries where user_id = ?",
            Long::class.java,
            bystanderId,
        )
        assert(bystanderDiary == 1L) { "Expected bystander diary to remain." }

        // Duplicate confirmation is a safe no-op conflict.
        confirmDeletion(targetId, operationId, token, targetEmail).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DELETION_ALREADY_COMPLETED") }
        }

        // A new preview against the deleted user is rejected with the completed operation reference.
        mockMvc.post("/api/v1/admin/users/$targetId/deletion-preview") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DELETION_ALREADY_COMPLETED") }
        }

        // The deleted identity no longer matches search.
        mockMvc.get("/api/v1/admin/users") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", targetEmail)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(0) }
        }
    }

    @Test
    fun `write boundary rejects a request that authenticated before deletion barrier`() {
        seedOwnedData(targetId, phoneSuffix = "1")
        jdbcTemplate.update("update users set status = 'DELETION_IN_PROGRESS' where id = ?", targetId)

        listOf(
            "update user_profiles set display_name = 'Must not persist' where user_id = ?",
            "update diary_entries set display_name_snapshot = 'Must not persist' where user_id = ?",
            "update meals set name = 'Must not persist' where owner_user_id = ?",
            "update idempotency_keys set request_hash = 'must-not-persist' where user_id = ?",
        ).forEach { sql -> assertWriteBarrier(sql, targetId) }
    }

    @Test
    fun `deactivated user can be permanently deleted`() {
        jdbcTemplate.update("update users set status = 'DEACTIVATED' where id = ?", targetId)
        val preview = previewDeletion(targetId)

        confirmDeletion(targetId, preview.field("operationId"), preview.field("confirmationToken"), targetEmail)
            .andExpect { status { isOk() }; jsonPath("$.status") { value("COMPLETED") } }
    }

    private fun assertWriteBarrier(sql: String, vararg parameters: Any) {
        val exception = assertThrows<Exception> { jdbcTemplate.update(sql, *parameters) }
        var cause: Throwable? = exception
        while (cause != null && cause !is java.sql.SQLException) cause = cause.cause
        assert((cause as? java.sql.SQLException)?.sqlState == "23U01") {
            "Expected deletion-barrier SQLSTATE 23U01 but was ${(cause as? java.sql.SQLException)?.sqlState}"
        }
    }

    @Test
    fun `deletion barrier waits for an in-flight user write and removes its row afterwards`() {
        seedOwnedData(targetId, phoneSuffix = "1")
        val preview = previewDeletion(targetId)
        val operationId = UUID.fromString(preview.field("operationId"))
        val token = preview.field("confirmationToken")

        val connection = dataSource.connection
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Transaction 1: a user-owned write whose trigger takes FOR SHARE on the users row,
            // then pauses before commit — the exact race window from the review.
            connection.autoCommit = false
            connection.prepareStatement(
                """
                insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source)
                values (?, current_date - 5, 80, 80, 'KG', 'MANUAL')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, targetId)
                statement.executeUpdate()
            }

            // Transaction 2: the deletion barrier must block behind the in-flight write.
            val deletion = executor.submit<Any?> {
                adminUserDeletionService.confirmDeletion(
                    actingAdminId = adminId,
                    targetUserId = targetId,
                    operationId = operationId,
                    confirmationToken = token,
                    confirmation = targetEmail,
                    reason = "Concurrency ordering test",
                )
            }
            assertThrows<TimeoutException>("Deletion must wait for the open user-owned write") {
                deletion.get(1500, TimeUnit.MILLISECONDS)
            }

            // Once the write commits, deletion proceeds and must remove the raced row too.
            connection.commit()
            deletion.get(30, TimeUnit.SECONDS)
        } finally {
            runCatching { connection.rollback() }
            runCatching { connection.close() }
            executor.shutdownNow()
        }

        val remainingWeights = jdbcTemplate.queryForObject(
            "select count(*) from weight_entries where user_id = ?",
            Long::class.java,
            targetId,
        )
        assert(remainingWeights == 0L) { "Expected the raced weight entry to be deleted, found $remainingWeights." }
        val status = jdbcTemplate.queryForObject("select status from users where id = ?", String::class.java, targetId)
        assert(status == "DELETED") { "Expected DELETED tombstone, found $status." }
    }

    @Test
    fun `rows cannot escape deletion by reassigning ownership to an active user`() {
        seedOwnedData(targetId, phoneSuffix = "1")
        jdbcTemplate.update("update users set status = 'DELETION_IN_PROGRESS' where id = ?", targetId)

        assertWriteBarrier("update meals set owner_user_id = ? where owner_user_id = ?", bystanderId, targetId)
        assertWriteBarrier("update foods set owner_user_id = ? where owner_user_id = ?", bystanderId, targetId)

        val escapedMeals = jdbcTemplate.queryForObject(
            "select count(*) from meals where owner_user_id = ?",
            Long::class.java,
            bystanderId,
        )
        assert(escapedMeals == 0L) { "Expected no meals reassigned away from the deleting user." }
    }

    @Test
    fun `real user endpoint returns a stable conflict while account deletion is in progress`() {
        jdbcTemplate.update("update users set status = 'DELETION_IN_PROGRESS' where id = ?", targetId)

        mockMvc.post("/api/v1/weight-entries") {
            with(authentication(authToken(targetId, "ROLE_USER")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"recordedDate": "2026-07-01", "weight": 80.5, "unit": "KG"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACCOUNT_DELETION_IN_PROGRESS") }
        }
    }

    @Test
    fun `self deletion and admin targets are rejected as protected accounts`() {
        mockMvc.post("/api/v1/admin/users/$adminId/deletion-preview") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROTECTED_ACCOUNT") }
        }

        mockMvc.post("/api/v1/admin/users/$secondAdminId/deletion-preview") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROTECTED_ACCOUNT") }
        }
    }

    @Test
    fun `preview bound to one admin cannot be confirmed by another`() {
        val preview = previewDeletion(targetId)
        val operationId = preview.field("operationId")
        val token = preview.field("confirmationToken")

        mockMvc.post("/api/v1/admin/users/$targetId/delete") {
            with(authentication(authToken(secondAdminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = confirmBody(operationId, token, targetEmail)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DELETION_PREVIEW_STALE") }
        }
    }

    private class PreviewResult(val raw: String) {
        fun field(name: String): String = raw.substringAfter("\"$name\":\"").substringBefore("\"")
    }

    private fun previewDeletion(userId: UUID): PreviewResult {
        val result = mockMvc.post("/api/v1/admin/users/$userId/deletion-preview") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect { status { isOk() } }.andReturn()
        return PreviewResult(result.response.contentAsString)
    }

    private fun confirmDeletion(userId: UUID, operationId: String, token: String, confirmation: String) =
        mockMvc.post("/api/v1/admin/users/$userId/delete") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = confirmBody(operationId, token, confirmation)
        }

    private fun confirmBody(operationId: String, token: String, confirmation: String) = """
        {
          "operationId": "$operationId",
          "confirmationToken": "$token",
          "confirmation": "$confirmation",
          "reason": "Integration test deletion"
        }
    """.trimIndent()

    private fun seedOwnedData(userId: UUID, phoneSuffix: String) {
        jdbcTemplate.update(
            """
            insert into refresh_tokens (id, user_id, token_hash, expires_at, family_id, created_at)
            values (gen_random_uuid(), ?, ?, now() + interval '7 days', gen_random_uuid(), now())
            """.trimIndent(),
            userId,
            "hash-${UUID.randomUUID()}",
        )
        jdbcTemplate.update(
            """
            insert into idempotency_keys (id, scope, user_id, idempotency_key, request_hash, response_status, response_body, created_at, expires_at)
            values (gen_random_uuid(), ?, ?, ?, 'req-hash', 200, '{}', now(), now() + interval '1 day')
            """.trimIndent(),
            "foods:custom:create:$userId",
            userId,
            "key-${UUID.randomUUID()}",
        )

        val planId = jdbcTemplate.queryForObject(
            """
            insert into nutrition_plans (user_id, start_date, timezone, calories, protein, carbs, fat)
            values (?, current_date, 'Asia/Tehran', 2000, 120, 220, 70)
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
        )
        jdbcTemplate.update(
            """
            insert into plan_schedules (user_id, nutrition_plan_id, schedule_type, active_from, macro_adjustment_mode, weekday_targets, date_overrides, schedule_snapshot)
            values (?, ?, 'FLAT', current_date, 'FIXED_GRAMS', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
            """.trimIndent(),
            userId,
            planId,
        )
        jdbcTemplate.update(
            """
            insert into weight_entries (user_id, recorded_date, weight_kg, display_weight, display_unit, source)
            values (?, current_date, 82.5, 82.5, 'KG', 'MANUAL')
            """.trimIndent(),
            userId,
        )
        jdbcTemplate.update(
            """
            insert into daily_scores (user_id, local_date, score, mode, formula_name, formula_version, breakdown, finalized_at)
            values (?, current_date - 1, 80, 'GOAL_ADHERENCE', 'daily-score', 'v1', '{}'::jsonb, now())
            """.trimIndent(),
            userId,
        )

        val diaryDayId = jdbcTemplate.queryForObject(
            "insert into diary_days (user_id, diary_date, timezone) values (?, current_date, 'Asia/Tehran') returning id",
            UUID::class.java,
            userId,
        )
        jdbcTemplate.update(
            """
            insert into diary_entries (
                diary_day_id, user_id, diary_date, meal_type, source_type, source_metadata,
                display_name_snapshot, serving_quantity_snapshot, serving_unit_code_snapshot, serving_unit_name_snapshot
            ) values (?, ?, current_date, 'LUNCH', 'MANUAL', '{}'::jsonb, 'Seeded Entry', 100, 'GRAM', 'گرم')
            """.trimIndent(),
            diaryDayId,
            userId,
        )

        val foodId = jdbcTemplate.queryForObject(
            """
            insert into foods (public_id, owner_user_id, type, source, name, normalized_name, data_quality, curation_status)
            values (?, ?, 'CUSTOM', 'USER_CURATED', 'Seeded Custom Food', 'seeded custom food', 'USER_SUBMITTED', 'REVIEWED')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "custom_${UUID.randomUUID().toString().replace("-", "")}",
            userId,
        )
        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (food_id, base_quantity, base_unit_id, calories, protein, carbs, fat)
            select ?, 100, id, 200, 10, 20, 5 from serving_units where code = 'GRAM'
            """.trimIndent(),
            foodId,
        )
        jdbcTemplate.update("insert into food_favorites (user_id, food_id) values (?, ?)", userId, foodId)
        jdbcTemplate.update("insert into recent_foods (user_id, food_id) values (?, ?)", userId, foodId)

        val mealId = jdbcTemplate.queryForObject(
            "insert into meals (owner_user_id, name, normalized_name) values (?, 'Seeded Meal', 'seeded meal') returning id",
            UUID::class.java,
            userId,
        )
        jdbcTemplate.update(
            """
            insert into meal_items (meal_id, food_id, quantity, serving_unit_id)
            select ?, ?, 1, id from serving_units where code = 'GRAM'
            """.trimIndent(),
            mealId,
            foodId,
        )

        val subscriptionPlanId = jdbcTemplate.queryForObject(
            "select id from subscription_plans where code = 'ADVANCED'",
            Long::class.java,
        ) ?: error("ADVANCED plan is required")
        jdbcTemplate.update(
            """
            insert into user_subscriptions (user_id, plan_id, status, period_start, period_end, cancel_at_period_end)
            values (?, ?, 'ACTIVE', now(), now() + interval '30 days', false)
            """.trimIndent(),
            userId,
            subscriptionPlanId,
        )
        jdbcTemplate.update(
            """
            insert into invoices (
                id, user_id, plan_id, period_start, period_end, amount_due, currency,
                amount_after_discount, discount_currency, status, manual, created_at, updated_at
            ) values (gen_random_uuid(), ?, ?, now(), now() + interval '30 days', 1990000, 'IRR', 1990000, 'IRR', 'PAID', false, now(), now())
            """.trimIndent(),
            userId,
            subscriptionPlanId,
        )
        val telegramFingerprint = userId.toString().replace("-", "").repeat(2)
        jdbcTemplate.update(
            """
            insert into notification_telegram_link_tokens (user_id, token_hash, expires_at)
            values (?, ?, now() + interval '10 minutes')
            """.trimIndent(),
            userId,
            telegramFingerprint,
        )
        jdbcTemplate.update(
            """
            insert into notification_telegram_endpoints (
                user_id, bot_identity, telegram_user_ciphertext, chat_id_ciphertext, key_version,
                user_fingerprint, chat_fingerprint, state, linked_at, verified_at, last_inbound_at
            ) values (?, 'deletion-test-bot', ?, ?, 'v1', ?, ?, 'ACTIVE', now(), now(), now())
            """.trimIndent(),
            userId,
            "encrypted-user-$userId",
            "encrypted-chat-$userId",
            telegramFingerprint,
            telegramFingerprint.reversed(),
        )
        val notificationIntentId = jdbcTemplate.queryForObject(
            """
            insert into notification_intents (
                user_id, notification_type, category, risk, route_strategy, occurred_at, scheduled_at, expires_at,
                idempotency_key, source_type, source_reference, request_id, template_data, status, terminal_at
            ) values (?, 'CORE_PROBE', 'MANDATORY_TRANSACTIONAL', 'LOW', 'EXPLICIT_CHANNELS', now(), now(),
                now() + interval '10 minutes', ?, 'DELETION_TEST', ?, ?, '{}'::jsonb, 'COMPLETED', now())
            returning id
            """.trimIndent(),
            UUID::class.java,
            userId,
            "deletion-${UUID.randomUUID()}",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
        ) ?: error("Notification intent was not created")
        val notificationDeliveryId = jdbcTemplate.queryForObject(
            """
            insert into notification_deliveries (
                intent_id, channel, endpoint_reference, provider_request_id, adapter_key, template_key, template_version, template_locale,
                rendered_subject, rendered_plain_body, status, due_at, expires_at, attempt_count, content_purge_at
            ) values (?, 'EMAIL', 'internal:deletion-test', ?, 'log-only', 'notification-core-probe', 1, 'en',
                'Probe', 'Probe', 'DELIVERED', now(), now() + interval '10 minutes', 1, now() + interval '30 days')
            returning id
            """.trimIndent(),
            UUID::class.java,
            notificationIntentId,
            "gyro-notification-${UUID.randomUUID()}",
        ) ?: error("Notification delivery was not created")
        jdbcTemplate.update(
            """
            insert into notification_attempts (
                delivery_id, attempt_number, started_at, completed_at, duration_ms, outcome, classification
            ) values (?, 1, now(), now(), 0, 'SUCCESS', 'LOG_ONLY_SUCCESS')
            """.trimIndent(),
            notificationDeliveryId,
        )
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

    private fun insertProfile(userId: UUID, displayName: String) {
        jdbcTemplate.update(
            """
            insert into user_profiles (id, user_id, display_name, timezone, locale, created_at, updated_at)
            values (gen_random_uuid(), ?, ?, 'Asia/Tehran', 'fa-IR', now(), now())
            """.trimIndent(),
            userId,
            displayName,
        )
    }

    private fun authToken(id: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(id.toString(), null, listOf(SimpleGrantedAuthority(role)))
    }
}
