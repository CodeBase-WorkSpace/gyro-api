package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.NotificationDestinationResolver
import com.gyro.api.notification.application.ProductSmsQuotaReservationService
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.NotificationChannel
import com.gyro.api.notification.domain.NotificationType
import com.gyro.api.notification.domain.RenderedNotification
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class SmsIrNotificationAdapterTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `HTTP 429 is rate limited`() {
        val notification = notification()
        val adapter = startAndCreateAdapter(MockResponse(code = 429, body = "{}"), notification)

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.THROTTLED, result.outcome)
        assertEquals(AdapterClassification.RATE_LIMIT, result.classification)
    }

    @Test
    fun `HTTP 500 is transient`() {
        val notification = notification()
        val adapter = startAndCreateAdapter(MockResponse(code = 500, body = "{}"), notification)

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    @Test
    fun `malformed provider JSON is permanent`() {
        val notification = notification()
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = "not-json"), notification)

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_PERMANENT, result.classification)
    }

    @Test
    fun `provider business error is permanent`() {
        val notification = notification()
        val adapter = startAndCreateAdapter(MockResponse(code = 200, body = """{\"status\":0,\"message\":\"rejected\"}"""), notification)

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.PERMANENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_PERMANENT, result.classification)
    }

    @Test
    fun `provider timeout is transient`() {
        val notification = notification()
        val client = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{\"status\":1}""")
                .bodyDelay(1, TimeUnit.SECONDS)
                .build(),
        )
        server.start()
        val adapter = createAdapter(client, notification)

        val result = adapter.deliver(notification)

        assertEquals(AdapterOutcome.TRANSIENT_FAILURE, result.outcome)
        assertEquals(AdapterClassification.PROVIDER_TRANSIENT, result.classification)
    }

    private fun startAndCreateAdapter(response: MockResponse, notification: RenderedNotification): SmsIrNotificationAdapter {
        server.enqueue(response)
        server.start()
        return createAdapter(OkHttpClient(), notification)
    }

    private fun createAdapter(client: OkHttpClient, notification: RenderedNotification): SmsIrNotificationAdapter {
        val destinations = Mockito.mock(NotificationDestinationResolver::class.java)
        val quotas = Mockito.mock(ProductSmsQuotaReservationService::class.java)
        val userId = UUID.randomUUID()
        Mockito.`when`(destinations.resolve(notification)).thenReturn("09121234567")
        Mockito.`when`(destinations.userId(notification)).thenReturn(userId)
        Mockito.`when`(quotas.reserveProviderAttempt(userId)).thenReturn(true)
        return SmsIrNotificationAdapter(
            properties = NotificationProperties(
                productSmsBaseUrl = server.url("/v1").toString().removeSuffix("/"),
                productSmsApiKey = "test-api-key",
                productSmsLineNumber = "3000",
            ),
            destinations = destinations,
            quotas = quotas,
            objectMapper = ObjectMapper(),
            client = client,
        )
    }

    private fun notification() = RenderedNotification(
        intentId = UUID.randomUUID(),
        deliveryId = UUID.randomUUID(),
        type = NotificationType.PAYMENT_VERIFIED,
        channel = NotificationChannel.SMS,
        endpointReference = "account:phone",
        providerRequestId = "provider-request",
        subject = null,
        plainBody = "رسید پرداخت",
        htmlBody = null,
    )
}
