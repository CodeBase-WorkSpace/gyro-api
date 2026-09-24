package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class FoodFavoriteControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `favorite food returns updated state and prevents duplicate favorite rows`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-favorite-${System.nanoTime()}@example.com")

        val publicId = "food_favorite_system_${System.nanoTime()}"
        val foodRowId = seedFood(
            publicId = publicId,
            sourceFoodId = "food-favorite-system-${System.nanoTime()}",
            name = "Favorite Chicken",
            normalizedName = "favorite chicken",
            ownerUserId = null,
        )
        seedSearchTerm(foodRowId, "Favorite Chicken", "favorite chicken")

        mockMvc.perform(
            post("/api/v1/foods/{foodId}/favorite", publicId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.foodId").value(publicId))
            .andExpect(jsonPath("$.favorite").value(true))

        mockMvc.perform(
            post("/api/v1/foods/{foodId}/favorite", publicId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.foodId").value(publicId))
            .andExpect(jsonPath("$.favorite").value(true))

        val favoriteCount = favoriteCount(userId, foodRowId)
        assertEquals(1, favoriteCount)

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.favorite").value(true))

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", "favorite chicken")
                .queryParam("favorite", "true")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(publicId))
            .andExpect(jsonPath("$.items[0].favorite").value(true))
    }

    @Test
    fun `unfavorite food returns updated state and is idempotent for visible food`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-unfavorite-${System.nanoTime()}@example.com")

        val publicId = "food_unfavorite_system_${System.nanoTime()}"
        val foodRowId = seedFood(
            publicId = publicId,
            sourceFoodId = "food-unfavorite-system-${System.nanoTime()}",
            name = "Unfavorite Chicken",
            normalizedName = "unfavorite chicken",
            ownerUserId = null,
        )
        seedFavorite(userId, foodRowId)

        mockMvc.perform(
            delete("/api/v1/foods/{foodId}/favorite", publicId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.foodId").value(publicId))
            .andExpect(jsonPath("$.favorite").value(false))

        mockMvc.perform(
            delete("/api/v1/foods/{foodId}/favorite", publicId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.foodId").value(publicId))
            .andExpect(jsonPath("$.favorite").value(false))

        assertEquals(0, favoriteCount(userId, foodRowId))
    }

    @Test
    fun `favorite endpoints hide inaccessible foods`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "food-favorite-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "food-favorite-other-${System.nanoTime()}@example.com")

        val customPublicId = "food_favorite_private_${System.nanoTime()}"
        seedFood(
            publicId = customPublicId,
            sourceFoodId = null,
            name = "Private Favorite Bowl",
            normalizedName = "private favorite bowl",
            ownerUserId = ownerUserId,
        )

        mockMvc.perform(
            post("/api/v1/foods/{foodId}/favorite", customPublicId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            delete("/api/v1/foods/{foodId}/favorite", customPublicId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    private fun seedUser(id: UUID, email: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id,
                email,
                password_hash,
                role,
                email_verification_status,
                phone_verification_status,
                status,
                created_at,
                updated_at
            )
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            email,
        )
    }

    private fun seedFood(
        publicId: String,
        sourceFoodId: String?,
        name: String,
        normalizedName: String,
        ownerUserId: UUID?,
    ): UUID {
        val foodRowId = UUID.randomUUID()
        val type = if (ownerUserId == null) "SYSTEM" else "CUSTOM"
        val source = if (ownerUserId == null) "USDA_FDC" else "USER_CURATED"

        jdbcTemplate.update(
            """
            insert into foods (
                id,
                public_id,
                owner_user_id,
                type,
                source,
                source_food_id,
                name,
                normalized_name,
                data_quality,
                curation_status,
                is_searchable,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, 'FOUNDATION', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            foodRowId,
            publicId,
            ownerUserId,
            type,
            source,
            sourceFoodId,
            name,
            normalizedName,
        )

        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (
                food_id,
                base_quantity,
                base_unit_id,
                calories,
                protein,
                carbs,
                fat,
                fiber,
                sugar,
                sodium,
                created_at,
                updated_at
            )
            select ?, 100.0000, id, 165.00, 31.00, 0.00, 3.60, 0.00, 0.00, 74.00, now(), now()
            from serving_units
            where code = 'GRAM'
            """.trimIndent(),
            foodRowId,
        )

        return foodRowId
    }

    private fun seedSearchTerm(foodRowId: UUID, term: String, normalizedTerm: String) {
        jdbcTemplate.update(
            """
            insert into food_search_terms (
                food_id,
                term,
                normalized_term,
                locale,
                term_kind,
                weight,
                search_vector,
                created_at
            )
            values (?, ?, ?, 'en', 'NAME', ?, to_tsvector('english', ?), now())
            """.trimIndent(),
            foodRowId,
            term,
            normalizedTerm,
            BigDecimal("10.0"),
            normalizedTerm,
        )
    }

    private fun seedFavorite(userId: UUID, foodRowId: UUID) {
        jdbcTemplate.update(
            """
            insert into food_favorites (user_id, food_id, created_at)
            values (?, ?, now())
            """.trimIndent(),
            userId,
            foodRowId,
        )
    }

    private fun favoriteCount(userId: UUID, foodRowId: UUID): Int {
        return jdbcTemplate.queryForObject(
            """
            select count(*)
            from food_favorites
            where user_id = ? and food_id = ?
            """.trimIndent(),
            Int::class.java,
            userId,
            foodRowId,
        ) ?: 0
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
