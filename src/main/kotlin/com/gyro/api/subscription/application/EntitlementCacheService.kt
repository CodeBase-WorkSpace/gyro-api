package com.gyro.api.subscription.application

import com.gyro.api.subscription.domain.Entitlement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.ObjectMapper

@Service
class EntitlementCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${app.entitlement.cache-ttl:60s}")
    private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "entitlement:"
    }

    fun get(userId: UUID): Entitlement? {
        return try {
            val key = keyFor(userId)
            val json = redisTemplate.opsForValue().get(key) ?: return null
            objectMapper.readValue(json, Entitlement::class.java)
        } catch (e: Exception) {
            log.warn("Entitlement cache read failed for userId={}: {}", userId, e.message)
            null
        }
    }

    fun put(userId: UUID, entitlement: Entitlement) {
        try {
            val key = keyFor(userId)
            val json = objectMapper.writeValueAsString(entitlement)
            redisTemplate.opsForValue().set(key, json, ttl)
        } catch (e: Exception) {
            log.warn("Entitlement cache write failed for userId={}: {}", userId, e.message)
        }
    }

    fun invalidate(userId: UUID) {
        try {
            val key = keyFor(userId)
            redisTemplate.delete(key)
            log.debug("Entitlement cache invalidated for userId={}", userId)
        } catch (e: Exception) {
            log.warn("Entitlement cache invalidation failed for userId={}: {}", userId, e.message)
        }
    }

    fun keyFor(userId: UUID): String = "$KEY_PREFIX$userId"
}
