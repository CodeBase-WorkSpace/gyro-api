package com.gyro.api.goal.application.coach

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.ObjectMapper

enum class NutritionCoachCacheOutcome { HIT, MISS, ERROR }

data class NutritionCoachCacheLookup(
    val value: NutritionCoachStateResult?,
    val outcome: NutritionCoachCacheOutcome,
    val generation: Long,
)

data class NutritionCoachCacheEntry(
    val generation: Long,
    val state: NutritionCoachStateResult,
)

/**
 * Shared cache for the expensive, owner-scoped Coach read model.
 *
 * Redis keeps results coherent across API instances. Mutation-driven eviction
 * provides freshness; the TTL only limits the lifetime of a missed eviction.
 */
@Service
class NutritionCoachStateCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${app.nutrition-coach.cache-ttl:5m}")
    private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val invalidateScript = DefaultRedisScript(INVALIDATE_SCRIPT, Long::class.java)

    fun get(userId: UUID): NutritionCoachCacheLookup {
        return try {
            val values = redisTemplate.opsForValue().multiGet(
                listOf(generationKeyFor(userId), stateKeyFor(userId))
            )
            val generation = values?.getOrNull(0)?.toLongOrNull() ?: INITIAL_GENERATION
            val json = values?.getOrNull(1)
                ?: return NutritionCoachCacheLookup(
                    null,
                    NutritionCoachCacheOutcome.MISS,
                    generation,
                )
            val entry = objectMapper.readValue(json, NutritionCoachCacheEntry::class.java)
            if (entry.generation != generation) {
                return NutritionCoachCacheLookup(
                    null,
                    NutritionCoachCacheOutcome.MISS,
                    generation,
                )
            }
            NutritionCoachCacheLookup(
                entry.state,
                NutritionCoachCacheOutcome.HIT,
                generation,
            )
        } catch (exception: Exception) {
            log.warn("Nutrition Coach cache read failed for userId={}: {}", userId, exception.message)
            NutritionCoachCacheLookup(
                null,
                NutritionCoachCacheOutcome.ERROR,
                INITIAL_GENERATION,
            )
        }
    }

    fun put(userId: UUID, state: NutritionCoachStateResult, generation: Long) {
        try {
            val entry = NutritionCoachCacheEntry(generation, state)
            redisTemplate.opsForValue().set(
                stateKeyFor(userId),
                objectMapper.writeValueAsString(entry),
                ttl,
            )
        } catch (exception: Exception) {
            log.warn("Nutrition Coach cache write failed for userId={}: {}", userId, exception.message)
        }
    }

    fun invalidate(userId: UUID) {
        try {
            val generationKey = generationKeyFor(userId)
            redisTemplate.execute(
                invalidateScript,
                listOf(generationKey),
                ttl.multipliedBy(2).toMillis().toString(),
            )
            log.debug("Nutrition Coach cache invalidated for userId={}", userId)
        } catch (exception: Exception) {
            log.warn("Nutrition Coach cache invalidation failed for userId={}: {}", userId, exception.message)
        }
    }

    fun stateKeyFor(userId: UUID): String = "$KEY_PREFIX$userId"

    fun generationKeyFor(userId: UUID): String = "$GENERATION_KEY_PREFIX$userId"

    private companion object {
        const val INITIAL_GENERATION = 0L
        const val KEY_PREFIX = "nutrition-coach:state:v1:"
        const val GENERATION_KEY_PREFIX = "nutrition-coach:generation:v1:"
        const val INVALIDATE_SCRIPT =
            "local generation = redis.call('INCR', KEYS[1]); " +
                "redis.call('PEXPIRE', KEYS[1], ARGV[1]); return generation"
    }
}
