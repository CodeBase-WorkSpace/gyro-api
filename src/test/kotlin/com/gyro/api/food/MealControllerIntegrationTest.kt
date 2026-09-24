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
class MealControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `create meal accepts public food ids returned by search`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-public-food-id-${System.nanoTime()}@example.com")
        val publicFoodId = "meal_public_food_${System.nanoTime()}"
        val internalFoodId = seedSystemFood(
            publicId = publicFoodId,
            name = "Search Result Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Search Result Meal",
                      "items": [
                        {
                          "foodId": "$publicFoodId",
                          "quantity": 100,
                          "servingUnit": "GRAM"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Search Result Meal"))
            .andExpect(jsonPath("$.items[0].foodId").value(internalFoodId.toString()))
            .andExpect(jsonPath("$.items[0].foodName").value("Search Result Food"))
            .andExpect(jsonPath("$.calories").value(200.00))
    }

    @Test
    fun `archive meal hides the template and preserves diary entries`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "meal-archive-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "meal-archive-other-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_archive_food_${System.nanoTime()}",
            name = "Archive Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val mealId = createMealViaApi(ownerUserId, "Archive Meal", foodId)
        val diaryDayId = UUID.randomUUID()
        val diaryEntryId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, current_date, 'UTC', now(), now())
            """.trimIndent(),
            diaryDayId,
            ownerUserId,
        )
        jdbcTemplate.update(
            """
            insert into diary_entries (
                id, diary_day_id, user_id, diary_date, meal_type, source_type, source_meal_id,
                display_name_snapshot, serving_quantity_snapshot, serving_unit_code_snapshot,
                serving_unit_name_snapshot, calories_snapshot, protein_snapshot, carbs_snapshot,
                fat_snapshot, fiber_snapshot, sugar_snapshot, sodium_snapshot, created_at, updated_at
            )
            values (?, ?, ?, current_date, 'DINNER', 'MEAL', ?, 'Archive Meal', 1, 'SERVING',
                    'Serving', 100, 2, 22, 0.5, 0, 0, 0, now(), now())
            """.trimIndent(),
            diaryEntryId,
            diaryDayId,
            ownerUserId,
            mealId,
        )

        mockMvc.perform(
            post("/api/v1/meals/{mealId}/archive", mealId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            post("/api/v1/meals/{mealId}/archive", mealId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealId").value(mealId.toString()))
            .andExpect(jsonPath("$.archived").value(true))

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from meals where id = ? and archived_at is not null",
                Int::class.java,
                mealId,
            ),
        )
        assertEquals(
            mealId,
            jdbcTemplate.queryForObject(
                "select source_meal_id from diary_entries where id = ?",
                UUID::class.java,
                diaryEntryId,
            ),
        )

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/meals")
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))

        mockMvc.perform(
            post("/api/v1/meals/{mealId}/archive", mealId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `update meal replaces items recalculates totals and preserves diary snapshots`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-update-${System.nanoTime()}@example.com")
        val originalFoodId = seedSystemFood(
            publicId = "meal_update_original_${System.nanoTime()}",
            name = "Original Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val replacementFoodId = seedSystemFood(
            publicId = "meal_update_replacement_${System.nanoTime()}",
            name = "Replacement Food",
            baseQuantity = 1,
            servingUnit = "SERVING",
            calories = 300,
            protein = 35,
            carbs = 5,
            fat = 12,
        )
        val mealId = createMealViaApi(userId, "Original Meal", originalFoodId)
        val diaryDayId = UUID.randomUUID()
        val diaryEntryId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, current_date, 'UTC', now(), now())
            """.trimIndent(),
            diaryDayId,
            userId,
        )
        jdbcTemplate.update(
            """
            insert into diary_entries (
                id, diary_day_id, user_id, diary_date, meal_type, source_type, source_meal_id,
                display_name_snapshot, serving_quantity_snapshot, serving_unit_code_snapshot,
                serving_unit_name_snapshot, calories_snapshot, protein_snapshot, carbs_snapshot,
                fat_snapshot, fiber_snapshot, sugar_snapshot, sodium_snapshot, created_at, updated_at
            )
            values (?, ?, ?, current_date, 'LUNCH', 'MEAL', ?, 'Original Meal', 1, 'SERVING',
                    'Serving', 100, 2, 22, 0.5, 0, 0, 0, now(), now())
            """.trimIndent(),
            diaryEntryId,
            diaryDayId,
            userId,
            mealId,
        )

        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "  Updated Meal  ",
                      "items": [
                        { "foodId": "$replacementFoodId", "quantity": 2, "servingUnit": "serving" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(mealId.toString()))
            .andExpect(jsonPath("$.name").value("Updated Meal"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].foodId").value(replacementFoodId.toString()))
            .andExpect(jsonPath("$.items[0].quantity").value(2.0000))
            .andExpect(jsonPath("$.calories").value(600.00))
            .andExpect(jsonPath("$.protein").value(70.00))
            .andExpect(jsonPath("$.carbs").value(10.00))
            .andExpect(jsonPath("$.fat").value(24.00))

        assertEquals(
            "Updated Meal",
            jdbcTemplate.queryForObject("select name from meals where id = ?", String::class.java, mealId),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject("select count(*) from meal_items where meal_id = ?", Int::class.java, mealId),
        )
        assertEquals(
            replacementFoodId,
            jdbcTemplate.queryForObject("select food_id from meal_items where meal_id = ?", UUID::class.java, mealId),
        )
        val snapshot = jdbcTemplate.queryForMap(
            "select display_name_snapshot, calories_snapshot from diary_entries where id = ?",
            diaryEntryId,
        )
        assertEquals("Original Meal", snapshot["display_name_snapshot"])
        assertEquals(java.math.BigDecimal("100.00"), snapshot["calories_snapshot"])
    }

    @Test
    fun `update meal resolves UUID-shaped public food ids from the edit form`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-update-public-uuid-${System.nanoTime()}@example.com")
        val publicFoodId = UUID.randomUUID().toString()
        val internalFoodId = seedSystemFood(
            publicId = publicFoodId,
            name = "UUID Public Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 180,
            protein = 6,
            carbs = 36,
            fat = 2,
        )
        val mealId = createMealViaApi(userId, "Editable Meal", internalFoodId)

        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Edited Meal",
                      "items": [
                        { "foodId": "$publicFoodId", "quantity": 150, "servingUnit": "GRAM" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Edited Meal"))
            .andExpect(jsonPath("$.items[0].foodId").value(internalFoodId.toString()))
            .andExpect(jsonPath("$.items[0].quantity").value(150.0000))
    }

    @Test
    fun `update meal requires ownership and validates replacement items`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "meal-update-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "meal-update-other-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_update_auth_${System.nanoTime()}",
            name = "Update Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val mealId = createMealViaApi(ownerUserId, "Private Meal", foodId)

        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(foodId))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "name": "No items", "items": [] }""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'items')]").isNotEmpty)
    }

    @Test
    fun `get meal returns owner scoped detail with ordered items and totals`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-get-${System.nanoTime()}@example.com")
        val riceId = seedSystemFood(
            publicId = "meal_get_rice_${System.nanoTime()}",
            name = "Detail Rice",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val chickenId = seedCustomFood(
            ownerUserId = userId,
            publicId = "meal_get_chicken_${System.nanoTime()}",
            name = "Detail Chicken",
            baseQuantity = 1,
            servingUnit = "SERVING",
            calories = 300,
            protein = 35,
            carbs = 5,
            fat = 12,
        )

        val createdResponse = mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Detail Plate",
                      "items": [
                        {
                          "foodId": "$riceId",
                          "quantity": 50,
                          "servingUnit": "GRAM"
                        },
                        {
                          "foodId": "$chickenId",
                          "quantity": 2,
                          "servingUnit": "SERVING"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        val mealId = UUID.fromString(objectMapper.readTree(createdResponse)["id"].toString().removeSurrounding("\""))

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(mealId.toString()))
            .andExpect(jsonPath("$.name").value("Detail Plate"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").isString)
            .andExpect(jsonPath("$.items[0].foodId").value(riceId.toString()))
            .andExpect(jsonPath("$.items[0].foodName").value("Detail Rice"))
            .andExpect(jsonPath("$.items[0].quantity").value(50.0000))
            .andExpect(jsonPath("$.items[0].servingUnit.code").value("GRAM"))
            .andExpect(jsonPath("$.items[0].calories").value(100.00))
            .andExpect(jsonPath("$.items[1].foodId").value(chickenId.toString()))
            .andExpect(jsonPath("$.items[1].servingUnit.code").value("SERVING"))
            .andExpect(jsonPath("$.items[1].calories").value(600.00))
            .andExpect(jsonPath("$.calories").value(700.00))
            .andExpect(jsonPath("$.protein").value(72.00))
            .andExpect(jsonPath("$.carbs").value(32.00))
            .andExpect(jsonPath("$.fat").value(24.50))
    }

    @Test
    fun `get meal hides meals the user does not own archived meals and invalid ids`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "meal-get-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "meal-get-other-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_get_private_food_${System.nanoTime()}",
            name = "Private Meal Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val mealId = createMealViaApi(
            userId = ownerUserId,
            name = "Private Meal",
            foodId = foodId,
        )
        val archivedMealId = createMealViaApi(
            userId = ownerUserId,
            name = "Archived Meal",
            foodId = foodId,
        )
        jdbcTemplate.update(
            "update meals set archived_at = now(), updated_at = now() where id = ?",
            archivedMealId,
        )

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", archivedMealId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", "not-a-uuid")
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `list meals returns owner scoped active meals with calculated totals`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "meal-list-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "meal-list-other-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_list_food_${System.nanoTime()}",
            name = "List Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val activeMealId = createMealViaApi(
            userId = ownerUserId,
            name = "Protein Rice Bowl",
            foodId = foodId,
        )
        val archivedMealId = createMealViaApi(
            userId = ownerUserId,
            name = "Archived Rice Bowl",
            foodId = foodId,
        )
        createMealViaApi(
            userId = otherUserId,
            name = "Other Rice Bowl",
            foodId = foodId,
        )
        jdbcTemplate.update(
            "update meals set archived_at = now(), updated_at = now() where id = ?",
            archivedMealId,
        )

        mockMvc.perform(
            get("/api/v1/meals")
                .queryParam("query", "protein")
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(activeMealId.toString()))
            .andExpect(jsonPath("$.items[0].name").value("Protein Rice Bowl"))
            .andExpect(jsonPath("$.items[0].itemCount").value(1))
            .andExpect(jsonPath("$.items[0].calories").value(100.00))
            .andExpect(jsonPath("$.items[0].protein").value(2.00))
            .andExpect(jsonPath("$.items[0].carbs").value(22.00))
            .andExpect(jsonPath("$.items[0].fat").value(0.50))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
    }

    @Test
    fun `list meals supports pagination`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-list-pagination-${System.nanoTime()}@example.com")
        seedAdvancedSubscription(userId)
        val foodId = seedSystemFood(
            publicId = "meal_list_page_food_${System.nanoTime()}",
            name = "Pagination Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        createMealViaApi(userId = userId, name = "Page Meal One", foodId = foodId)
        createMealViaApi(userId = userId, name = "Page Meal Two", foodId = foodId)
        createMealViaApi(userId = userId, name = "Page Meal Three", foodId = foodId)

        mockMvc.perform(
            get("/api/v1/meals")
                .queryParam("page", "0")
                .queryParam("size", "2")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
    }

    @Test
    fun `free plan blocks third active custom meal`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-free-limit-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_free_limit_food_${System.nanoTime()}",
            name = "Free Limit Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        createMealViaApi(userId, "Free Meal One", foodId)
        createMealViaApi(userId, "Free Meal Two", foodId)

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(foodId))
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"))
            .andExpect(jsonPath("$.metadata.supportReasonCode").value("PLAN_LIMIT_REACHED"))
            .andExpect(jsonPath("$.metadata.featureKey").value("higher_limits"))
            .andExpect(jsonPath("$.metadata.limitName").value("custom_meals"))
            .andExpect(jsonPath("$.metadata.limitValue").value("2"))
    }

    @Test
    fun `advanced plan allows more than two custom meals`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-advanced-limit-${System.nanoTime()}@example.com")
        seedAdvancedSubscription(userId)
        val foodId = seedSystemFood(
            publicId = "meal_advanced_limit_food_${System.nanoTime()}",
            name = "Advanced Limit Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        createMealViaApi(userId, "Advanced Meal One", foodId)
        createMealViaApi(userId, "Advanced Meal Two", foodId)

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(foodId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Test Meal"))
    }

    @Test
    fun `create meal persists owner scoped items and returns calculated totals`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-create-${System.nanoTime()}@example.com")
        val riceId = seedSystemFood(
            publicId = "meal_rice_${System.nanoTime()}",
            name = "Rice",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val chickenId = seedCustomFood(
            ownerUserId = userId,
            publicId = "meal_chicken_${System.nanoTime()}",
            name = "Chicken Bowl",
            baseQuantity = 1,
            servingUnit = "SERVING",
            calories = 300,
            protein = 35,
            carbs = 5,
            fat = 12,
        )

        val response = mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "  Lunch Plate  ",
                      "items": [
                        {
                          "foodId": "$riceId",
                          "quantity": 50,
                          "servingUnit": "gram"
                        },
                        {
                          "foodId": "$chickenId",
                          "quantity": 2,
                          "servingUnit": "serving"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isString)
            .andExpect(jsonPath("$.name").value("Lunch Plate"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].foodId").value(riceId.toString()))
            .andExpect(jsonPath("$.items[0].foodName").value("Rice"))
            .andExpect(jsonPath("$.items[0].quantity").value(50.0000))
            .andExpect(jsonPath("$.items[0].servingUnit.code").value("GRAM"))
            .andExpect(jsonPath("$.items[0].calories").value(100.00))
            .andExpect(jsonPath("$.items[1].foodId").value(chickenId.toString()))
            .andExpect(jsonPath("$.items[1].calories").value(600.00))
            .andExpect(jsonPath("$.calories").value(700.00))
            .andExpect(jsonPath("$.protein").value(72.00))
            .andExpect(jsonPath("$.carbs").value(32.00))
            .andExpect(jsonPath("$.fat").value(24.50))
            .andReturn()
            .response
            .contentAsString

        val mealId = UUID.fromString(objectMapper.readTree(response)["id"].toString().removeSurrounding("\""))
        val meal = jdbcTemplate.queryForMap(
            """
            select owner_user_id, name, normalized_name, archived_at
            from meals
            where id = ?
            """.trimIndent(),
            mealId,
        )
        assertEquals(userId, meal["owner_user_id"])
        assertEquals("Lunch Plate", meal["name"])
        assertEquals("lunch plate", meal["normalized_name"])
        assertEquals(null, meal["archived_at"])

        val itemCount = jdbcTemplate.queryForObject(
            "select count(*) from meal_items where meal_id = ?",
            Int::class.java,
            mealId,
        ) ?: 0
        assertEquals(2, itemCount)
    }

    @Test
    fun `create meal localizes system food names from the user profile locale`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-create-localized-${System.nanoTime()}@example.com")
        seedProfile(userId, locale = "fa-IR")
        val ricePublicId = "meal_localized_rice_${System.nanoTime()}"
        val riceId = seedSystemFood(
            publicId = ricePublicId,
            name = "Rice",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        seedFoodLocalization(riceId, "fa", "برنج")

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(riceId))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.items[0].foodName").value("برنج"))
    }

    @Test
    fun `update meal localizes replacement food names from the user profile locale`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-update-localized-${System.nanoTime()}@example.com")
        seedProfile(userId, locale = "fa-IR")
        val originalFoodId = seedSystemFood(
            publicId = "meal_update_original_localized_${System.nanoTime()}",
            name = "Original Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val localizedFoodPublicId = "meal_update_localized_${System.nanoTime()}"
        val localizedFoodId = seedSystemFood(
            publicId = localizedFoodPublicId,
            name = "Rice",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        seedFoodLocalization(localizedFoodId, "fa", "برنج")
        val mealId = createMealViaApi(userId, "Localized Meal", originalFoodId)

        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Localized Meal",
                      "items": [
                        { "foodId": "$localizedFoodPublicId", "quantity": 50, "servingUnit": "GRAM" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].foodId").value(localizedFoodId.toString()))
            .andExpect(jsonPath("$.items[0].foodName").value("برنج"))
    }

    @Test
    fun `create meal rounds decimal item nutrition and total calculations`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-rounding-${System.nanoTime()}@example.com")
        val firstFoodId = seedSystemFood(
            publicId = "meal_rounding_first_${System.nanoTime()}",
            name = "Rounding Rice",
            baseQuantity = 3,
            servingUnit = "GRAM",
            calories = 100,
            protein = 10,
            carbs = 7,
            fat = 4,
        )
        val secondFoodId = seedSystemFood(
            publicId = "meal_rounding_second_${System.nanoTime()}",
            name = "Rounding Lentils",
            baseQuantity = 8,
            servingUnit = "GRAM",
            calories = 50,
            protein = 5,
            carbs = 8,
            fat = 2,
        )

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Rounded Plate",
                      "items": [
                        {
                          "foodId": "$firstFoodId",
                          "quantity": 1,
                          "servingUnit": "GRAM"
                        },
                        {
                          "foodId": "$secondFoodId",
                          "quantity": 3,
                          "servingUnit": "GRAM"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].foodName").value("Rounding Rice"))
            .andExpect(jsonPath("$.items[0].calories").value(33.33))
            .andExpect(jsonPath("$.items[0].protein").value(3.33))
            .andExpect(jsonPath("$.items[0].carbs").value(2.33))
            .andExpect(jsonPath("$.items[0].fat").value(1.33))
            .andExpect(jsonPath("$.items[1].foodName").value("Rounding Lentils"))
            .andExpect(jsonPath("$.items[1].calories").value(18.75))
            .andExpect(jsonPath("$.items[1].protein").value(1.88))
            .andExpect(jsonPath("$.items[1].carbs").value(3.00))
            .andExpect(jsonPath("$.items[1].fat").value(0.75))
            .andExpect(jsonPath("$.calories").value(52.08))
            .andExpect(jsonPath("$.protein").value(5.21))
            .andExpect(jsonPath("$.carbs").value(5.33))
            .andExpect(jsonPath("$.fat").value(2.08))
    }

    @Test
    fun `create meal hides custom foods owned by another user`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "meal-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "meal-other-${System.nanoTime()}@example.com")
        val privateFoodId = seedCustomFood(
            ownerUserId = ownerUserId,
            publicId = "meal_private_${System.nanoTime()}",
            name = "Private Food",
            baseQuantity = 1,
            servingUnit = "SERVING",
            calories = 300,
            protein = 35,
            carbs = 5,
            fat = 12,
        )

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(privateFoodId))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Food was not found."))
    }

    @Test
    fun `create meal rejects incompatible serving unit`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-incompatible-unit-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_mass_food_${System.nanoTime()}",
            name = "Mass Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateMealPayload(foodId, servingUnit = "SERVING"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("servingUnit is incompatible with food base unit."))
    }

    @Test
    fun `create meal with same idempotency key returns cached response`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-idempotent-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_idempotent_food_${System.nanoTime()}",
            name = "Idempotent Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val idempotencyKey = "meal-${System.nanoTime()}"
        val requestBody = validCreateMealPayload(foodId)

        val firstResponse = mockMvc.perform(
            post("/api/v1/meals")
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
            post("/api/v1/meals")
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
        assertEquals(firstJson["calories"].toString(), replayedJson["calories"].toString())

        val createdRows = jdbcTemplate.queryForObject(
            """
            select count(*)
            from meals
            where owner_user_id = ? and name = 'Test Meal'
            """.trimIndent(),
            Int::class.java,
            userId,
        ) ?: 0
        assertEquals(1, createdRows)
    }

    @Test
    fun `create meal rejects invalid payload`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-invalid-payload-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "",
                      "items": []
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.length()").value(2))
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").isNotEmpty)
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'items')]").isNotEmpty)
    }

    @Test
    fun `create meal with serving definition returns per serving nutrition`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-serving-def-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_serving_def_food_${System.nanoTime()}",
            name = "Batch Base Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 20,
            carbs = 10,
            fat = 4,
        )

        val response = mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Chocolate Batch",
                      "totalBatchWeight": 200,
                      "servingWeight": 10,
                      "items": [
                        { "foodId": "$foodId", "quantity": 100, "servingUnit": "GRAM" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.calories").value(200.00))
            .andExpect(jsonPath("$.servingDefinition.totalBatchWeight").value(200.0))
            .andExpect(jsonPath("$.servingDefinition.servingWeight").value(10.0))
            .andExpect(jsonPath("$.servingDefinition.servingsPerBatch").value(20.0))
            .andExpect(jsonPath("$.servingDefinition.perServing.calories").value(10.00))
            .andExpect(jsonPath("$.servingDefinition.perServing.protein").value(1.00))
            .andReturn()
            .response
            .contentAsString
        val mealId = UUID.fromString(objectMapper.readTree(response)["id"].toString().removeSurrounding("\""))

        mockMvc.perform(
            get("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.servingDefinition.servingsPerBatch").value(20.0))

        mockMvc.perform(
            get("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].servingDefinition.perServing.calories").value(10.00))

        // Clearing the definition on update removes it.
        mockMvc.perform(
            patch("/api/v1/meals/{mealId}", mealId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Protein Chocolate Batch",
                      "items": [
                        { "foodId": "$foodId", "quantity": 100, "servingUnit": "GRAM" }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.servingDefinition").doesNotExist())
    }

    @Test
    fun `meal serving definition validation rejects partial or inverted weights`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "meal-serving-def-invalid-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "meal_serving_def_invalid_${System.nanoTime()}",
            name = "Validation Food",
            baseQuantity = 100,
            servingUnit = "GRAM",
            calories = 200,
            protein = 20,
            carbs = 10,
            fat = 4,
        )

        fun payload(fields: String) = """
            {
              "name": "Invalid Serving Meal",
              $fields
              "items": [
                { "foodId": "$foodId", "quantity": 100, "servingUnit": "GRAM" }
              ]
            }
        """.trimIndent()

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("\"totalBatchWeight\": 200,"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("\"totalBatchWeight\": 10, \"servingWeight\": 200,"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("\"totalBatchWeight\": 100000000, \"servingWeight\": 1,"))
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

    private fun seedProfile(
        userId: UUID,
        locale: String,
    ) {
        jdbcTemplate.update(
            """
            insert into user_profiles (
                user_id,
                timezone,
                locale,
                created_at,
                updated_at
            )
            values (?, 'Asia/Tehran', ?, now(), now())
            on conflict (user_id) do update
            set locale = excluded.locale,
                updated_at = now()
            """.trimIndent(),
            userId,
            locale,
        )
    }

    private fun seedFoodLocalization(
        foodId: UUID,
        locale: String,
        displayName: String,
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
            values (?, ?, ?, lower(?), 'GYRO_CURATED', 'REVIEWED', now(), now())
            """.trimIndent(),
            foodId,
            locale,
            displayName,
            displayName,
        )
    }

    private fun seedSystemFood(
        publicId: String,
        name: String,
        baseQuantity: Int,
        servingUnit: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ): UUID {
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
            values (?, 'SYSTEM', 'USDA_FDC', ?, ?, lower(?), 'FOUNDATION', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            publicId,
            "$publicId-source",
            name,
            name,
        )
        return seedNutrition(publicId, baseQuantity, servingUnit, calories, protein, carbs, fat)
    }

    private fun seedCustomFood(
        ownerUserId: UUID,
        publicId: String,
        name: String,
        baseQuantity: Int,
        servingUnit: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ): UUID {
        jdbcTemplate.update(
            """
            insert into foods (
                public_id,
                owner_user_id,
                type,
                source,
                name,
                normalized_name,
                data_quality,
                curation_status,
                is_searchable,
                created_at,
                updated_at
            )
            values (?, ?, 'CUSTOM', 'USER_CURATED', ?, lower(?), 'USER_SUBMITTED', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            publicId,
            ownerUserId,
            name,
            name,
        )
        return seedNutrition(publicId, baseQuantity, servingUnit, calories, protein, carbs, fat)
    }

    private fun seedNutrition(
        publicId: String,
        baseQuantity: Int,
        servingUnit: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ): UUID {
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
            select id, ?, (select id from serving_units where code = ?), ?, ?, ?, ?, 0, 0, 0, now(), now()
            from foods
            where public_id = ?
            """.trimIndent(),
            baseQuantity,
            servingUnit,
            calories,
            protein,
            carbs,
            fat,
            publicId,
        )

        return jdbcTemplate.queryForObject(
            "select id from foods where public_id = ?",
            UUID::class.java,
            publicId,
        ) ?: error("Seed food was not inserted.")
    }

    private fun validCreateMealPayload(
        foodId: UUID,
        servingUnit: String = "GRAM",
    ): String {
        return """
        {
          "name": "Test Meal",
          "items": [
            {
              "foodId": "$foodId",
              "quantity": 50,
              "servingUnit": "$servingUnit"
            }
          ]
        }
        """.trimIndent()
    }

    private fun createMealViaApi(
        userId: UUID,
        name: String,
        foodId: UUID,
    ): UUID {
        val response = mockMvc.perform(
            post("/api/v1/meals")
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "$name",
                      "items": [
                        {
                          "foodId": "$foodId",
                          "quantity": 50,
                          "servingUnit": "GRAM"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString

        return UUID.fromString(objectMapper.readTree(response)["id"].toString().removeSurrounding("\""))
    }

    private fun testAuthentication(userId: UUID): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
    }
}
