package com.gyro.api.diary

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.subscription.application.CachedEntitlementService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
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
class DiaryControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val cachedEntitlementService: CachedEntitlementService,
) {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `batch create logs foods atomically updates recents and is idempotent`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-21"
        seedUser(userId, "diary-batch-${System.nanoTime()}@example.com")
        val chickenPublicId = "batch_chicken_${System.nanoTime()}"
        val ricePublicId = "batch_rice_${System.nanoTime()}"
        val chickenId = seedSystemFood(chickenPublicId, "Batch Chicken", 100, 165, 31, 0, 4)
        val riceId = seedSystemFood(ricePublicId, "Batch Rice", 100, 130, 3, 28, 0)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")
        val body = """
            {
              "mealType": "LUNCH",
              "entries": [
                {"sourceType":"FOOD","sourceId":"$chickenPublicId","quantity":150,"servingUnitId":"$gramUnitId"},
                {"sourceType":"FOOD","sourceId":"$ricePublicId","quantity":200,"servingUnitId":"$gramUnitId"}
              ]
            }
        """.trimIndent()

        repeat(2) {
            mockMvc.perform(
                post("/api/v1/diary/{date}/entries/batch", date)
                    .with(authentication(testAuthentication(userId)))
                    .header("Idempotency-Key", "batch-${userId}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.mealGroups[1].entries.length()").value(2))
                .andExpect(jsonPath("$.totals.calories").value(507.50))
        }

        assertEquals(2, jdbcTemplate.queryForObject(
            "select count(*) from diary_entries where user_id = ? and diary_date = ?",
            Int::class.java,
            userId,
            java.time.LocalDate.parse(date),
        ))
        assertEquals(2, jdbcTemplate.queryForObject(
            "select count(*) from recent_foods where user_id = ? and food_id in (?, ?)",
            Int::class.java,
            userId,
            chickenId,
            riceId,
        ))
        assertEquals(2, jdbcTemplate.queryForObject(
            "select sum(use_count) from recent_foods where user_id = ? and food_id in (?, ?)",
            Int::class.java,
            userId,
            chickenId,
            riceId,
        ))
    }

    @Test
    fun `batch create rolls back all entries when one food is invalid`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-22"
        seedUser(userId, "diary-batch-invalid-${System.nanoTime()}@example.com")
        val validPublicId = "batch_valid_${System.nanoTime()}"
        seedSystemFood(validPublicId, "Batch Valid", 100, 100, 10, 10, 2)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/batch", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "batch-invalid-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"mealType":"DINNER","entries":[
                      {"sourceType":"FOOD","sourceId":"$validPublicId","quantity":100,"servingUnitId":"$gramUnitId"},
                      {"sourceType":"FOOD","sourceId":"missing-food","quantity":100,"servingUnitId":"$gramUnitId"}
                    ]}
                """.trimIndent())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Food was not found."))

        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from diary_entries where user_id = ? and diary_date = ?",
            Int::class.java,
            userId,
            java.time.LocalDate.parse(date),
        ))
    }

    @Test
    fun `batch create rejects a serving unit code instead of a UUID`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-22"
        seedUser(userId, "diary-batch-unit-${System.nanoTime()}@example.com")
        val foodPublicId = "batch_unit_${System.nanoTime()}"
        seedSystemFood(foodPublicId, "Batch Unit", 100, 100, 10, 10, 2)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/batch", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "batch-unit-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mealType":"DINNER","entries":[
                      {"sourceType":"FOOD","sourceId":"$foodPublicId","quantity":100,"servingUnitId":"GRAM"}
                    ]}
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("servingUnitId is invalid."))

        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from diary_entries where user_id = ? and diary_date = ?",
            Int::class.java,
            userId,
            java.time.LocalDate.parse(date),
        ))
    }

    @Test
    fun `batch quick plate saves a reusable meal and logs it atomically`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-22"
        seedUser(userId, "diary-plate-${System.nanoTime()}@example.com")
        val foodPublicId = "plate_food_${System.nanoTime()}"
        val foodId = seedSystemFood(foodPublicId, "Plate Food", 100, 100, 10, 10, 2)
        seedServingPortion(foodId, "CUP", 140)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")
        val body = """
            {"mealType":"LUNCH","entries":[
              {"sourceType":"FOOD","sourceId":"$foodPublicId","quantity":140,"servingUnitId":"$gramUnitId"}
            ],"quickPlate":{"name":"Daily Plate"}}
        """.trimIndent()

        repeat(2) {
            mockMvc.perform(
                post("/api/v1/diary/{date}/entries/batch", date)
                    .with(authentication(testAuthentication(userId)))
                    .header("Idempotency-Key", "batch-plate-${userId}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.mealGroups[1].entries.length()").value(1))
                .andExpect(jsonPath("$.mealGroups[1].entries[0].sourceType").value("MEAL"))
                .andExpect(jsonPath("$.mealGroups[1].entries[0].displayName").value("Daily Plate"))
                .andExpect(jsonPath("$.mealGroups[1].entries[0].nutrition.calories").value(140.00))
        }

        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from meals where owner_user_id = ? and name = ?",
            Int::class.java,
            userId,
            "Daily Plate",
        ))
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from diary_entries where user_id = ? and diary_date = ?",
            Int::class.java,
            userId,
            java.time.LocalDate.parse(date),
        ))
        val savedMealQuantity = jdbcTemplate.queryForObject(
            "select quantity from meal_items where meal_id = (select id from meals where owner_user_id = ? and name = ?)",
            java.math.BigDecimal::class.java,
            userId,
            "Daily Plate",
        ) ?: error("Quick Plate item is missing")
        assertEquals(0, 140.toBigDecimal().compareTo(savedMealQuantity))
    }

    @Test
    fun `quick plate respects the free custom meal limit`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-23"
        seedUser(userId, "diary-plate-limit-${System.nanoTime()}@example.com")
        val publicId = "plate_limit_food_${System.nanoTime()}"
        val foodId = seedSystemFood(publicId, "Plate Limit Food", 100, 100, 10, 10, 2)
        seedMeal(userId, "Existing Plate One", foodId, 100)
        seedMeal(userId, "Existing Plate Two", foodId, 100)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/batch", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "batch-plate-limit-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mealType":"LUNCH","entries":[
                      {"sourceType":"FOOD","sourceId":"$publicId","quantity":100,"servingUnitId":"$gramUnitId"}
                    ],"quickPlate":{"name":"Blocked Plate"}}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"))
            .andExpect(jsonPath("$.metadata.limitName").value("custom_meals"))
    }

    @Test
    fun `advanced users can create quick plates beyond the free custom meal limit`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-23"
        seedUser(userId, "diary-plate-advanced-${System.nanoTime()}@example.com")
        seedAdvancedSubscription(userId)
        val publicId = "plate_advanced_food_${System.nanoTime()}"
        val foodId = seedSystemFood(publicId, "Advanced Plate Food", 100, 100, 10, 10, 2)
        seedMeal(userId, "Existing Advanced Plate One", foodId, 100)
        seedMeal(userId, "Existing Advanced Plate Two", foodId, 100)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/batch", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "batch-plate-advanced-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mealType":"LUNCH","entries":[
                      {"sourceType":"FOOD","sourceId":"$publicId","quantity":100,"servingUnitId":"$gramUnitId"}
                    ],"quickPlate":{"name":"Advanced Plate"}}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[1].entries[0].displayName").value("Advanced Plate"))
    }

    @Test
    fun `batch create snapshots gram weighted cup and piece portions`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-22"
        seedUser(userId, "diary-portions-${System.nanoTime()}@example.com")
        val cupFoodPublicId = "portion_cup_${System.nanoTime()}"
        val pieceFoodPublicId = "portion_piece_${System.nanoTime()}"
        val cupFoodId = seedSystemFood(cupFoodPublicId, "Cup Food", 100, 100, 10, 10, 2)
        val pieceFoodId = seedSystemFood(pieceFoodPublicId, "Piece Food", 100, 100, 10, 10, 2)
        seedServingPortion(cupFoodId, "CUP", 140)
        seedServingPortion(pieceFoodId, "PIECE", 55)
        val gramUnitId = jdbcTemplate.queryForObject(
            "select id from serving_units where code = 'GRAM'",
            UUID::class.java,
        ) ?: error("GRAM unit is missing")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/batch", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", "batch-portions-${userId}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mealType":"LUNCH","entries":[
                      {"sourceType":"FOOD","sourceId":"$cupFoodPublicId","quantity":140,"servingUnitId":"$gramUnitId"},
                      {"sourceType":"FOOD","sourceId":"$pieceFoodPublicId","quantity":55,"servingUnitId":"$gramUnitId"}
                    ]}
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[1].entries.length()").value(2))
            .andExpect(jsonPath("$.totals.calories").value(195.00))
    }

    @Test
    fun `create diary entries snapshots food meal and manual nutrition`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-03"
        seedUser(userId, "diary-create-${System.nanoTime()}@example.com")
        val foodPublicId = "diary_food_${System.nanoTime()}"
        val foodId = seedSystemFood(
            publicId = foodPublicId,
            name = "Diary Rice",
            baseQuantity = 100,
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        seedFoodLocalization(foodId, "fa", "برنج دفتر غذایی")
        val mealId = seedMeal(userId, "Diary Rice Bowl", foodId, quantity = 100)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodPublicId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[0].entries[0].sourceType").value("FOOD"))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].displayName").value("برنج دفتر غذایی"))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].nutrition.calories").value(100.00))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "LUNCH",
                      "sourceType": "MEAL",
                      "sourceMealId": "$mealId",
                      "servingQuantity": 2
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[1].entries[0].sourceType").value("MEAL"))
            .andExpect(jsonPath("$.mealGroups[1].entries[0].sourceMealId").value(mealId.toString()))
            .andExpect(jsonPath("$.mealGroups[1].entries[0].nutrition.calories").value(400.00))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(555.00))
            .andExpect(jsonPath("$.totals.protein").value(20.00))
            .andExpect(jsonPath("$.totals.carbs").value(113.00))
            .andExpect(jsonPath("$.totals.fat").value(3.00))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].sourceType").value("MANUAL"))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].displayName").value("Protein shake"))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].nutrition.calories").value(55.00))

        assertEquals(
            3,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(date),
            ),
        )
        assertEquals(
            java.math.BigDecimal("400.00"),
            jdbcTemplate.queryForObject(
                "select calories_snapshot from diary_entries where source_meal_id = ?",
                java.math.BigDecimal::class.java,
                mealId,
            ),
        )

        jdbcTemplate.update(
            "update food_nutrition_facts set calories = 999, updated_at = now() where food_id = ?",
            foodId,
        )
        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(555.00))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].nutrition.calories").value(100.00))
    }

    @Test
    fun `update and delete diary entries return refreshed owner day`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-04"
        seedUser(userId, "diary-update-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "diary_update_food_${System.nanoTime()}",
            name = "Update Food",
            baseQuantity = 100,
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val createResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val entryId = objectMapper.readTree(createResponse)["mealGroups"][4]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        val updateKey = "diary-update-${userId}"
        mockMvc.perform(
            patch("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "DINNER"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(100.00))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].sourceType").value("FOOD"))
            .andExpect(jsonPath("$.mealGroups[4].entries.length()").value(0))

        mockMvc.perform(
            patch("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "DINNER"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(100.00))

        mockMvc.perform(
            patch("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "LUNCH"))
        )
            .andExpect(status().isConflict)

        val deleteKey = "diary-delete-${userId}"
        mockMvc.perform(
            delete("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", deleteKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(0.00))
            .andExpect(jsonPath("$.mealGroups[2].entries.length()").value(0))

        mockMvc.perform(
            delete("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", deleteKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(0.00))
    }

    @Test
    fun `diary mutations reject foreign entry access and invalid source shapes`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val date = "2026-06-05"
        seedUser(ownerUserId, "diary-mutation-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "diary-mutation-other-${System.nanoTime()}@example.com")
        val createResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val entryId = objectMapper.readTree(createResponse)["mealGroups"][4]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        mockMvc.perform(
            patch("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            delete("/api/v1/diary/{date}/entries/{entryId}", date, entryId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "SNACK",
                      "sourceType": "FOOD",
                      "servingQuantity": 1
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("sourceFoodId is required."))
    }

    @Test
    fun `copy from date copies stored snapshots into target day`() {
        val userId = UUID.randomUUID()
        val sourceDate = "2026-06-06"
        val targetDate = "2026-06-07"
        seedUser(userId, "diary-copy-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "diary_copy_food_${System.nanoTime()}",
            name = "Copy Rice",
            baseQuantity = 100,
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "LUNCH"))
        )
            .andExpect(status().isOk)

        jdbcTemplate.update(
            "update food_nutrition_facts set calories = 999, updated_at = now() where food_id = ?",
            foodId,
        )

        mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", targetDate, sourceDate)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(targetDate))
            .andExpect(jsonPath("$.totals.calories").value(100.00))
            .andExpect(jsonPath("$.mealGroups[1].entries[0].displayName").value("Copy Rice"))
            .andExpect(jsonPath("$.mealGroups[1].entries[0].nutrition.calories").value(100.00))

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(targetDate),
            ),
        )
    }

    @Test
    fun `copy from date rejects same date and inaccessible source day`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val sourceDate = "2026-06-08"
        val targetDate = "2026-06-09"
        seedUser(ownerUserId, "diary-copy-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "diary-copy-other-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", sourceDate, sourceDate)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("targetDate and sourceDate must be different."))

        mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", targetDate, sourceDate)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Source diary day was not found."))
    }

    @Test
    fun `diary create with same idempotency key returns cached response and avoids duplicate rows`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-10"
        seedUser(userId, "diary-create-idempotent-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "diary_create_idempotent_food_${System.nanoTime()}",
            name = "Idempotent Rice",
            baseQuantity = 100,
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )
        val idempotencyKey = "diary-create-${System.nanoTime()}"
        val requestBody = foodEntryPayload(foodId, mealType = "LUNCH")

        val firstResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val replayedResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val firstJson = objectMapper.readTree(firstResponse)
        val replayedJson = objectMapper.readTree(replayedResponse)
        assertEquals(firstJson.toString(), replayedJson.toString())
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(date),
            ),
        )
    }

    @Test
    fun `diary create copy and repeat reject reused idempotency keys with different requests`() {
        val userId = UUID.randomUUID()
        val sourceDate = "2026-06-11"
        val targetDate = "2026-06-12"
        val repeatDate = "2026-06-13"
        seedUser(userId, "diary-idempotent-conflict-${System.nanoTime()}@example.com")
        val foodId = seedSystemFood(
            publicId = "diary_idempotent_conflict_food_${System.nanoTime()}",
            name = "Conflict Rice",
            baseQuantity = 100,
            calories = 200,
            protein = 4,
            carbs = 44,
            fat = 1,
        )

        val createKey = "diary-create-conflict-${System.nanoTime()}"
        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "BREAKFAST"))
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(foodId, mealType = "DINNER"))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))

        val copyKey = "diary-copy-conflict-${System.nanoTime()}"
        mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", targetDate, sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", copyKey)
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", repeatDate, sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", copyKey)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))

        val sourceResponse = mockMvc.perform(
            get("/api/v1/diary/{date}", sourceDate)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val entryId = objectMapper.readTree(sourceResponse)["mealGroups"][0]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        val repeatKey = "diary-repeat-conflict-${System.nanoTime()}"
        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/{entryId}/repeat", repeatDate, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", repeatKey)
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/{entryId}/repeat", targetDate, entryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", repeatKey)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
    }

    @Test
    fun `copy and repeat preserve stored snapshots after custom food and meal changes and idempotent retries do not duplicate entries`() {
        val userId = UUID.randomUUID()
        val sourceDate = "2026-06-14"
        val copyDate = "2026-06-15"
        val repeatDate = "2026-06-16"
        seedUser(userId, "diary-snapshot-${System.nanoTime()}@example.com")
        val customFoodId = seedCustomFood(
            ownerUserId = userId,
            name = "Snapshot Oats",
            servingQuantity = 100,
            servingUnitCode = "GRAM",
            calories = 300,
            protein = 10,
            carbs = 40,
            fat = 5,
        )
        val mealId = seedMeal(userId, "Snapshot Bowl", customFoodId, quantity = 100)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(customFoodId, mealType = "BREAKFAST"))
        )
            .andExpect(status().isOk)

        val mealResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", sourceDate)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "DINNER",
                      "sourceType": "MEAL",
                      "sourceMealId": "$mealId",
                      "servingQuantity": 1
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val mealEntryId = objectMapper.readTree(mealResponse)["mealGroups"][2]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        jdbcTemplate.update(
            "update foods set name = ?, normalized_name = lower(?) where id = ?",
            "Edited Snapshot Oats",
            "Edited Snapshot Oats",
            customFoodId,
        )
        jdbcTemplate.update(
            "update food_nutrition_facts set calories = 900, protein = 50, carbs = 1, fat = 1, updated_at = now() where food_id = ?",
            customFoodId,
        )
        jdbcTemplate.update(
            "update meals set name = ?, normalized_name = lower(?), updated_at = now() where id = ?",
            "Edited Snapshot Bowl",
            "Edited Snapshot Bowl",
            mealId,
        )

        val copyKey = "diary-copy-snapshot-${System.nanoTime()}"
        val firstCopyResponse = mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", copyDate, sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", copyKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(450.00))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].displayName").value("Snapshot Oats"))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].nutrition.calories").value(150.00))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].displayName").value("Snapshot Bowl"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(300.00))
            .andReturn()
            .response
            .contentAsString

        val replayedCopyResponse = mockMvc.perform(
            post("/api/v1/diary/{targetDate}/copy-from/{sourceDate}", copyDate, sourceDate)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", copyKey)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertEquals(firstCopyResponse, replayedCopyResponse)
        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(copyDate),
            ),
        )

        val repeatKey = "diary-repeat-snapshot-${System.nanoTime()}"
        val firstRepeatResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries/{entryId}/repeat", repeatDate, mealEntryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", repeatKey)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(300.00))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].displayName").value("Snapshot Bowl"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(300.00))
            .andReturn()
            .response
            .contentAsString

        val replayedRepeatResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries/{entryId}/repeat", repeatDate, mealEntryId)
                .with(authentication(testAuthentication(userId)))
                .header("Idempotency-Key", repeatKey)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertEquals(firstRepeatResponse, replayedRepeatResponse)
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(repeatDate),
            ),
        )

        mockMvc.perform(
            get("/api/v1/diary/{date}", sourceDate)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(450.00))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].displayName").value("Snapshot Oats"))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].nutrition.calories").value(150.00))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].displayName").value("Snapshot Bowl"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(300.00))
    }

    @Test
    fun `diary create and repeat hide private custom food meal and foreign entries`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val date = "2026-06-17"
        val repeatDate = "2026-06-18"
        seedUser(ownerUserId, "diary-private-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "diary-private-other-${System.nanoTime()}@example.com")
        val privateFoodId = seedCustomFood(
            ownerUserId = ownerUserId,
            name = "Private Diary Food",
            servingQuantity = 1,
            servingUnitCode = "SERVING",
            calories = 540,
            protein = 42,
            carbs = 48,
            fat = 18,
        )
        val privateMealId = seedMeal(ownerUserId, "Private Diary Meal", privateFoodId, quantity = 1)

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(foodEntryPayload(privateFoodId, mealType = "LUNCH"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Food was not found."))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "DINNER",
                      "sourceType": "MEAL",
                      "sourceMealId": "$privateMealId",
                      "servingQuantity": 1
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Meal was not found."))

        val ownerEntryResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val entryId = objectMapper.readTree(ownerEntryResponse)["mealGroups"][4]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries/{entryId}/repeat", repeatDate, entryId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `diary meal entry multiplies decimal custom meal nutrition and keeps stored snapshot after template edits`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-19"
        seedUser(userId, "diary-meal-rounding-${System.nanoTime()}@example.com")
        val foodId = seedCustomFood(
            ownerUserId = userId,
            name = "Rounding Meal Food",
            servingQuantity = 3,
            servingUnitCode = "GRAM",
            calories = 100,
            protein = 10,
            carbs = 7,
            fat = 4,
        )
        val mealId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into meals (id, owner_user_id, name, normalized_name, created_at, updated_at)
            values (?, ?, ?, lower(?), now(), now())
            """.trimIndent(),
            mealId,
            userId,
            "Rounded Meal",
            "Rounded Meal",
        )
        jdbcTemplate.update(
            """
            insert into meal_items (meal_id, food_id, quantity, serving_unit_id, sort_order, created_at, updated_at)
            values (?, ?, 2.0000, (select id from serving_units where code = 'GRAM'), 0, now(), now())
            """.trimIndent(),
            mealId,
            foodId,
        )

        val createResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "DINNER",
                      "sourceType": "MEAL",
                      "sourceMealId": "$mealId",
                      "servingQuantity": 1.25
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(83.33))
            .andExpect(jsonPath("$.totals.protein").value(8.33))
            .andExpect(jsonPath("$.totals.carbs").value(5.83))
            .andExpect(jsonPath("$.totals.fat").value(3.33))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].displayName").value("Rounded Meal"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(83.33))
            .andReturn()
            .response
            .contentAsString
        val entryId = objectMapper.readTree(createResponse)["mealGroups"][2]["entries"][0]["id"]
            .toString()
            .removeSurrounding("\"")

        jdbcTemplate.update(
            "update meals set name = ?, normalized_name = lower(?), updated_at = now() where id = ?",
            "Edited Rounded Meal",
            "Edited Rounded Meal",
            mealId,
        )
        jdbcTemplate.update(
            "update food_nutrition_facts set calories = 900, protein = 90, carbs = 1, fat = 1, updated_at = now() where food_id = ?",
            foodId,
        )

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[2].entries[0].id").value(entryId))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].displayName").value("Rounded Meal"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(83.33))
            .andExpect(jsonPath("$.totals.calories").value(83.33))
    }

    @Test
    fun `diary read and write stay isolated across users for same date`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val date = "2026-06-20"
        seedUser(ownerUserId, "diary-isolation-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "diary-isolation-other-${System.nanoTime()}@example.com")

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(ownerUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(55.00))

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(0.00))
            .andExpect(jsonPath("$.mealGroups[4].entries.length()").value(0))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(otherUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "CUSTOM",
                      "sourceType": "MANUAL",
                      "servingQuantity": 1,
                      "servingUnit": "serving",
                      "displayName": "Other shake",
                      "manualNutrition": {
                        "calories": 120,
                        "protein": 20,
                        "carbs": 8,
                        "fat": 2,
                        "fiber": 1,
                        "sugar": 4,
                        "sodium": 30
                      }
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(120.00))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].displayName").value("Other shake"))

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(55.00))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].displayName").value("Protein shake"))

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_days where user_id = ? and diary_date = ?",
                Int::class.java,
                ownerUserId,
                java.time.LocalDate.parse(date),
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_days where user_id = ? and diary_date = ?",
                Int::class.java,
                otherUserId,
                java.time.LocalDate.parse(date),
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                ownerUserId,
                java.time.LocalDate.parse(date),
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_entries where user_id = ? and diary_date = ?",
                Int::class.java,
                otherUserId,
                java.time.LocalDate.parse(date),
            ),
        )
    }

    @Test
    fun `get diary creates owner day and returns grouped snapshot totals`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-01"
        seedUser(userId, "diary-${System.nanoTime()}@example.com")
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, ?, 'Asia/Tehran', now(), now())
            """.trimIndent(),
            dayId,
            userId,
            java.time.LocalDate.parse(date),
        )
        seedManualEntry(
            id = UUID.randomUUID(),
            dayId = dayId,
            userId = userId,
            date = date,
            mealType = "BREAKFAST",
            displayName = "Breakfast oats",
            sortOrder = 1,
            calories = 350,
            protein = 15,
            carbs = 50,
            fat = 10,
        )
        seedManualEntry(
            id = UUID.randomUUID(),
            dayId = dayId,
            userId = userId,
            date = date,
            mealType = "DINNER",
            displayName = "Dinner chicken",
            sortOrder = 0,
            calories = 420,
            protein = 45,
            carbs = 12,
            fat = 18,
        )

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(date))
            .andExpect(jsonPath("$.timezone").value("Asia/Tehran"))
            .andExpect(jsonPath("$.goal.configured").value(false))
            .andExpect(jsonPath("$.goal.calories").doesNotExist())
            .andExpect(jsonPath("$.remainingCalories.configured").value(false))
            .andExpect(jsonPath("$.remainingCalories.value").doesNotExist())
            .andExpect(jsonPath("$.macroProgress.configured").value(false))
            .andExpect(jsonPath("$.macroProgress.protein.consumed").value(60.00))
            .andExpect(jsonPath("$.macroProgress.protein.target").doesNotExist())
            .andExpect(jsonPath("$.totals.calories").value(770.00))
            .andExpect(jsonPath("$.totals.protein").value(60.00))
            .andExpect(jsonPath("$.totals.carbs").value(62.00))
            .andExpect(jsonPath("$.totals.fat").value(28.00))
            .andExpect(jsonPath("$.mealGroups.length()").value(5))
            .andExpect(jsonPath("$.mealGroups[0].mealType").value("BREAKFAST"))
            .andExpect(jsonPath("$.mealGroups[0].entries[0].displayName").value("Breakfast oats"))
            .andExpect(jsonPath("$.mealGroups[0].totals.calories").value(350.00))
            .andExpect(jsonPath("$.mealGroups[2].mealType").value("DINNER"))
            .andExpect(jsonPath("$.mealGroups[2].entries[0].nutrition.calories").value(420.00))
            .andExpect(jsonPath("$.warnings[0].code").value("GOALS_NOT_CONFIGURED"))
    }

    @Test
    fun `get diary includes active goals in remaining calories and macro progress`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-02"
        seedUser(userId, "diary-goals-${System.nanoTime()}@example.com")
        seedNutritionPlan(
            userId = userId,
            startDate = LocalDate.parse("2026-06-01"),
            calories = BigDecimal("2100.00"),
            protein = BigDecimal("130.000"),
            carbs = BigDecimal("240.000"),
            fat = BigDecimal("70.000"),
            fiber = BigDecimal("30.000"),
        )
        val dayId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into diary_days (id, user_id, diary_date, timezone, created_at, updated_at)
            values (?, ?, ?, 'Asia/Tehran', now(), now())
            """.trimIndent(),
            dayId,
            userId,
            LocalDate.parse(date),
        )
        seedManualEntry(
            id = UUID.randomUUID(),
            dayId = dayId,
            userId = userId,
            date = date,
            mealType = "LUNCH",
            displayName = "Goal lunch",
            sortOrder = 0,
            calories = 650,
            protein = 52,
            carbs = 80,
            fat = 20,
        )

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.goal.configured").value(true))
            .andExpect(jsonPath("$.goal.calories").value(2100.00))
            .andExpect(jsonPath("$.goal.protein").value(130.00))
            .andExpect(jsonPath("$.remainingCalories.configured").value(true))
            .andExpect(jsonPath("$.remainingCalories.value").value(1450.00))
            .andExpect(jsonPath("$.macroProgress.configured").value(true))
            .andExpect(jsonPath("$.macroProgress.protein.consumed").value(52.00))
            .andExpect(jsonPath("$.macroProgress.protein.target").value(130.00))
            .andExpect(jsonPath("$.macroProgress.protein.remaining").value(78.00))
            .andExpect(jsonPath("$.macroProgress.protein.goalPercent").value(40.00))
            .andExpect(jsonPath("$.macroProgress.carbs.target").value(240.00))
            .andExpect(jsonPath("$.macroProgress.fat.target").value(70.00))
            .andExpect(jsonPath("$.warnings.length()").value(0))
    }

    @Test
    fun `get diary creates an empty day only for the authenticated user`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val date = "2026-06-02"
        seedUser(userId, "diary-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "diary-other-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(0.00))
            .andExpect(jsonPath("$.mealGroups[*].entries").isNotEmpty)

        mockMvc.perform(
            get("/api/v1/diary/{date}", date)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_days where user_id = ? and diary_date = ?",
                Int::class.java,
                userId,
                java.time.LocalDate.parse(date),
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from diary_days where user_id = ? and diary_date = ?",
                Int::class.java,
                otherUserId,
                java.time.LocalDate.parse(date),
            ),
        )
    }

    @Test
    fun `get diary rejects non ISO date`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "diary-invalid-${System.nanoTime()}@example.com")

        mockMvc.perform(
            get("/api/v1/diary/{date}", "06-01-2026")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("date must be an ISO-8601 calendar date."))
    }

    @Test
    fun `free users can read a future day while diary writes stay outside the window`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "diary-future-window-${System.nanoTime()}@example.com")
        val futureDate = LocalDate.now().plusDays(4)

        mockMvc.perform(
            get("/api/v1/diary/{date}", futureDate)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(futureDate.toString()))
            .andExpect(jsonPath("$.canWriteDiary").value(false))

        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", futureDate)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "future-window-${userId}")
                .content(manualEntryPayload())
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FUTURE_DATE_LIMIT"))
            .andExpect(jsonPath("$.metadata.maximumDate").exists())
    }

    @Test
    fun `lapsed users can read and delete preserved entries beyond the free future window`() {
        val userId = UUID.randomUUID()
        val futureDate = LocalDate.now().plusDays(4)
        seedUser(userId, "diary-future-preserved-${System.nanoTime()}@example.com")
        seedAdvancedSubscription(userId)
        cachedEntitlementService.invalidate(userId)

        val createResponse = mockMvc.perform(
            post("/api/v1/diary/{date}/entries", futureDate)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val entryId = objectMapper.readTree(createResponse)["mealGroups"][4]["entries"][0]["id"]
            .toString().removeSurrounding("\"")

        jdbcTemplate.update(
            "update user_subscriptions set status = 'EXPIRED', period_end = now() - interval '1 day' where user_id = ?",
            userId,
        )
        cachedEntitlementService.invalidate(userId)

        mockMvc.perform(
            get("/api/v1/diary/{date}", futureDate)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canWriteDiary").value(false))
            .andExpect(jsonPath("$.mealGroups[4].entries[0].id").value(entryId))

        mockMvc.perform(
            patch("/api/v1/diary/{date}/entries/{entryId}", futureDate, entryId)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualEntryPayload())
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FUTURE_DATE_LIMIT"))

        mockMvc.perform(
            delete("/api/v1/diary/{date}/entries/{entryId}", futureDate, entryId)
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mealGroups[4].entries.length()").value(0))
    }

    @Test
    fun `diary meal entry with serving definition logs servings instead of whole recipes`() {
        val userId = UUID.randomUUID()
        val date = "2026-06-20"
        seedUser(userId, "diary-meal-serving-def-${System.nanoTime()}@example.com")
        val foodId = seedCustomFood(
            ownerUserId = userId,
            name = "Batch Chocolate Base",
            servingQuantity = 100,
            servingUnitCode = "GRAM",
            calories = 200,
            protein = 20,
            carbs = 10,
            fat = 4,
        )
        val mealId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into meals (id, owner_user_id, name, normalized_name, total_batch_weight, serving_weight, created_at, updated_at)
            values (?, ?, ?, lower(?), 200, 10, now(), now())
            """.trimIndent(),
            mealId,
            userId,
            "Protein Chocolate Batch",
            "Protein Chocolate Batch",
        )
        jdbcTemplate.update(
            """
            insert into meal_items (meal_id, food_id, quantity, serving_unit_id, sort_order, created_at, updated_at)
            values (?, ?, 100, (select id from serving_units where code = 'GRAM'), 0, now(), now())
            """.trimIndent(),
            mealId,
            foodId,
        )

        // Whole batch is 200 kcal for 200 g; one serving of 10 g is 10 kcal, so 2 servings = 20 kcal.
        mockMvc.perform(
            post("/api/v1/diary/{date}/entries", date)
                .with(authentication(testAuthentication(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mealType": "SNACK",
                      "sourceType": "MEAL",
                      "sourceMealId": "$mealId",
                      "servingQuantity": 2
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.calories").value(20.00))
            .andExpect(jsonPath("$.totals.protein").value(2.00))
            .andExpect(jsonPath("$.totals.carbs").value(1.00))
            .andExpect(jsonPath("$.totals.fat").value(0.40))
    }

    private fun foodEntryPayload(foodId: UUID, mealType: String = "BREAKFAST"): String {
        return foodEntryPayload(foodId.toString(), mealType)
    }

    private fun foodEntryPayload(foodId: String, mealType: String = "BREAKFAST"): String {
        return """
        {
          "mealType": "$mealType",
          "sourceType": "FOOD",
          "sourceFoodId": "$foodId",
          "servingQuantity": 50,
          "servingUnit": "gram"
        }
        """.trimIndent()
    }

    private fun manualEntryPayload(): String {
        return """
        {
          "mealType": "CUSTOM",
          "sourceType": "MANUAL",
          "servingQuantity": 1,
          "servingUnit": "serving",
          "displayName": "Protein shake",
          "manualNutrition": {
            "calories": 55,
            "protein": 10,
            "carbs": 3,
            "fat": 0.5,
            "fiber": 0,
            "sugar": 2,
            "sodium": 20
          }
        }
        """.trimIndent()
    }

    private fun seedSystemFood(
        publicId: String,
        name: String,
        baseQuantity: Int,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ): UUID {
        jdbcTemplate.update(
            """
            insert into foods (
                public_id, type, source, source_food_id, name, normalized_name, data_quality,
                curation_status, is_searchable, created_at, updated_at
            )
            values (?, 'SYSTEM', 'USDA_FDC', ?, ?, lower(?), 'FOUNDATION', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            publicId,
            "$publicId-source",
            name,
            name,
        )
        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (
                food_id, base_quantity, base_unit_id, calories, protein, carbs, fat, fiber, sugar, sodium,
                created_at, updated_at
            )
            select id, ?, (select id from serving_units where code = 'GRAM'), ?, ?, ?, ?, 0, 0, 0, now(), now()
            from foods
            where public_id = ?
            """.trimIndent(),
            baseQuantity,
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
        ) ?: error("Food was not inserted.")
    }

    private fun seedServingPortion(
        foodId: UUID,
        servingUnitCode: String,
        gramWeight: Int,
    ) {
        jdbcTemplate.update(
            """
            insert into food_serving_portions (
                food_id, serving_unit_id, amount, gram_weight, raw_unit_name,
                portion_description, source_portion_id, sort_order, created_at, updated_at
            )
            select ?, id, 1.0000, ?, lower(?), concat('1 ', lower(?)), ?, 0, now(), now()
            from serving_units where code = ?
            """.trimIndent(),
            foodId,
            gramWeight,
            servingUnitCode,
            servingUnitCode,
            "portion-${foodId}-${servingUnitCode}",
            servingUnitCode,
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
                food_id, locale, display_name, normalized_display_name, source, review_status, created_at, updated_at
            )
            values (?, ?, ?, ?, 'GYRO_CURATED', 'REVIEWED', now(), now())
            """.trimIndent(),
            foodId,
            locale,
            displayName,
            displayName,
        )
    }

    private fun seedMeal(
        ownerUserId: UUID,
        name: String,
        foodId: UUID,
        quantity: Int,
    ): UUID {
        val mealId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into meals (id, owner_user_id, name, normalized_name, created_at, updated_at)
            values (?, ?, ?, lower(?), now(), now())
            """.trimIndent(),
            mealId,
            ownerUserId,
            name,
            name,
        )
        jdbcTemplate.update(
            """
            insert into meal_items (meal_id, food_id, quantity, serving_unit_id, sort_order, created_at, updated_at)
            values (?, ?, ?, (select id from serving_units where code = 'GRAM'), 0, now(), now())
            """.trimIndent(),
            mealId,
            foodId,
            quantity,
        )
        return mealId
    }

    private fun seedManualEntry(
        id: UUID,
        dayId: UUID,
        userId: UUID,
        date: String,
        mealType: String,
        displayName: String,
        sortOrder: Int,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ) {
        jdbcTemplate.update(
            """
            insert into diary_entries (
                id, diary_day_id, user_id, diary_date, meal_type, source_type, display_name_snapshot,
                serving_quantity_snapshot, serving_unit_code_snapshot, serving_unit_name_snapshot,
                calories_snapshot, protein_snapshot, carbs_snapshot, fat_snapshot, fiber_snapshot,
                sugar_snapshot, sodium_snapshot, sort_order, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, 'MANUAL', ?, 1, 'SERVING', 'Serving', ?, ?, ?, ?, 0, 0, 0, ?, now(), now())
            """.trimIndent(),
            id,
            dayId,
            userId,
            java.time.LocalDate.parse(date),
            mealType,
            displayName,
            calories,
            protein,
            carbs,
            fat,
            sortOrder,
        )
    }

    private fun seedCustomFood(
        ownerUserId: UUID,
        name: String,
        servingQuantity: Int,
        servingUnitCode: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
    ): UUID {
        val foodId = UUID.randomUUID()
        val publicId = "food_${UUID.randomUUID().toString().replace("-", "")}"
        jdbcTemplate.update(
            """
            insert into foods (
                id, public_id, owner_user_id, type, source, name, normalized_name,
                data_quality, curation_status, is_searchable, created_at, updated_at
            )
            values (?, ?, ?, 'CUSTOM', 'USER_CURATED', ?, lower(?), 'USER_SUBMITTED', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            foodId,
            publicId,
            ownerUserId,
            name,
            name,
        )
        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (
                food_id, base_quantity, base_unit_id, calories, protein, carbs, fat, fiber, sugar, sodium,
                created_at, updated_at
            )
            values (?, ?, (select id from serving_units where code = ?), ?, ?, ?, ?, 0, 0, 0, now(), now())
            """.trimIndent(),
            foodId,
            servingQuantity,
            servingUnitCode,
            calories,
            protein,
            carbs,
            fat,
        )
        return foodId
    }

    private fun seedUser(id: UUID, email: String) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role, email_verification_status, phone_verification_status,
                status, created_at, updated_at
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
                user_id, plan_id, status, period_start, period_end, created_at, updated_at
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

    private fun seedNutritionPlan(
        userId: UUID,
        startDate: LocalDate,
        calories: BigDecimal,
        protein: BigDecimal,
        carbs: BigDecimal,
        fat: BigDecimal,
        fiber: BigDecimal?,
    ) {
        val planId = jdbcTemplate.queryForObject(
            """
            insert into nutrition_plans (
                id,
                user_id,
                start_date,
                timezone,
                calories,
                protein,
                carbs,
                fat,
                fiber,
                created_at,
                updated_at
            )
            values (?, ?, ?, 'Asia/Tehran', ?, ?, ?, ?, ?, now(), now())
            returning id
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(),
            userId,
            startDate,
            calories,
            protein,
            carbs,
            fat,
            fiber,
        ) ?: error("Expected inserted nutrition plan id")

        jdbcTemplate.update(
            """
            insert into plan_schedules (
                user_id,
                nutrition_plan_id,
                schedule_type,
                active_from,
                active_to,
                weekday_targets,
                date_overrides,
                macro_adjustment_mode,
                schedule_snapshot,
                created_at,
                updated_at
            )
            values (?, ?, 'FLAT', ?, null, '{}'::jsonb, '{}'::jsonb, 'FIXED_GRAMS', '{}'::jsonb, now(), now())
            """.trimIndent(),
            userId,
            planId,
            startDate,
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
