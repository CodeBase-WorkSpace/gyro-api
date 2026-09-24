package com.gyro.api.user

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.user.domain.UserDataDeletionManifest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Fails when a user-referencing relation is added to the schema without an explicit deletion or
 * retention policy in [UserDataDeletionManifest], so new user-owned tables cannot silently fall
 * outside the admin deletion workflow.
 */
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class UserDeletionManifestContractTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `every foreign key referencing users has an explicit deletion policy`() {
        val fkColumns = jdbcTemplate.query(
            """
            select tc.table_name, kcu.column_name
            from information_schema.table_constraints tc
            join information_schema.key_column_usage kcu on tc.constraint_name = kcu.constraint_name
            join information_schema.constraint_column_usage ccu on tc.constraint_name = ccu.constraint_name
            where tc.constraint_type = 'FOREIGN KEY'
              and tc.table_schema = 'public'
              and ccu.table_name = 'users'
            """.trimIndent(),
        ) { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }

        assertCovered(fkColumns)
    }

    @Test
    fun `every user-id shaped column has an explicit deletion policy even without a foreign key`() {
        val namedColumns = jdbcTemplate.query(
            """
            select table_name, column_name
            from information_schema.columns
            where table_schema = 'public'
              and table_name <> 'users'
              and (
                  column_name in ('user_id', 'owner_user_id', 'actor_user_id', 'target_user_id',
                                  'acting_admin_id', 'granted_by', 'revoked_by')
              )
            """.trimIndent(),
        ) { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }

        assertCovered(namedColumns)
    }

    @Test
    fun `every directly deleted user relation has a write barrier trigger`() {
        val triggerDefinitions = jdbcTemplate.query(
            """
            select c.relname, pg_get_triggerdef(t.oid)
            from pg_trigger t join pg_class c on c.oid = t.tgrelid
            where not t.tgisinternal
              and pg_get_triggerdef(t.oid) like '%gyro_reject_write_during_account_deletion%'
            """.trimIndent(),
        ) { rs, _ -> rs.getString(1) to rs.getString(2) }

        val uncovered = UserDataDeletionManifest.entries
            .filter { it.writeBarrierRequired }
            .filter { entry -> triggerDefinitions.none { (table, definition) -> table == entry.table && definition.contains(entry.column) } }

        assert(uncovered.isEmpty()) {
            "DELETE relations without a deletion write barrier: " +
                uncovered.joinToString { "${it.table}.${it.column}" }
        }
    }

    private fun assertCovered(columns: List<Pair<String, String>>) {
        check(columns.isNotEmpty()) { "Expected at least one user-referencing column in the schema." }
        val unclassified = columns.filter { (table, column) ->
            UserDataDeletionManifest.policyFor(table, column) == null
        }
        assert(unclassified.isEmpty()) {
            "User-referencing relations without a deletion policy in UserDataDeletionManifest: " +
                unclassified.joinToString { "${it.first}.${it.second}" } +
                ". Add each to the manifest with DELETE or RETAIN_WITH_TOMBSTONE and wire it into " +
                "AdminUserDeletionService."
        }
    }
}
