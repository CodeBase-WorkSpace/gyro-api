package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
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
class CustomFoodControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `create custom food persists owner scoped food and returns nutrition summary`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-${System.nanoTime()}@example.com")

        val response = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "  Protein Oats  ",
                      "servingQuantity": 55.43219,
                      "servingUnit": "gram",
                      "calories": 212.349,
                      "protein": 10.4567,
                      "carbs": 34.7891,
                      "fat": 5.6789,
                      "fiber": 6.4321,
                      "sugar": 1.2345,
                      "sodium": 0.9876
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isString)
            .andExpect(jsonPath("$.type").value("CUSTOM"))
            .andExpect(jsonPath("$.name").value("Protein Oats"))
            .andExpect(jsonPath("$.servingQuantity").value(55.4322))
            .andExpect(jsonPath("$.servingUnit.code").value("GRAM"))
            .andExpect(jsonPath("$.calories").value(212.35))
            .andExpect(jsonPath("$.protein").value(10.46))
            .andExpect(jsonPath("$.carbs").value(34.79))
            .andExpect(jsonPath("$.fat").value(5.68))
            .andExpect(jsonPath("$.fiber").value(6.43))
            .andExpect(jsonPath("$.sugar").value(1.23))
            .andExpect(jsonPath("$.sodium").value(0.99))
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(response)["id"].toString().removeSurrounding("\"")
        assertTrue(createdId.startsWith("food_"))

        val persistedFood = jdbcTemplate.queryForMap(
            """
            select public_id, owner_user_id, type, source, name, normalized_name, is_searchable, archived_at
            from foods
            where public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals(createdId, persistedFood["public_id"])
        assertEquals(userId, persistedFood["owner_user_id"])
        assertEquals("CUSTOM", persistedFood["type"])
        assertEquals("USER_CURATED", persistedFood["source"])
        assertEquals("Protein Oats", persistedFood["name"])
        assertEquals("protein oats", persistedFood["normalized_name"])
        assertEquals(true, persistedFood["is_searchable"])
        assertEquals(null, persistedFood["archived_at"])

        val persistedNutrition = jdbcTemplate.queryForMap(
            """
            select nf.base_quantity, su.code as serving_unit_code, nf.calories, nf.protein, nf.carbs, nf.fat, nf.fiber, nf.sugar, nf.sodium
            from food_nutrition_facts nf
            join foods f on f.id = nf.food_id
            join serving_units su on su.id = nf.base_unit_id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals("55.4322", persistedNutrition["base_quantity"].toString())
        assertEquals("GRAM", persistedNutrition["serving_unit_code"])
        assertEquals("212.35", persistedNutrition["calories"].toString())
        assertEquals("10.46", persistedNutrition["protein"].toString())
        assertEquals("34.79", persistedNutrition["carbs"].toString())
        assertEquals("5.68", persistedNutrition["fat"].toString())
        assertEquals("6.43", persistedNutrition["fiber"].toString())
        assertEquals("1.23", persistedNutrition["sugar"].toString())
        assertEquals("0.99", persistedNutrition["sodium"].toString())

        val persistedSearchTerm = jdbcTemplate.queryForMap(
            """
            select locale, term, normalized_term, search_vector
            from food_search_terms fst
            join foods f on f.id = fst.food_id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals("en", persistedSearchTerm["locale"])
        assertEquals("Protein Oats", persistedSearchTerm["term"])
        assertEquals("protein oats", persistedSearchTerm["normalized_term"])
        assertNotNull(persistedSearchTerm["search_vector"])
    }

    @Test
    fun `create custom food rejects unknown serving unit`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-invalid-unit-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Oats",
                      "servingQuantity": 50,
                      "servingUnit": "bucket",
                      "calories": 212,
                      "protein": 10,
                      "carbs": 35,
                      "fat": 6
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("servingUnit is invalid."))
    }

    @Test
    fun `create custom food with same idempotency key returns cached response`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-idempotent-${System.nanoTime()}@example.com")
        val idempotencyKey = "custom-food-${System.nanoTime()}"
        val foodName = "Idempotent Oats ${System.nanoTime()}"
        val requestBody = """
            {
              "name": "$foodName",
              "servingQuantity": 50,
              "servingUnit": "GRAM",
              "calories": 200,
              "protein": 10,
              "carbs": 32,
              "fat": 4
            }
        """.trimIndent()

        val firstResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val replayedResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val firstJson = objectMapper.readTree(firstResponse)
        val replayedJson = objectMapper.readTree(replayedResponse)
        assertEquals(firstJson["id"].toString(), replayedJson["id"].toString())
        assertEquals(firstJson["name"].toString(), replayedJson["name"].toString())

        val createdRows = jdbcTemplate.queryForObject(
            """
            select count(*)
            from foods
            where owner_user_id = ? and name = ?
            """.trimIndent(),
            Int::class.java,
            userId,
            foodName,
        ) ?: 0
        assertEquals(1, createdRows)
    }

    @Test
    fun `create custom food with reused idempotency key and different payload returns conflict`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-idempotent-conflict-${System.nanoTime()}@example.com")
        val idempotencyKey = "custom-food-conflict-${System.nanoTime()}"

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("First Idempotent Food ${System.nanoTime()}"))
        )
            .andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Second Idempotent Food ${System.nanoTime()}"))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
    }

    @Test
    fun `update custom food requires ownership and updates nutrition and search term in place`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-update-${System.nanoTime()}@example.com")

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Oats",
                      "servingQuantity": 50,
                      "servingUnit": "GRAM",
                      "calories": 200,
                      "protein": 10,
                      "carbs": 32,
                      "fat": 4,
                      "fiber": 5,
                      "sugar": 1,
                      "sodium": 0.5
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")
        val originalRowIds = jdbcTemplate.queryForMap(
            """
            select f.id as food_row_id, nf.id as nutrition_row_id
            from foods f
            join food_nutrition_facts nf on nf.food_id = f.id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )

        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", createdId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "  Updated Protein Oats  ",
                      "servingQuantity": 60.55555,
                      "servingUnit": "serving",
                      "calories": 245.456,
                      "protein": 12.345,
                      "carbs": 40.111,
                      "fat": 6.222,
                      "fiber": 7.333,
                      "sugar": 2.444,
                      "sodium": 1.555
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(createdId))
            .andExpect(jsonPath("$.type").value("CUSTOM"))
            .andExpect(jsonPath("$.name").value("Updated Protein Oats"))
            .andExpect(jsonPath("$.servingQuantity").value(60.5556))
            .andExpect(jsonPath("$.servingUnit.code").value("SERVING"))
            .andExpect(jsonPath("$.calories").value(245.46))
            .andExpect(jsonPath("$.protein").value(12.35))
            .andExpect(jsonPath("$.carbs").value(40.11))
            .andExpect(jsonPath("$.fat").value(6.22))
            .andExpect(jsonPath("$.fiber").value(7.33))
            .andExpect(jsonPath("$.sugar").value(2.44))
            .andExpect(jsonPath("$.sodium").value(1.56))

        val updatedRowIds = jdbcTemplate.queryForMap(
            """
            select f.id as food_row_id, nf.id as nutrition_row_id
            from foods f
            join food_nutrition_facts nf on nf.food_id = f.id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals(originalRowIds["food_row_id"], updatedRowIds["food_row_id"])
        assertEquals(originalRowIds["nutrition_row_id"], updatedRowIds["nutrition_row_id"])

        val persistedFood = jdbcTemplate.queryForMap(
            """
            select name, normalized_name
            from foods
            where public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals("Updated Protein Oats", persistedFood["name"])
        assertEquals("updated protein oats", persistedFood["normalized_name"])

        val persistedSearchTerm = jdbcTemplate.queryForMap(
            """
            select locale, term, normalized_term
            from food_search_terms fst
            join foods f on f.id = fst.food_id
            where f.public_id = ? and fst.term_kind = 'NAME'
            """.trimIndent(),
            createdId,
        )
        assertEquals("en", persistedSearchTerm["locale"])
        assertEquals("Updated Protein Oats", persistedSearchTerm["term"])
        assertEquals("updated protein oats", persistedSearchTerm["normalized_term"])
    }

    @Test
    fun `update custom food rejects unknown serving unit`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-update-invalid-unit-${System.nanoTime()}@example.com")

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Oats",
                      "servingQuantity": 50,
                      "servingUnit": "GRAM",
                      "calories": 200,
                      "protein": 10,
                      "carbs": 32,
                      "fat": 4
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")

        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", createdId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Oats",
                      "servingQuantity": 50,
                      "servingUnit": "bucket",
                      "calories": 200,
                      "protein": 10,
                      "carbs": 32,
                      "fat": 4
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("servingUnit is invalid."))
    }

    @Test
    fun `update custom food hides foods the user does not own`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "custom-food-update-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "custom-food-update-other-${System.nanoTime()}@example.com")

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Private Bowl",
                      "servingQuantity": 1,
                      "servingUnit": "SERVING",
                      "calories": 540,
                      "protein": 42,
                      "carbs": 48,
                      "fat": 18
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")

        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", createdId)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validUpdatePayload())
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val systemFoodId = seedSystemFood()
        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", systemFoodId)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validUpdatePayload())
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `archive custom food requires ownership and hides it from normal reads while preserving nutrition`() {
        val ownerUserId = UUID.randomUUID()
        seedUser(ownerUserId, "custom-food-archive-${System.nanoTime()}@example.com")
        val foodName = "Archive Bowl ${System.nanoTime()}"
        val searchQuery = foodName.lowercase()

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "$foodName",
                      "servingQuantity": 1,
                      "servingUnit": "SERVING",
                      "calories": 540,
                      "protein": 42,
                      "carbs": 48,
                      "fat": 18
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")
        val originalRows = jdbcTemplate.queryForMap(
            """
            select f.id as food_row_id, nf.id as nutrition_row_id
            from foods f
            join food_nutrition_facts nf on nf.food_id = f.id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )

        mockMvc.perform(
            post("/api/v1/foods/custom/{foodId}/archive", createdId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.foodId").value(createdId))
            .andExpect(jsonPath("$.archived").value(true))

        val archivedAt = jdbcTemplate.queryForObject(
            """
            select archived_at
            from foods
            where public_id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            createdId,
        )
        assertNotNull(archivedAt)

        val preservedNutritionRows = jdbcTemplate.queryForObject(
            """
            select count(*)
            from food_nutrition_facts
            where food_id = ?
            """.trimIndent(),
            Int::class.java,
            originalRows["food_row_id"],
        ) ?: 0
        assertEquals(1, preservedNutritionRows)

        val preservedRows = jdbcTemplate.queryForMap(
            """
            select f.id as food_row_id, nf.id as nutrition_row_id
            from foods f
            join food_nutrition_facts nf on nf.food_id = f.id
            where f.public_id = ?
            """.trimIndent(),
            createdId,
        )
        assertEquals(originalRows["food_row_id"], preservedRows["food_row_id"])
        assertEquals(originalRows["nutrition_row_id"], preservedRows["nutrition_row_id"])

        mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", searchQuery)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", createdId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `archive custom food hides foods the user does not own`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "custom-food-archive-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "custom-food-archive-other-${System.nanoTime()}@example.com")

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Private Archive Bowl",
                      "servingQuantity": 1,
                      "servingUnit": "SERVING",
                      "calories": 540,
                      "protein": 42,
                      "carbs": 48,
                      "fat": 18
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")

        mockMvc.perform(
            post("/api/v1/foods/custom/{foodId}/archive", createdId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val systemFoodId = seedSystemFood()
        mockMvc.perform(
            post("/api/v1/foods/custom/{foodId}/archive", systemFoodId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `custom food authorization is owner scoped across read edit archive and favorite`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "custom-food-auth-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "custom-food-auth-other-${System.nanoTime()}@example.com")

        val createdResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Private Authorization Bowl ${System.nanoTime()}"))
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\"")

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", createdId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", createdId)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validUpdatePayload())
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            post("/api/v1/foods/custom/{foodId}/archive", createdId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            post("/api/v1/foods/{foodId}/favorite", createdId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        val systemFoodId = seedSystemFood()
        mockMvc.perform(
            get("/api/v1/foods/{foodId}", systemFoodId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(systemFoodId))

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", systemFoodId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(systemFoodId))
    }

    @Test
    fun `create custom food rejects invalid nutrient payload`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-invalid-payload-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Oats",
                      "servingQuantity": 0,
                      "servingUnit": "GRAM",
                      "calories": -1,
                      "protein": 10,
                      "carbs": 35,
                      "fat": 6
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.length()").value(2))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'servingQuantity')]").isNotEmpty)
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'calories')]").isNotEmpty)
    }

    @Test
    fun `created custom food is searchable only by its owner`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "custom-food-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "custom-food-other-${System.nanoTime()}@example.com")
        val foodName = "Secret Chicken Bowl ${System.nanoTime()}"
        val searchQuery = foodName.lowercase()

        val response = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "$foodName",
                      "servingQuantity": 1,
                      "servingUnit": "SERVING",
                      "calories": 540,
                      "protein": 42,
                      "carbs": 48,
                      "fat": 18
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        val createdId = objectMapper.readTree(response)["id"].toString().removeSurrounding("\"")

        val ownerSearch = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", searchQuery)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val ownerItems = objectMapper.readTree(ownerSearch)["items"]
        assertEquals(1, ownerItems.size())
        assertEquals(createdId, ownerItems[0]["id"].toString().removeSurrounding("\""))

        val otherSearch = mockMvc.perform(
            get("/api/v1/foods")
                .queryParam("query", searchQuery)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val otherItems = objectMapper.readTree(otherSearch)["items"]
        assertTrue(otherItems.isEmpty)

        val visibleToOther = jdbcTemplate.queryForObject(
            """
            select exists(
                select 1
                from foods
                where public_id = ?
                  and owner_user_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            createdId,
            otherUserId,
        ) ?: true
        assertFalse(visibleToOther)
    }

    @Test
    fun `free plan blocks third active custom food`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-free-limit-${System.nanoTime()}@example.com")

        createCustomFoodViaApi(userId, "Free Custom Food One")
        createCustomFoodViaApi(userId, "Free Custom Food Two")

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Free Custom Food Three"))
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"))
            .andExpect(jsonPath("$.metadata.supportReasonCode").value("PLAN_LIMIT_REACHED"))
            .andExpect(jsonPath("$.metadata.featureKey").value("higher_limits"))
            .andExpect(jsonPath("$.metadata.limitName").value("custom_foods"))
            .andExpect(jsonPath("$.metadata.limitValue").value("2"))
    }

    @Test
    fun `advanced plan allows more than two custom foods`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-advanced-limit-${System.nanoTime()}@example.com")
        seedAdvancedSubscription(userId)

        createCustomFoodViaApi(userId, "Advanced Custom Food One")
        createCustomFoodViaApi(userId, "Advanced Custom Food Two")

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Advanced Custom Food Three"))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Advanced Custom Food Three"))
    }

    @Test
    fun `create custom food with portions persists them and update replaces them`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-portions-${System.nanoTime()}@example.com")

        val createResponse = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Bulk Protein Chocolate",
                      "servingQuantity": 100,
                      "servingUnit": "GRAM",
                      "calories": 350,
                      "protein": 30,
                      "carbs": 40,
                      "fat": 9,
                      "portions": [
                        { "name": "slice", "gramWeight": 10 },
                        { "name": "big slice", "gramWeight": 25.5 }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.portions.length()").value(2))
            .andExpect(jsonPath("$.portions[0].unitName").value("slice"))
            .andExpect(jsonPath("$.portions[0].gramWeight").value(10.0))
            .andExpect(jsonPath("$.portions[0].displayText").value("1 slice"))
            .andExpect(jsonPath("$.portions[1].gramWeight").value(25.5))
            .andReturn()
            .response
            .contentAsString
        val foodPublicId = objectMapper.readTree(createResponse)["id"].toString().removeSurrounding("\"")

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                """
                select count(*) from food_serving_portions fsp
                join foods f on f.id = fsp.food_id
                where f.public_id = ?
                """.trimIndent(),
                Int::class.java,
                foodPublicId,
            ),
        )

        mockMvc.perform(
            patch("/api/v1/foods/custom/{foodId}", foodPublicId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Bulk Protein Chocolate",
                      "servingQuantity": 100,
                      "servingUnit": "GRAM",
                      "calories": 350,
                      "protein": 30,
                      "carbs": 40,
                      "fat": 9,
                      "portions": [
                        { "name": "cube", "gramWeight": 5 }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.portions.length()").value(1))
            .andExpect(jsonPath("$.portions[0].unitName").value("cube"))
            .andExpect(jsonPath("$.portions[0].gramWeight").value(5.0))

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                select count(*) from food_serving_portions fsp
                join foods f on f.id = fsp.food_id
                where f.public_id = ?
                """.trimIndent(),
                Int::class.java,
                foodPublicId,
            ),
        )
        assertEquals(
            "cube",
            jdbcTemplate.queryForObject(
                """
                select fsp.raw_unit_name from food_serving_portions fsp
                join foods f on f.id = fsp.food_id
                where f.public_id = ?
                """.trimIndent(),
                String::class.java,
                foodPublicId,
            ),
        )
    }

    @Test
    fun `custom food portions are rejected for non gram base units and duplicate names`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "custom-food-portions-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Piece Food",
                      "servingQuantity": 1,
                      "servingUnit": "PIECE",
                      "calories": 100,
                      "protein": 5,
                      "carbs": 10,
                      "fat": 2,
                      "portions": [ { "name": "slice", "gramWeight": 10 } ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Gram Food",
                      "servingQuantity": 100,
                      "servingUnit": "GRAM",
                      "calories": 100,
                      "protein": 5,
                      "carbs": 10,
                      "fat": 2,
                      "portions": [
                        { "name": "slice", "gramWeight": 10 },
                        { "name": " slice ", "gramWeight": 20 }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
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

    private fun seedAdvancedSubscription(userId: UUID) {
        jdbcTemplate.update(
            """
            insert into user_subscriptions (
                user_id,
                plan_id,
                status,
                period_start,
                period_end,
                created_at,
                updated_at
            )
            values (?, (select id from subscription_plans where code = 'ADVANCED'), 'ACTIVE', now(), now() + interval '30 days', now(), now())
            on conflict (user_id) do update set
                plan_id = excluded.plan_id,
                status = excluded.status,
                period_start = excluded.period_start,
                period_end = excluded.period_end,
                updated_at = now()
            """.trimIndent(),
            userId,
        )
    }

    private fun createCustomFoodViaApi(userId: UUID, name: String): String {
        val response = mockMvc.perform(
            post("/api/v1/foods/custom")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload(name))
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return objectMapper.readTree(response)["id"].toString().removeSurrounding("\"")
    }

    private fun seedSystemFood(): String {
        val publicId = "custom_food_system_${System.nanoTime()}"
        jdbcTemplate.update(
            """
            insert into foods (
                public_id,
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
            values (?, 'SYSTEM', 'USDA_FDC', ?, 'System Food', 'system food', 'FOUNDATION', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            publicId,
            "custom-food-system-${System.nanoTime()}",
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
            select id, 100, (select id from serving_units where code = 'GRAM'), 100, 10, 10, 3, 1, 1, 1, now(), now()
            from foods
            where public_id = ?
            """.trimIndent(),
            publicId,
        )

        return publicId
    }

    private fun validUpdatePayload(): String {
        return """
        {
          "name": "Updated Bowl",
          "servingQuantity": 1,
          "servingUnit": "SERVING",
          "calories": 500,
          "protein": 40,
          "carbs": 45,
          "fat": 15
        }
        """.trimIndent()
    }

    private fun validCreatePayload(foodName: String): String {
        return """
        {
          "name": "$foodName",
          "servingQuantity": 1,
          "servingUnit": "SERVING",
          "calories": 500,
          "protein": 40,
          "carbs": 45,
          "fat": 15
        }
        """.trimIndent()
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
