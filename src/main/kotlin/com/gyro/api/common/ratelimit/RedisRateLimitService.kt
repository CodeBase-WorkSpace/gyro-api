package com.gyro.api.common.ratelimit

import com.gyro.api.common.error.RateLimitExceededException
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RedisRateLimitService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${app.rate-limit.enabled:true}")
    private val enabled: Boolean,
) : RateLimitService {
    private val incrementScript = DefaultRedisScript(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """.trimIndent(),
        Long::class.java,
    )

    override fun check(key: String, limit: Long, window: Duration) {
        if (!enabled) {
            return
        }

        val count = redisTemplate.execute(
            incrementScript,
            listOf(key),
            window.toMillis().toString(),
        ) ?: 1L

        if (count > limit) {
            throw RateLimitExceededException()
        }
    }
}
