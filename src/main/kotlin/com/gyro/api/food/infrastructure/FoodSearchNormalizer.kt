package com.gyro.api.food.infrastructure

import org.springframework.stereotype.Component

@Component
class FoodSearchNormalizer {
    fun normalizeSearchQuery(query: String?): NormalizedFoodSearchQuery {
        val raw = query.orEmpty()
        val locale = detectLocale(raw)
        val normalized = if (locale == "fa") {
            normalizeFarsi(raw)
        } else {
            normalizeEnglish(raw)
        }

        return NormalizedFoodSearchQuery(
            value = normalized,
            locale = locale,
            isBlank = normalized.isBlank(),
        )
    }

    private fun detectLocale(raw: String): String = if (raw.any { it in FARSI_RANGE || it in ARABIC_RANGE }) "fa" else "en"

    fun normalizeFoodName(name: String): String {
        val locale = detectLocale(name)

        return if (locale == "fa") {
            normalizeFarsi(name)
        } else {
            normalizeEnglish(name)
        }
    }

    fun normalizeEnglish(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(LEADING_TRAILING_PUNCTUATION, "")
            .replace(WHITESPACE, " ")
            .trim()
    }

    fun normalizeFarsi(value: String): String {
        return value
            .trim()
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace(ARABIC_FARSI_DIGITS) { match -> normalizeDigit(match.value.first()) }
            .replace(DIACRITICS, "")
            .replace(ZERO_WIDTH_NON_JOINERS, "")
            .replace(LEADING_TRAILING_PUNCTUATION, "")
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun normalizeDigit(char: Char): String {
        val digit = when (char) {
            in '۰'..'۹' -> char.code - '۰'.code
            in '٠'..'٩' -> char.code - '٠'.code
            else -> return char.toString()
        }

        return digit.toString()
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val LEADING_TRAILING_PUNCTUATION = Regex("^[\\p{Punct}،؛؟«»]+|[\\p{Punct}،؛؟«»]+$")
        private val DIACRITICS = Regex("[\\u064B-\\u065F\\u0670]")
        private val ZERO_WIDTH_NON_JOINERS = Regex("[\\u200B-\\u200F\\uFEFF]")
        private val ARABIC_FARSI_DIGITS = Regex("[۰-۹٠-٩]")
        private val FARSI_RANGE = '\u0600'..'\u06FF'
        private val ARABIC_RANGE = '\u0750'..'\u077F'
    }
}

data class NormalizedFoodSearchQuery(
    val value: String,
    val locale: String,
    val isBlank: Boolean,
)
