package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
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

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ]
)
class FoodDetailControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `food detail returns localized system food with nutrition and portions`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-detail-system-${System.nanoTime()}@example.com")

        val publicId = "food_detail_system_${System.nanoTime()}"
        val foodId = seedFood(
            publicId = publicId,
            sourceFoodId = "detail-system-${System.nanoTime()}",
            name = "Chicken Breast",
            normalizedName = "chicken breast",
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedFoodLocalization(foodId, "fa", "سینه مرغ", "سینه مرغ")
        seedServingPortion(foodId)
        seedFavorite(userId, foodId)
        seedRecent(userId, foodId)

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(publicId))
            .andExpect(jsonPath("$.type").value("SYSTEM"))
            .andExpect(jsonPath("$.name").value("Chicken Breast"))
            .andExpect(jsonPath("$.displayName").value("سینه مرغ"))
            .andExpect(jsonPath("$.locale").value("fa"))
            .andExpect(jsonPath("$.servingQuantity").value(100.0000))
            .andExpect(jsonPath("$.servingUnit.code").value("GRAM"))
            .andExpect(jsonPath("$.servingUnit.label").value("گرم"))
            .andExpect(jsonPath("$.calories").value(165.00))
            .andExpect(jsonPath("$.protein").value(31.00))
            .andExpect(jsonPath("$.carbs").value(0.00))
            .andExpect(jsonPath("$.fat").value(3.60))
            .andExpect(jsonPath("$.fiber").value(0.00))
            .andExpect(jsonPath("$.sugar").value(0.00))
            .andExpect(jsonPath("$.sodium").value(74.00))
            .andExpect(jsonPath("$.favorite").value(true))
            .andExpect(jsonPath("$.recent").value(true))
            .andExpect(jsonPath("$.source").value("USDA_FDC"))
            .andExpect(jsonPath("$.dataQuality").value("FOUNDATION"))
            .andExpect(jsonPath("$.portions.length()").value(1))
            .andExpect(jsonPath("$.portions[0].amount").value(1.0000))
            .andExpect(jsonPath("$.portions[0].unitName").value("فنجان"))
            .andExpect(jsonPath("$.portions[0].unitAbbreviation").value("CUP"))
            .andExpect(jsonPath("$.portions[0].gramWeight").value(140.0000))
            .andExpect(jsonPath("$.portions[0].displayText").value("1 فنجان diced (140 گرم)"))

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", foodId)
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(publicId))
    }

    @Test
    fun `food detail keeps english portion description for BCP 47 english locales`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-detail-en-us-${System.nanoTime()}@example.com")

        val publicId = "food_detail_english_locale_${System.nanoTime()}"
        val foodId = seedFood(
            publicId = publicId,
            sourceFoodId = "detail-english-locale-${System.nanoTime()}",
            name = "Chicken Breast",
            normalizedName = "chicken breast",
            dataQuality = "FOUNDATION",
            calories = BigDecimal("165.00"),
            ownerUserId = null,
        )
        seedServingPortion(foodId)

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .queryParam("locale", "en-US")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locale").value("en"))
            .andExpect(jsonPath("$.portions[0].displayText").value("1 cup, diced"))
    }

    @Test
    fun `food detail falls back to canonical English name when requested localization is not reviewed`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-detail-fallback-${System.nanoTime()}@example.com")

        val publicId = "food_detail_fallback_${System.nanoTime()}"
        val foodId = seedFood(
            publicId = publicId,
            sourceFoodId = "detail-fallback-${System.nanoTime()}",
            name = "Plain Yogurt",
            normalizedName = "plain yogurt",
            dataQuality = "SR_LEGACY",
            calories = BigDecimal("61.00"),
            ownerUserId = null,
        )
        seedFoodLocalization(foodId, "fa", "ماست ساده پیشنهادی", "ماست ساده پیشنهادی", reviewStatus = "UNREVIEWED")

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .queryParam("locale", "fa")
                .with(authentication(testAuthentication(userId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(publicId))
            .andExpect(jsonPath("$.displayName").value("Plain Yogurt"))
            .andExpect(jsonPath("$.locale").value("en"))
    }

    @Test
    fun `food detail returns owner custom food and hides it from other users`() {
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seedUser(ownerUserId, "food-detail-owner-${System.nanoTime()}@example.com")
        seedUser(otherUserId, "food-detail-other-${System.nanoTime()}@example.com")

        val publicId = "food_detail_custom_${System.nanoTime()}"
        seedFood(
            publicId = publicId,
            sourceFoodId = null,
            name = "Private Protein Bowl",
            normalizedName = "private protein bowl",
            dataQuality = "USER_SUBMITTED",
            calories = BigDecimal("540.00"),
            ownerUserId = ownerUserId,
        )

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .with(authentication(testAuthentication(ownerUserId)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(publicId))
            .andExpect(jsonPath("$.type").value("CUSTOM"))
            .andExpect(jsonPath("$.displayName").value("Private Protein Bowl"))

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .with(authentication(testAuthentication(otherUserId)))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Food was not found."))
    }

    @Test
    fun `food detail returns not found for hidden system food`() {
        val userId = UUID.randomUUID()
        seedUser(userId, "food-detail-hidden-${System.nanoTime()}@example.com")

        val publicId = "food_detail_hidden_${System.nanoTime()}"
        seedFood(
            publicId = publicId,
            sourceFoodId = "detail-hidden-${System.nanoTime()}",
            name = "Hidden Food",
            normalizedName = "hidden food",
            dataQuality = "UNREVIEWED",
            calories = BigDecimal("10.00"),
            ownerUserId = null,
            curationStatus = "HIDDEN",
        )

        mockMvc.perform(
            get("/api/v1/foods/{foodId}", publicId)
                .with(authentication(testAuthentication(userId)))
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
        dataQuality: String,
        calories: BigDecimal,
        ownerUserId: UUID?,
        curationStatus: String = "REVIEWED",
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
                name,
                normalized_name,
                data_quality,
                curation_status,
                is_searchable,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, now(), now())
            """.trimIndent(),
            foodId,
            publicId,
            ownerUserId,
            type,
            source,
            sourceFoodId,
            name,
            normalizedName,
            dataQuality,
            curationStatus,
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

    private fun seedFoodLocalization(
        foodId: UUID,
        locale: String,
        displayName: String,
        normalizedDisplayName: String,
        reviewStatus: String = "REVIEWED",
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
            values (?, ?, ?, ?, 'GYRO_CURATED', ?, now(), now())
            """.trimIndent(),
            foodId,
            locale,
            displayName,
            normalizedDisplayName,
            reviewStatus,
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
            "portion-${System.nanoTime()}",
        )
    }

    private fun seedFavorite(userId: UUID, foodId: UUID) {
        jdbcTemplate.update(
            """
            insert into food_favorites (user_id, food_id, created_at)
            values (?, ?, now())
            """.trimIndent(),
            userId,
            foodId,
        )
    }

    private fun seedRecent(userId: UUID, foodId: UUID) {
        jdbcTemplate.update(
            """
            insert into recent_foods (user_id, food_id, last_used_at, use_count, created_at, updated_at)
            values (?, ?, now(), 2, now(), now())
            """.trimIndent(),
            userId,
            foodId,
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
