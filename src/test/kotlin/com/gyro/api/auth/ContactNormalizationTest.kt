package com.gyro.api.auth

import com.gyro.api.auth.application.EmailNormalizer
import com.gyro.api.auth.application.IranianPhoneValidator
import com.gyro.api.common.error.FieldValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContactNormalizationTest {
    @Test
    fun `email normalizer trims and lowercases the entire email`() {
        assertEquals("tester@example.invalid", EmailNormalizer.normalize(" Tester@Example.invalid "))
        assertEquals("second@example.invalid", EmailNormalizer.normalize("SECOND@EXAMPLE.INVALID"))
        assertEquals("user@example.com", EmailNormalizer.normalize("user@EXAMPLE.COM"))
    }

    @Test
    fun `iranian phone validator normalizes accepted formats to e164`() {
        assertEquals("+989121234567", IranianPhoneValidator.normalize("09121234567"))
        assertEquals("+989121234567", IranianPhoneValidator.normalize("989121234567"))
        assertEquals("+989121234567", IranianPhoneValidator.normalize("+989121234567"))
        assertEquals("+989121234567", IranianPhoneValidator.normalize("۰۹۱۲۱۲۳۴۵۶۷"))
        assertEquals("+989121234567", IranianPhoneValidator.normalize("٠٩١٢١٢٣٤٥٦٧"))
    }

    @Test
    fun `iranian phone validator rejects malformed phone numbers`() {
        listOf(
            "08121234567",
            "09123",
            "+9809121234567",
            "abc",
            "+98abc",
            "++989121234567",
        ).forEach { phoneNumber ->
            assertThrows(FieldValidationException::class.java) {
                IranianPhoneValidator.normalize(phoneNumber)
            }
        }
    }
}
