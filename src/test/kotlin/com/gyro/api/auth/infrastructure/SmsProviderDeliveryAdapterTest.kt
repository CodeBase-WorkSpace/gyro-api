package com.gyro.api.auth.infrastructure

import tools.jackson.databind.ObjectMapper
import com.gyro.api.auth.application.verification.VerificationChannel
import com.gyro.api.auth.application.verification.VerificationDeliveryRequest
import com.gyro.api.auth.application.verification.VerificationPurpose
import com.gyro.api.auth.config.SmsIrProperties
import com.gyro.api.auth.config.SmsIrTemplateProperties
import com.gyro.api.auth.config.VerificationDeliveryProperties
import com.gyro.api.auth.config.VerificationSmsProvider
import com.gyro.api.common.error.ExternalServiceException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmsProviderDeliveryAdapterTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sms ir adapter posts verify request with configured headers and body`() {
        server.enqueue(MockResponse(code = 200, body = """{"status":1,"message":"موفق"}"""))
        server.start()

        val adapter = SmsProviderDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                smsIr = SmsIrProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    login = SmsIrTemplateProperties(
                        templateId = 123456,
                        parameterName = "Code",
                    ),
                ),
            ),
            objectMapper = ObjectMapper(),
        )

        adapter.deliver(
            VerificationDeliveryRequest(
                channel = VerificationChannel.SMS,
                identifier = "+989121234567",
                purpose = VerificationPurpose.LOGIN,
                code = "654321",
            )
        )

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertEquals("/v1/send/verify", recorded.target)
        assertEquals("POST", recorded.method)
        assertEquals("sandbox-key", recorded.headers["x-api-key"])
        assertEquals("application/json", recorded.headers["Accept"])
        assertTrue(body.contains("\"templateId\":123456"))
        assertTrue(body.contains("\"mobile\":\"+989121234567\""))
        assertTrue(body.contains("\"name\":\"Code\""))
        assertTrue(body.contains("\"value\":\"654321\""))
    }

    @Test
    fun `sms ir adapter translates upstream failure into external service exception`() {
        server.enqueue(MockResponse(code = 500, body = """{"status":0,"message":"error"}"""))
        server.start()

        val adapter = SmsProviderDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                smsIr = SmsIrProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    signup = SmsIrTemplateProperties(
                        templateId = 258377,
                        parameterName = "SignupCode",
                    ),
                ),
            ),
            objectMapper = ObjectMapper(),
        )

        assertFailsWith<ExternalServiceException> {
            adapter.deliver(
                VerificationDeliveryRequest(
                    channel = VerificationChannel.SMS,
                    identifier = "09121234567",
                    purpose = VerificationPurpose.SIGNUP,
                    code = "123456",
                )
            )
        }
    }

    @Test
    fun `sms ir adapter rejects unsuccessful api payloads even on http 200`() {
        server.enqueue(MockResponse(code = 200, body = """{"status":0,"message":"failed"}"""))
        server.start()

        val adapter = SmsProviderDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                smsIr = SmsIrProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    passwordReset = SmsIrTemplateProperties(
                        templateId = 777777,
                        parameterName = "ResetCode",
                    ),
                ),
            ),
            objectMapper = ObjectMapper(),
        )

        assertFailsWith<ExternalServiceException> {
            adapter.deliver(
                VerificationDeliveryRequest(
                    channel = VerificationChannel.SMS,
                    identifier = "09121234567",
                    purpose = VerificationPurpose.PASSWORD_RESET,
                    code = "123456",
                )
            )
        }
    }

    @Test
    fun `sms ir adapter translates malformed api payload into external service exception`() {
        server.enqueue(MockResponse(code = 200, body = """not-json"""))
        server.start()

        val adapter = SmsProviderDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                smsIr = SmsIrProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    login = SmsIrTemplateProperties(
                        templateId = 123456,
                        parameterName = "Code",
                    ),
                ),
            ),
            objectMapper = ObjectMapper(),
        )

        assertFailsWith<ExternalServiceException> {
            adapter.deliver(
                VerificationDeliveryRequest(
                    channel = VerificationChannel.SMS,
                    identifier = "09121234567",
                    purpose = VerificationPurpose.LOGIN,
                    code = "123456",
                )
            )
        }
    }

    @Test
    fun `sms ir adapter uses purpose-specific template and parameter name`() {
        server.enqueue(MockResponse(code = 200, body = """{"status":1,"message":"موفق"}"""))
        server.start()

        val adapter = SmsProviderDeliveryAdapter(
            properties = VerificationDeliveryProperties(
                smsProvider = VerificationSmsProvider.SMS_IR,
                smsIr = SmsIrProperties(
                    baseUrl = server.url("/v1").toString().removeSuffix("/"),
                    apiKey = "sandbox-key",
                    signup = SmsIrTemplateProperties(
                        templateId = 258377,
                        parameterName = "SignupCode",
                    ),
                    login = SmsIrTemplateProperties(
                        templateId = 111111,
                        parameterName = "LoginCode",
                    ),
                ),
            ),
            objectMapper = ObjectMapper(),
        )

        adapter.deliver(
            VerificationDeliveryRequest(
                channel = VerificationChannel.SMS,
                identifier = "09121234567",
                purpose = VerificationPurpose.SIGNUP,
                code = "112233",
            )
        )

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"templateId\":258377"))
        assertTrue(body.contains("\"name\":\"SignupCode\""))
        assertTrue(body.contains("\"value\":\"112233\""))
    }
}
