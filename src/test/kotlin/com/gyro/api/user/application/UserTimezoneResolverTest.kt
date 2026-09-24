package com.gyro.api.user.application

import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.domain.UserProfile
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class UserTimezoneResolverTest {
    private val userId = UUID.randomUUID()
    private val profiles = Mockito.mock(UserProfileRepository::class.java)
    private val resolver = UserTimezoneResolver(
        profiles,
        UserPreferencesProperties(defaultTimezone = "Asia/Tehran"),
    )

    @Test
    fun `invalid persisted timezone safely falls back to configured default`() {
        val profile = Mockito.mock(UserProfile::class.java)
        Mockito.`when`(profile.timezone).thenReturn("not-a-timezone")
        Mockito.`when`(profiles.findByUser_Id(userId)).thenReturn(profile)

        assertEquals(ZoneId.of("Asia/Tehran"), resolver.resolve(userId))
    }
}
