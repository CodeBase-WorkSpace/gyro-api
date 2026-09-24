package com.gyro.api.common.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Configuration
class TimeConfiguration {
    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }
}

@Component
class TimeProvider(
    private val clock: Clock,
) {
    fun now(): Instant {
        return Instant.now(clock)
    }

    fun today(zoneId: ZoneId): LocalDate {
        return LocalDate.now(clock.withZone(zoneId))
    }

    fun toUserDate(
        instant: Instant,
        zoneId: ZoneId,
    ): LocalDate {
        return instant.atZone(zoneId).toLocalDate()
    }
}
