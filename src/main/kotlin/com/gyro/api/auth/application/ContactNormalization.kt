package com.gyro.api.auth.application

import com.gyro.api.common.error.ApiErrorResponse
import com.gyro.api.common.error.FieldValidationException
import java.util.Locale

object EmailNormalizer {
    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun normalize(rawEmail: String): String {
        val normalized = rawEmail.trim().lowercase(Locale.ROOT)
        if (!EMAIL_PATTERN.matches(normalized)) {
            throw contactValidationException("email", "Email is not valid.")
        }
        return normalized
    }

    fun isValid(rawEmail: String): Boolean {
        return runCatching { normalize(rawEmail) }.isSuccess
    }
}

object IranianPhoneValidator {
    private val PHONE_PATTERN = Regex("^(?:09\\d{9}|989\\d{9}|\\+989\\d{9})$")
    private val DIGIT_MAP = mapOf(
        '۰' to '0',
        '۱' to '1',
        '۲' to '2',
        '۳' to '3',
        '۴' to '4',
        '۵' to '5',
        '۶' to '6',
        '۷' to '7',
        '۸' to '8',
        '۹' to '9',
        '٠' to '0',
        '١' to '1',
        '٢' to '2',
        '٣' to '3',
        '٤' to '4',
        '٥' to '5',
        '٦' to '6',
        '٧' to '7',
        '٨' to '8',
        '٩' to '9',
    )

    fun normalize(rawPhoneNumber: String): String {
        val normalizedDigits = rawPhoneNumber.trim().normalizeDigits()
        if (!PHONE_PATTERN.matches(normalizedDigits)) {
            throw contactValidationException("phoneNumber", "Phone number is not valid.")
        }

        return when {
            normalizedDigits.startsWith("+") -> normalizedDigits
            normalizedDigits.startsWith("98") -> "+$normalizedDigits"
            else -> "+98${normalizedDigits.drop(1)}"
        }
    }

    fun isValid(rawPhoneNumber: String): Boolean {
        return runCatching { normalize(rawPhoneNumber) }.isSuccess
    }

    private fun String.normalizeDigits(): String {
        return map { DIGIT_MAP[it] ?: it }.joinToString("")
    }
}

data class NormalizedContact(
    val email: String? = null,
    val phoneNumber: String? = null,
) {
    val type: String
        get() = when {
            email != null && phoneNumber != null -> "email_and_phone"
            email != null -> "email"
            phoneNumber != null -> "phone"
            else -> "missing"
        }
}

fun normalizeContact(email: String?, phoneNumber: String?): NormalizedContact {
    return NormalizedContact(
        email = email?.takeIf { it.isNotBlank() }?.let(EmailNormalizer::normalize),
        phoneNumber = phoneNumber?.takeIf { it.isNotBlank() }?.let(IranianPhoneValidator::normalize),
    )
}

data class NormalizedIdentifier(
    val label: String,
    val value: String,
) {
    val isEmail: Boolean
        get() = label == EMAIL_LABEL

    val fieldName: String
        get() = if (isEmail) "email" else "phoneNumber"

    fun publicType(): String {
        return if (isEmail) EMAIL_LABEL else PHONE_LABEL
    }

    companion object {
        fun from(rawIdentifier: String): NormalizedIdentifier {
            val trimmed = rawIdentifier.trim()
            if (trimmed.contains("@")) {
                return NormalizedIdentifier(EMAIL_LABEL, EmailNormalizer.normalize(trimmed))
            }
            return NormalizedIdentifier(PHONE_LABEL, IranianPhoneValidator.normalize(trimmed))
        }

        const val EMAIL_LABEL = "email"
        const val PHONE_LABEL = "phone"
    }
}

private fun contactValidationException(field: String, message: String): FieldValidationException {
    return FieldValidationException(
        fieldErrors = listOf(
            ApiErrorResponse.FieldError(
                field = field,
                errorMessage = message,
                code = "INVALID",
            )
        )
    )
}
