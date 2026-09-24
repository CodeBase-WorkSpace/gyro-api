package com.gyro.api.auth.infrastructure

import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.config.KavenegarProperties
import com.gyro.api.auth.config.KavenegarTemplateProperties
import com.gyro.api.auth.config.VerificationDeliveryProperties
import com.gyro.api.auth.config.VerificationSmsProvider
import com.gyro.api.common.error.ExternalServiceException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KavenegarSmsDeliveryAdapterTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `posts Kavenegar verify lookup request with configured template`() {
        server.enqueue(MockResponse(code = 200, body = """{"return":{"status":200,"message":"OK"},"entries":[]}"""))
        server.start()
        val adapter = adapter(loginTemplate = "gyro-login")

        adapter.deliver(request(VerificationPurpose.LOGIN, "+989121234567", "654321"))

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertEquals("/v1/sandbox-key/verify/lookup.json", recorded.target)
        assertEquals("POST", recorded.method)
        assertEquals("application/json", recorded.headers["Accept"])
        assertTrue(body.contains("receptor=%2B989121234567"))
        assertTrue(body.contains("token=654321"))
        assertTrue(body.contains("template=gyro-login"))
    }

    @ParameterizedTest
    @MethodSource("purposeTemplates")
    fun `routes verification purposes to approved Kavenegar templates`(
        purpose: VerificationPurpose,
        expectedTemplate: String,
    ) {
        server.enqueue(MockResponse(code = 200, body = """{"return":{"status":200,"message":"OK"},"entries":[]}"""))
        server.start()

        adapter().deliver(request(purpose, "09121234567", "123456"))

        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("template=$expectedTemplate"))
    }

    @Test
    fun `translates non successful Kavenegar response into external service exception`() {
        server.enqueue(MockResponse(code = 200, body = """{"return":{"status":400,"message":"Invalid template"},"entries":[]}"""))
        server.start()

        assertFailsWith<ExternalServiceException> {
            adapter().deliver(request(VerificationPurpose.SIGNUP, "09121234567", "123456"))
        }
    }

    @Test
    fun `translates malformed Kavenegar response into external service exception`() {
        server.enqueue(MockResponse(code = 200, body = "not-json"))
        server.start()

        assertFailsWith<ExternalServiceException> {
            adapter().deliver(request(VerificationPurpose.SIGNUP, "09121234567", "123456"))
        }
    }

    private fun adapter(loginTemplate: String = "welcomeotp"): KavenegarSmsDeliveryAdapter {
        return KavenegarSmsDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.KAVENEGAR,
                kavenegar = KavenegarProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    signup = KavenegarTemplateProperties("welcomeotp"),
                    login = KavenegarTemplateProperties(loginTemplate),
                    passwordReset = KavenegarTemplateProperties("passwordotp"),
                    changeIdentifier = KavenegarTemplateProperties("welcomeotp"),
                ),
            ),
            objectMapper = ObjectMapper(),
        )
    }

    private fun request(
        purpose: VerificationPurpose,
        identifier: String,
        code: String,
    ) = VerificationDeliveryRequest(
        channel = VerificationChannel.SMS,
        identifier = identifier,
        purpose = purpose,
        code = code,
    )

    companion object {
        @JvmStatic
        fun purposeTemplates() = listOf(
            Arguments.of(VerificationPurpose.SIGNUP, "welcomeotp"),
            Arguments.of(VerificationPurpose.LOGIN, "welcomeotp"),
            Arguments.of(VerificationPurpose.PASSWORD_RESET, "passwordotp"),
        )
    }
}
