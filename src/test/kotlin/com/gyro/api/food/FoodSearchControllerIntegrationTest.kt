package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.food.application.FoodSearchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class FoodSearchControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val foodSearchService: FoodSearchService,
) {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `search returns paginated ranked foods and hides another users custom foods`() {
        val currentUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-current-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "food-search-other-${System.nanoTime()}@example.com")
        val searchToken = UUID.randomUUID().toString().replace("-", "")
        val exactFoodName = searchToken
        val exactNormalizedName = searchToken
        val fuzzyFoodName = "$exactFoodName Breast"
        val fuzzyNormalizedName = "$exactNormalizedName breast"
        val hiddenFoodName = "$exactFoodName Private"
        val hiddenNormalizedName = "$exactNormalizedName private"

        val exactFoodId = seedFood(
            publicId = "food_phase3_exact_${System.nanoTime()}",
            sourceFoodId = "phase3-exact-${System.nanoTime()}",
            name = exactFoodName,
            normalizedName = exactNormalizedName,
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedSearchTerm(exactFoodId, exactFoodName, exactNormalizedName, BigDecimal("10.0"))

        val fuzzyFoodId = seedFood(
            publicId = "food_phase3_fuzzy_${System.nanoTime()}",
            sourceFoodId = "phase3-fuzzy-${System.nanoTime()}",
            name = fuzzyFoodName,
            normalizedName = fuzzyNormalizedName,
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("172.00"),
            ownerUserId = null,
        )
        seedSearchTerm(fuzzyFoodId, fuzzyFoodName, fuzzyNormalizedName, BigDecimal("7.5"))

        val hiddenCustomFoodId = seedFood(
            publicId = "food_phase3_hidden_${System.nanoTime()}",
            sourceFoodId = null,
            name = hiddenFoodName,
            normalizedName = hiddenNormalizedName,
            dataQuality = "USER_SUBMITTED",
            calories = BigDecimal("400.00"),
            ownerUserId = otherUserId,
        )
        seedSearchTerm(hiddenCustomFoodId, hiddenFoodName, hiddenNormalizedName, BigDecimal("20.0"))

        val directSearch = foodSearchService.search(
            currentUserId = currentUserId,
            query = exactNormalizedName,
            locale = null,
            type = null,
            favorite = null,
            recent = null,
            page = 0,
            size = 1,
        )
        assertEquals(2, directSearch.totalItems)

        val response = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", exactNormalizedName)
                .queryParam("page", "0")
                .queryParam("size", "1")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn()
            .response
            .contentAsString

        val root = objectMapper.readTree(response)
        assertEquals(exactFoodName, root["items"][0]["name"].asText())
        assertEquals("food_phase3_exact_", root["items"][0]["id"].asText().take("food_phase3_exact_".length))

        val secondPage = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", exactNormalizedName)
                .queryParam("page", "1")
                .queryParam("size", "1")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn()
            .response
            .contentAsString

        val secondPageItems = objectMapper.readTree(secondPage).get("items")
        val secondPageIds = (0 until secondPageItems.size()).map { index ->
            secondPageItems.get(index).get("id").asText()
        }
        assertTrue(secondPageIds.single().startsWith("food_phase3_fuzzy_"))
        assertFalse(secondPageIds.any { it.startsWith("food_phase3_hidden_") })
    }

    @Test
    fun `search displays reviewed food localization instead of matched alias`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-localized-${System.nanoTime()}@example.com")
        val localizedQuery = "مرغ تست ${System.nanoTime()}"

        val foodId = seedFood(
            publicId = "food_localized_alias_${System.nanoTime()}",
            sourceFoodId = "localized-alias-${System.nanoTime()}",
            name = "Chicken Breast",
            normalizedName = "chicken breast",
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedFoodLocalization(foodId, "fa", "سینه مرغ", "سینه مرغ")
        seedSearchTerm(
            foodId = foodId,
            term = localizedQuery,
            normalizedTerm = localizedQuery,
            weight = BigDecimal("8.0"),
            locale = "fa",
            termKind = "ALIAS",
        )

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", localizedQuery)
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
            .let { response ->
                val items = objectMapper.readTree(response)["items"]
                assertTrue(items.any { item ->
                    item["name"].asText() == "Chicken Breast" &&
                        item["displayName"].asText() == "سینه مرغ" &&
                        item["locale"].asText() == "fa"
                })
            }
    }

    @Test
    fun `search keeps modifier text in localized portion labels`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-portions-${System.nanoTime()}@example.com")

        val foodId = seedFood(
            publicId = "food_search_portion_${System.nanoTime()}",
            sourceFoodId = "portion-search-${System.nanoTime()}",
            name = "Chicken Breast",
            normalizedName = "chicken breast",
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedFoodLocalization(foodId, "fa", "سینه مرغ", "سینه مرغ")
        seedSearchTerm(foodId, "مرغ", "مرغ", BigDecimal("8.0"), locale = "fa", termKind = "ALIAS")
        seedServingPortion(foodId)

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", "مرغ")
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].portions[0].modifier").value("diced"))
            .andExpect(jsonPath("$.items[0].portions[0].displayText").value("1 فنجان diced (140 گرم)"))
    }

    @Test
    fun `search normalizes Farsi language tags and falls back to reviewed alias for display name`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-fa-ir-${System.nanoTime()}@example.com")
        val aliasText = "مرغ تست ${System.nanoTime()}"

        val foodId = seedFood(
            publicId = "food_fa_alias_${System.nanoTime()}",
            sourceFoodId = "fa-alias-${System.nanoTime()}",
            name = "Poultry, mechanically deboned, from mature hens, raw",
            normalizedName = "poultry mechanically deboned from mature hens raw",
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("243.00"),
            ownerUserId = null,
        )
        seedFoodAlias(foodId, "fa", aliasText, aliasText, reviewStatus = "UNREVIEWED")
        seedSearchTerm(
            foodId = foodId,
            term = aliasText,
            normalizedTerm = aliasText,
            weight = BigDecimal("8.0"),
            locale = "fa",
            termKind = "ALIAS",
        )

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", aliasText)
                .queryParam("locale", "fa-IR")
            .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].name").value("Poultry, mechanically deboned, from mature hens, raw"))
            .andExpect(jsonPath("$.items[0].displayName").value(aliasText))
            .andExpect(jsonPath("$.items[0].locale").value("fa"))
    }

    @Test
    fun `search hides baby foods from default results`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-baby-hidden-${System.nanoTime()}@example.com")
        val babyCategoryId = seedFoodCategory("baby foods")
        val searchToken = "hidecategory${UUID.randomUUID().toString().replace("-", "")}"
        val babyFoodPublicId = "food_baby_hidden_${System.nanoTime()}"

        val babyFoodId = seedFood(
            publicId = babyFoodPublicId,
            sourceFoodId = "baby-hidden-${System.nanoTime()}",
            name = "$searchToken infant puree",
            normalizedName = "$searchToken infant puree",
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("62.00"),
            ownerUserId = null,
            categoryId = babyCategoryId,
        )
        seedSearchTerm(babyFoodId, "$searchToken infant puree", "$searchToken infant puree", BigDecimal("12.0"))

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", searchToken)
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalItems").value(0))
            .andExpect(jsonPath("$.items.length()").value(0))

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", "infant $searchToken")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].id").value(babyFoodPublicId))
    }

    @Test
    fun `search ranks restaurant foods below generic foods`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-restaurant-rank-${System.nanoTime()}@example.com")
        val restaurantCategoryId = seedFoodCategory("restaurant foods")
        val searchToken = "rankcategory${UUID.randomUUID().toString().replace("-", "")}"

        val genericFoodPublicId = "food_generic_rank_${System.nanoTime()}"
        val genericFoodId = seedFood(
            publicId = genericFoodPublicId,
            sourceFoodId = "generic-rank-${System.nanoTime()}",
            name = "$searchToken chicken",
            normalizedName = "$searchToken chicken",
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedSearchTerm(genericFoodId, "$searchToken chicken", "$searchToken chicken", BigDecimal("8.0"))

        val restaurantFoodPublicId = "food_restaurant_rank_${System.nanoTime()}"
        val restaurantFoodId = seedFood(
            publicId = restaurantFoodPublicId,
            sourceFoodId = "restaurant-rank-${System.nanoTime()}",
            name = "$searchToken chicken restaurant",
            normalizedName = "$searchToken chicken restaurant",
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("225.00"),
            ownerUserId = null,
            categoryId = restaurantCategoryId,
        )
        seedSearchTerm(
            foodId = restaurantFoodId,
            term = "$searchToken chicken",
            normalizedTerm = "$searchToken chicken",
            weight = BigDecimal("8.0"),
        )

        val response = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", "$searchToken chicken")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()
            .response
            .contentAsString

        val items = objectMapper.readTree(response).get("items")
        assertEquals(genericFoodPublicId, items[0].get("id").asText())
        assertEquals(restaurantFoodPublicId, items[1].get("id").asText())
    }

    @Test
    fun `search ranks curated foods first for fa locale`() {
        val currentUserId = UUID.randomUUID()
        seedUser(currentUserId, "food-search-curated-fa-${System.nanoTime()}@example.com")
        // UUID-hex token instead of nanoTime digits: sibling tests seed fa terms with
        // nanoTime suffixes, and shared digit prefixes push pg_trgm similarity above the
        // search fallback threshold, leaking their rows into this query's results.
        val searchToken = "غذای ${UUID.randomUUID().toString().replace("-", "")}"

        val foundationFoodPublicId = "food_foundation_fa_${System.nanoTime()}"
        val foundationFoodId = seedFood(
            publicId = foundationFoodPublicId,
            sourceFoodId = "foundation-fa-${System.nanoTime()}",
            name = "$searchToken foundation",
            normalizedName = "$searchToken foundation",
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedSearchTerm(
            foodId = foundationFoodId,
            term = searchToken,
            normalizedTerm = searchToken,
            weight = BigDecimal("13.0"),
            locale = "fa",
        )

        val curatedFoodPublicId = "food_curated_fa_${System.nanoTime()}"
        val curatedFoodId = seedFood(
            publicId = curatedFoodPublicId,
            sourceFoodId = "curated-fa-${System.nanoTime()}",
            name = "$searchToken curated",
            normalizedName = "$searchToken curated",
            dataQuality = "CURATED",
            calories = BigDecimal("172.00"),
            ownerUserId = null,
        )
        seedSearchTerm(
            foodId = curatedFoodId,
            term = "$searchToken محلی",
            normalizedTerm = "$searchToken محلی",
            weight = BigDecimal("7.5"),
            locale = "fa",
        )

        val response = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", searchToken)
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(currentUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()
            .response
            .contentAsString

        val items = objectMapper.readTree(response).get("items")
        assertEquals(curatedFoodPublicId, items[0].get("id").asText())
        assertEquals(foundationFoodPublicId, items[1].get("id").asText())
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
        dataQuality: String,
        calories: BigDecimal,
        ownerUserId: UUID?,
        categoryId: UUID? = null,
    ): UUID {
        val foodId = UUID.randomUUID()
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
                category_id,
                name,
                normalized_name,
                data_quality,
                curation_status,
                is_searchable,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REVIEWED', true, now(), now())
            """.trimIndent(),
            foodId,
            publicId,
            ownerUserId,
            type,
            source,
            sourceFoodId,
            categoryId,
            name,
            normalizedName,
            dataQuality,
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
            select ?, 100.0000, id, ?, 31.00, 0.00, 3.60, 0.00, 0.00, 74.00, now(), now()
            from serving_units
            where code = 'GRAM'
            """.trimIndent(),
            foodId,
            calories,
        )

        return foodId
    }

    private fun seedFoodCategory(normalizedName: String): UUID {
        val existing = jdbcTemplate.query(
            """
            select id
            from food_categories
            where source = 'USDA_FDC'
              and normalized_name = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            normalizedName,
        ).firstOrNull()

        if (existing != null) {
            return existing
        }

        val categoryId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into food_categories (
                id,
                source,
                source_category_id,
                name,
                normalized_name,
                created_at,
                updated_at
            )
            values (?, 'USDA_FDC', ?, ?, ?, now(), now())
            """.trimIndent(),
            categoryId,
            "test-category-${normalizedName.replace(" ", "-")}",
            normalizedName,
            normalizedName,
        )
        return categoryId
    }

    private fun seedFoodLocalization(
        foodId: UUID,
        locale: String,
        displayName: String,
        normalizedDisplayName: String,
    ) {
        jdbcTemplate.update(
            """
            insert into food_localizations (
                food_id,
                locale,
                display_name,
                normalized_display_name,
                source,
                review_status,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, 'GYRO_CURATED', 'REVIEWED', now(), now())
            """.trimIndent(),
            foodId,
            locale,
            displayName,
            normalizedDisplayName,
        )
    }

    private fun seedFoodAlias(
        foodId: UUID,
        locale: String,
        alias: String,
        normalizedAlias: String,
        reviewStatus: String = "REVIEWED",
    ) {
        jdbcTemplate.update(
            """
            insert into food_aliases (
                food_id,
                locale,
                alias,
                normalized_alias,
                source,
                review_status,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, 'GYRO_CURATED', ?, now(), now())
            """.trimIndent(),
            foodId,
            locale,
            alias,
            normalizedAlias,
            reviewStatus,
        )
    }

    private fun seedSearchTerm(
        foodId: UUID,
        term: String,
        normalizedTerm: String,
        weight: BigDecimal,
        locale: String = "en",
        termKind: String = "NAME",
    ) {
        val textSearchConfig = if (locale == "fa") "simple" else "english"

        jdbcTemplate.update(
            """
            insert into food_search_terms (
                food_id,
                locale,
                term,
                normalized_term,
                term_kind,
                weight,
                search_vector,
                created_at
            )
            values (?, ?, ?, ?, ?, ?, to_tsvector(cast(? as regconfig), ?), now())
            """.trimIndent(),
            foodId,
            locale,
            term,
            normalizedTerm,
            termKind,
            weight,
            textSearchConfig,
            normalizedTerm,
        )
    }

    private fun seedServingPortion(foodId: UUID) {
        jdbcTemplate.update(
            """
            insert into food_serving_portions (
                food_id,
                serving_unit_id,
                amount,
                gram_weight,
                raw_unit_name,
                modifier,
                portion_description,
                source_portion_id,
                sort_order,
                created_at,
                updated_at
            )
            select ?, id, 1.0000, 140.0000, 'cup', 'diced', '1 cup, diced', ?, 1, now(), now()
            from serving_units
            where code = 'CUP'
            """.trimIndent(),
            foodId,
            "portion-search-${System.nanoTime()}",
        )
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
