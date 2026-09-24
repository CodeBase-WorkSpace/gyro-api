package com.gyro.api.food

import com.gyro.api.TestcontainersConfiguration
import com.gyro.api.food.application.CustomFoodService
import com.gyro.api.food.application.MealService
import com.gyro.api.food.web.dto.CreateCustomFoodRequest
import com.gyro.api.food.web.dto.CreateMealItemRequest
import com.gyro.api.food.web.dto.CreateMealRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency tests proving that per-user advisory locking prevents two parallel
 * create operations from both passing the Free-plan count check and exceeding the limit.
 *
 * Without the lock, a Free user at count=1 with limit=2 could create 3+ custom foods/meals
 * because both transactions observe count=1 before either inserts. With the advisory lock,
 * the second create is serialized behind the first and observes the updated count.
 */
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest(
    properties = [
        "app.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.rate-limit.enabled=false",
    ],
)
class PlanLimitConcurrencyIntegrationTest(
    @Autowired private val customFoodService: CustomFoodService,
    @Autowired private val mealService: MealService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `concurrent custom food creates cannot exceed free plan limit`() {
        val userId = UUID.randomUUID()
        seedUser(userId)

        // Pre-seed count = limit - 1 so the next successful create reaches the cap.
        repeat(FREE_CUSTOM_FOODS - 1) {
            createCustomFoodViaService(userId, "Pre-seeded Food $it")
        }

        val startGate = CountDownLatch(1)
        val threadCount = 4
        val doneLatch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val limitReachedCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            pool.submit {
                try {
                    startGate.await()
                    try {
                        createCustomFoodViaService(userId, "Concurrent Food $i")
                        successCount.incrementAndGet()
                    } catch (e: com.gyro.api.common.error.PlanLimitReachedException) {
                        limitReachedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Concurrent creates must complete within timeout")
        pool.shutdown()

        // With limit=2 and one pre-seeded food, exactly one concurrent create can succeed.
        assertEquals(1, successCount.get(), "Only one concurrent create should succeed (the cap)")
        assertEquals(threadCount - 1, limitReachedCount.get(), "Remaining concurrent creates must be rejected")

        val activeCount = jdbcTemplate.queryForObject(
            """
            select count(*) from foods
            where owner_user_id = ?
              and type = 'CUSTOM'
              and archived_at is null
            """.trimIndent(),
            Long::class.java,
            userId,
        )
        assertEquals(FREE_CUSTOM_FOODS.toLong(), activeCount, "Active custom foods must equal the free limit")
    }

    @Test
    fun `concurrent custom meal creates cannot exceed free plan limit`() {
        val userId = UUID.randomUUID()
        seedUser(userId)
        val foodId = seedSystemFood()

        repeat(FREE_CUSTOM_MEALS - 1) {
            createMealViaService(userId, "Pre-seeded Meal $it", foodId)
        }

        val startGate = CountDownLatch(1)
        val threadCount = 4
        val doneLatch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val limitReachedCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            pool.submit {
                try {
                    startGate.await()
                    try {
                        createMealViaService(userId, "Concurrent Meal $i", foodId)
                        successCount.incrementAndGet()
                    } catch (e: com.gyro.api.common.error.PlanLimitReachedException) {
                        limitReachedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Concurrent creates must complete within timeout")
        pool.shutdown()

        assertEquals(1, successCount.get(), "Only one concurrent create should succeed (the cap)")
        assertEquals(threadCount - 1, limitReachedCount.get(), "Remaining concurrent creates must be rejected")

        val activeCount = jdbcTemplate.queryForObject(
            """
            select count(*) from meals
            where owner_user_id = ?
              and archived_at is null
            """.trimIndent(),
            Long::class.java,
            userId,
        )
        assertEquals(FREE_CUSTOM_MEALS.toLong(), activeCount, "Active custom meals must equal the free limit")
    }

    private fun createCustomFoodViaService(userId: UUID, name: String) {
        customFoodService.create(
            ownerUserId = userId,
            request = CreateCustomFoodRequest(
                name = name,
                servingQuantity = BigDecimal.ONE,
                servingUnit = "SERVING",
                calories = BigDecimal(100),
                protein = BigDecimal(10),
                carbs = BigDecimal(20),
                fat = BigDecimal(5),
            ),
        )
    }

    private fun createMealViaService(userId: UUID, name: String, foodId: UUID) {
        mealService.create(
            ownerUserId = userId,
            request = CreateMealRequest(
                name = name,
                items = listOf(
                    CreateMealItemRequest(
                        foodId = foodId.toString(),
                        quantity = BigDecimal(50),
                        servingUnit = "GRAM",
                    ),
                ),
            ),
        )
    }

    private fun seedUser(id: UUID) {
        jdbcTemplate.update(
            """
            insert into users (
                id, email, password_hash, role,
                email_verification_status, phone_verification_status,
                status, created_at, updated_at
            )
            values (?, ?, '{noop}Password123', 'USER', 'VERIFIED', 'UNVERIFIED', 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            "plan-limit-concurrency-${System.nanoTime()}-${id}@example.com",
        )
    }

    private fun seedSystemFood(): UUID {
        val publicId = "plan_limit_food_${System.nanoTime()}"
        jdbcTemplate.update(
            """
            insert into foods (
                public_id, type, source, source_food_id, name, normalized_name,
                data_quality, curation_status, is_searchable, created_at, updated_at
            )
            values (?, 'SYSTEM', 'USDA_FDC', ?, 'Concurrency Food', 'concurrency food',
                'FOUNDATION', 'REVIEWED', true, now(), now())
            """.trimIndent(),
            publicId,
            "$publicId-source",
        )
        jdbcTemplate.update(
            """
            insert into food_nutrition_facts (
                food_id, base_quantity, base_unit_id, calories, protein, carbs, fat,
                fiber, sugar, sodium, created_at, updated_at
            )
            select id, 100, (select id from serving_units where code = 'GRAM'),
                100, 10, 10, 3, 0, 0, 0, now(), now()
            from foods where public_id = ?
            """.trimIndent(),
            publicId,
        )
        return jdbcTemplate.queryForObject(
            "select id from foods where public_id = ?",
            UUID::class.java,
            publicId,
        ) ?: error("Seed food was not inserted.")
    }

    private companion object {
        private const val FREE_CUSTOM_FOODS = 2
        private const val FREE_CUSTOM_MEALS = 2
    }
}
