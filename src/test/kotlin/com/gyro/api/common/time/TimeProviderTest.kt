package com.gyro.api.common.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeProviderTest {
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-10T21:30:00Z"),
        ZoneId.of("UTC"),
    )
    private val timeProvider = TimeProvider(fixedClock)

    @Test
    fun `today uses supplied user timezone`() {
        assertEquals(
            LocalDate.of(2026, 6, 11),
            timeProvider.today(ZoneId.of("Asia/Tehran")),
        )
    }

    @Test
    fun `toUserDate converts instant using supplied timezone`() {
        assertEquals(
            LocalDate.of(2026, 6, 9),
            timeProvider.toUserDate(
                instant = Instant.parse("2026-06-10T03:00:00Z"),
                zoneId = ZoneId.of("America/New_York"),
            ),
        )
    }
}
