package com.gyro.api.common.ratelimit

import java.time.Duration

interface RateLimitService {
    fun check(key: String, limit: Long, window: Duration)
}
