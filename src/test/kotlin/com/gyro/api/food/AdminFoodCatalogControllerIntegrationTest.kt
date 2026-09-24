package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.*
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
class AdminFoodCatalogControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val adminId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val createdFoodIds = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        insertUser(adminId, "catalog-admin-${UUID.randomUUID()}@example.com", "ADMIN")
        insertUser(userId, "catalog-user-${UUID.randomUUID()}@example.com", "USER")
    }

    @AfterEach
    fun tearDown() {
        createdFoodIds.forEach { publicId ->
            jdbcTemplate.update("delete from foods where public_id = ?", publicId)
        }
        createdFoodIds.clear()
        jdbcTemplate.update(
            "delete from account_audit_events where actor_user_id = ? or target_user_id = ?",
            adminId,
            adminId,
        )
        jdbcTemplate.update("delete from users where id in (?, ?)", userId, adminId)
    }

    @Test
    fun `catalog endpoints reject anonymous and non-admin users`() {
        mockMvc.get("/api/v1/admin/catalog/foods").andExpect { status { isUnauthorized() } }

        mockMvc.get("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(userId, "ROLE_USER")))
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(userId, "ROLE_USER")))
            contentType = MediaType.APPLICATION_JSON
            content = createFoodBody(name = "Should Not Exist")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin creates a complete food that appears in normal search and detail`() {
        val result = createFood(
            name = "Curated Admin Granola",
            body = createFoodBody(
                name = "Curated Admin Granola",
                brandName = "GyroBrand",
                faName = "گرانولای ادمین",
                aliases = listOf("en" to "admin granola snack"),
                portions = listOf(
                    """{"servingUnitCode": "GRAM", "amount": 100, "sortOrder": 0}""",
                    """{"servingUnitCode": "CUP", "amount": 1, "gramWeight": 45, "sortOrder": 1}""",
                    """{"servingUnitCode": "TABLESPOON", "amount": 2, "gramWeight": 12, "sortOrder": 2}""",
                ),
            ),
        )
        val foodId = extractFoodId(result)

        mockMvc.get("/api/v1/admin/catalog/foods/$foodId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.brandName") { value("GyroBrand") }
            jsonPath("$.portions.length()") { value(3) }
            jsonPath("$.localizations.length()") { value(2) }
            jsonPath("$.aliases.length()") { value(1) }
            jsonPath("$.lockVersion") { value(0) }
        }

        // The food is visible to normal users through search (by name, alias, and brand) and detail.
        listOf("Curated Admin Granola", "admin granola snack", "GyroBrand").forEach { query ->
            mockMvc.get("/api/v1/foods") {
                with(authentication(authToken(userId, "ROLE_USER")))
                param("query", query)
                param("locale", "en")
            }.andExpect {
                status { isOk() }
                content { string(containsString(foodId)) }
            }
        }

        mockMvc.get("/api/v1/foods/$foodId") {
            with(authentication(authToken(userId, "ROLE_USER")))
            param("locale", "fa")
        }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("گرانولای ادمین") }
            jsonPath("$.portions.length()") { value(3) }
        }

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from account_audit_events where actor_user_id = ? and event_type = 'ADMIN_CATALOG_CHANGED'",
            Long::class.java,
            adminId,
        ) ?: 0
        assert(auditCount >= 1) { "Expected an ADMIN_CATALOG_CHANGED audit event." }
    }

    @Test
    fun `admin list filters and inconsistent macros produce warnings`() {
        val result = createFood(
            name = "Macro Mismatch Bar",
            body = createFoodBody(
                name = "Macro Mismatch Bar",
                calories = "900.00",
            ),
        )
        val foodId = extractFoodId(result)
        assert(result.response.contentAsString.contains("MACRO_INCONSISTENT")) {
            "Expected MACRO_INCONSISTENT warning."
        }

        mockMvc.get("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", "Macro Mismatch Bar")
            param("source", "GYRO_CURATED")
            param("archived", "false")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(greaterThanOrEqualTo(1)) }
            content { string(containsString(foodId)) }
        }
    }

    @Test
    fun `stale lock version is rejected and fresh update succeeds`() {
        val result = createFood(name = "Lockable Yogurt", body = createFoodBody(name = "Lockable Yogurt"))
        val foodId = extractFoodId(result)

        mockMvc.put("/api/v1/admin/catalog/foods/$foodId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = updateFoodBody(name = "Lockable Yogurt Updated", expectedLockVersion = 7)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CATALOG_VERSION_CONFLICT") }
        }

        mockMvc.put("/api/v1/admin/catalog/foods/$foodId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = updateFoodBody(name = "Lockable Yogurt Updated", expectedLockVersion = 0)
        }.andExpect {
            status { isOk() }
            jsonPath("$.food.name") { value("Lockable Yogurt Updated") }
            jsonPath("$.food.lockVersion") { value(1) }
        }
    }

    @Test
    fun `archive hides the food from normal search and restore brings it back`() {
        val result = createFood(name = "Archivable Soup", body = createFoodBody(name = "Archivable Soup"))
        val foodId = extractFoodId(result)

        mockMvc.post("/api/v1/admin/catalog/foods/$foodId/archive") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/v1/foods/$foodId") {
            with(authentication(authToken(userId, "ROLE_USER")))
        }.andExpect { status { isNotFound() } }

        mockMvc.post("/api/v1/admin/catalog/foods/$foodId/restore") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/v1/foods/$foodId") {
            with(authentication(authToken(userId, "ROLE_USER")))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `admin catalog endpoints do not expose or mutate user custom foods`() {
        val customFoodId = "custom_${UUID.randomUUID().toString().replace("-", "")}"
        createdFoodIds += customFoodId
        jdbcTemplate.update(
            """
            insert into foods (
                public_id, owner_user_id, type, source, name, normalized_name,
                data_quality, curation_status, is_searchable, created_at, updated_at
            ) values (?, ?, 'CUSTOM', 'USER_CURATED', 'Private Food', 'private food',
                'USER_SUBMITTED', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            customFoodId,
            userId,
        )

        mockMvc.get("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", "Private Food")
        }.andExpect {
            status { isOk() }
            content { string(not(containsString(customFoodId))) }
        }

        mockMvc.get("/api/v1/admin/catalog/foods/duplicates") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("name", "private food")
        }.andExpect {
            status { isOk() }
            content { string(not(containsString(customFoodId))) }
        }

        // Opt-in visibility: user foods appear only with an explicit ownership filter and are never editable.
        listOf("USER", "ALL").forEach { ownership ->
            mockMvc.get("/api/v1/admin/catalog/foods") {
                with(authentication(authToken(adminId, "ROLE_ADMIN")))
                param("query", "Private Food")
                param("ownership", ownership)
            }.andExpect {
                status { isOk() }
                content { string(containsString(customFoodId)) }
                jsonPath("$.items[0].id") { value(customFoodId) }
                jsonPath("$.items[0].editable") { value(false) }
            }
        }

        mockMvc.get("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("query", "Private Food")
            param("ownership", "BOGUS")
        }.andExpect { status { isBadRequest() } }

        // Detail is readable (so the admin can inspect or copy it) but flagged read-only.
        mockMvc.get("/api/v1/admin/catalog/foods/$customFoodId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.editable") { value(false) }
        }

        mockMvc.put("/api/v1/admin/catalog/foods/$customFoodId") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = updateFoodBody(name = "Overwritten Private Food", expectedLockVersion = 0)
        }.andExpect { status { isNotFound() } }

        mockMvc.post("/api/v1/admin/catalog/foods/$customFoodId/archive") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect { status { isNotFound() } }

        mockMvc.post("/api/v1/admin/catalog/foods/$customFoodId/restore") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect { status { isNotFound() } }

        val food = jdbcTemplate.queryForMap(
            "select name, archived_at from foods where public_id = ?",
            customFoodId,
        )
        assert(food["name"] == "Private Food")
        assert(food["archived_at"] == null)
    }

    @Test
    fun `create rejects unknown serving units and duplicate locales`() {
        mockMvc.post("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = createFoodBody(name = "Broken Unit Food", baseUnitCode = "NOT_A_UNIT")
        }.andExpect {
            status { isBadRequest() }
            content { string(containsString("nutrition.baseUnitCode")) }
        }
    }

    @Test
    fun `duplicate suggestions find existing foods before creation`() {
        val result = createFood(name = "Unique Duplicate Probe", body = createFoodBody(name = "Unique Duplicate Probe"))
        val foodId = extractFoodId(result)

        mockMvc.get("/api/v1/admin/catalog/foods/duplicates") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            param("name", "unique duplicate probe")
        }.andExpect {
            status { isOk() }
            content { string(containsString(foodId)) }
        }
    }

    @Test
    fun `serving units and categories are listed and categories can be created`() {
        mockMvc.get("/api/v1/admin/catalog/serving-units") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
        }.andExpect {
            status { isOk() }
            content { string(containsString("GRAM")) }
        }

        val categoryName = "Admin Category ${UUID.randomUUID().toString().take(8)}"
        val creation = mockMvc.post("/api/v1/admin/catalog/categories") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$categoryName"}"""
        }.andExpect { status { isCreated() } }.andReturn()

        try {
            mockMvc.get("/api/v1/admin/catalog/categories") {
                with(authentication(authToken(adminId, "ROLE_ADMIN")))
            }.andExpect {
                status { isOk() }
                content { string(containsString(categoryName)) }
            }
        } finally {
            val categoryId = creation.response.contentAsString
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
            jdbcTemplate.update("delete from food_categories where id = ?::uuid", categoryId)
        }
    }

    private fun createFood(name: String, body: String): MvcResult {
        val result = mockMvc.post("/api/v1/admin/catalog/foods") {
            with(authentication(authToken(adminId, "ROLE_ADMIN")))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isCreated() }
            jsonPath("$.food.name") { value(name) }
        }.andReturn()

        val foodId = extractFoodId(result)
        createdFoodIds += foodId
        return result
    }

    private fun extractFoodId(result: MvcResult): String {
        val content = result.response.contentAsString
        return content.substringAfter("\"id\":\"food_").substringBefore("\"").let { "food_$it" }
    }

    private fun createFoodBody(
        name: String,
        brandName: String? = null,
        faName: String? = null,
        calories: String = "450.00",
        baseUnitCode: String = "GRAM",
        aliases: List<Pair<String, String>> = emptyList(),
        portions: List<String> = listOf("""{"servingUnitCode": "GRAM", "amount": 100, "sortOrder": 0}"""),
    ): String {
        val localizations = buildList {
            add("""{"locale": "en", "displayName": "$name"}""")
            if (faName != null) {
                add("""{"locale": "fa", "displayName": "$faName"}""")
            }
        }
        val aliasJson = aliases.map { (locale, alias) -> """{"locale": "$locale", "alias": "$alias"}""" }

        return """
            {
              "name": "$name",
              ${if (brandName != null) "\"brandName\": \"$brandName\"," else ""}
              "curationStatus": "REVIEWED",
              "isSearchable": true,
              "localizations": [${localizations.joinToString(",")}],
              "aliases": [${aliasJson.joinToString(",")}],
              "nutrition": {
                "baseQuantity": 100,
                "baseUnitCode": "$baseUnitCode",
                "calories": $calories,
                "protein": 12.00,
                "carbs": 60.00,
                "fat": 18.00,
                "fiber": 6.00,
                "sugar": 20.00,
                "sodium": 150.00
              },
              "portions": [${portions.joinToString(",")}]
            }
        """.trimIndent()
    }

    private fun updateFoodBody(name: String, expectedLockVersion: Int): String {
        return """
            {
              "name": "$name",
              "curationStatus": "REVIEWED",
              "isSearchable": true,
              "localizations": [{"locale": "en", "displayName": "$name"}],
              "aliases": [],
              "nutrition": {
                "baseQuantity": 100,
                "baseUnitCode": "GRAM",
                "calories": 450.00,
                "protein": 12.00,
                "carbs": 60.00,
                "fat": 18.00
              },
              "portions": [{"servingUnitCode": "GRAM", "amount": 100, "sortOrder": 0}],
              "expectedLockVersion": $expectedLockVersion
            }
        """.trimIndent()
    }

    private fun insertUser(id: UUID, email: String, role: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, phone_number, password_hash, role,
                email_verification_status, phone_verification_status, status, created_at, updated_at
            ) values (?, ?, null, '{noop}Password123', ?, 'VERIFIED', 'VERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
            role,
        )
    }

    private fun authToken(id: UUID, role: String): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(id.toString(), null, listOf(SimpleGrantedAuthority(role)))
    }
}
