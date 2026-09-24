package com.gyro.api.user.config

import com.gyro.api.common.error.InvalidProfilePreferenceException
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.DateTimeException
import java.time.ZoneId
import java.util.IllformedLocaleException
import java.util.Locale

@ConfigurationProperties(prefix = "app.user-preferences")
data class UserPreferencesProperties(
    val defaultTimezone: String = "Asia/Tehran",
    val defaultLocale: String = "fa-IR",
) {
    val normalizedDefaultTimezone: String = normalizeTimezone(defaultTimezone)
    val normalizedDefaultLocale: String = normalizeLocale(defaultLocale)

    companion object {
        private val localePattern = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2}|-[0-9]{3})?$")

        fun normalizeTimezone(value: String): String {
            return try {
                ZoneId.of(value.trim()).id
            } catch (ex: DateTimeException) {
                throw InvalidProfilePreferenceException("timezone")
            }
        }

        fun normalizeLocale(value: String): String {
            val normalized = value.trim().replace('_', '-')
            if (!normalized.matches(localePattern)) {
                throw InvalidProfilePreferenceException("locale")
            }

            return try {
                Locale.Builder().setLanguageTag(normalized).build().toLanguageTag()
            } catch (ex: IllformedLocaleException) {
                throw InvalidProfilePreferenceException("locale")
            }
        }
    }
}
