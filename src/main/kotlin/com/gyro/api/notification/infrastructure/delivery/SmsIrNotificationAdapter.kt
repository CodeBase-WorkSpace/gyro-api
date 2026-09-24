package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.auth.application.IranianPhoneValidator
import com.gyro.api.notification.application.NotificationDestinationResolver
import com.gyro.api.notification.application.ProductSmsQuotaReservationService
import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.security.MessageDigest

@Component
@ConditionalOnProperty(prefix = "app.notification", name = ["product-sms-enabled"], havingValue = "true")
class SmsIrNotificationAdapter(
    private val properties: NotificationProperties,
    private val destinations: NotificationDestinationResolver,
    private val quotas: ProductSmsQuotaReservationService,
    private val objectMapper: ObjectMapper,
    private val client: OkHttpClient = OkHttpClient(),
) : NotificationChannelAdapter {
    override val adapterKey = "sms-ir"

    override fun reconcile(notification: RenderedNotification): AdapterResult = UNKNOWN_AFTER_SEND

    override fun deliver(notification: RenderedNotification): AdapterResult {
        val destination = destinations.resolve(notification)
            ?.let { runCatching { IranianPhoneValidator.normalize(it) }.getOrNull() }
            ?: return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        val userId = destinations.userId(notification)
            ?: return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        val requestBody = objectMapper.writeValueAsString(
            SmsIrBulkRequest(
                lineNumber = properties.productSmsLineNumber,
                messageText = notification.plainBody,
                mobiles = listOf(destination),
            ),
        )
        val request = Request.Builder()
            .url("${properties.productSmsBaseUrl.trimEnd('/')}/send/bulk")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("Accept", "application/json")
            .addHeader("x-api-key", properties.productSmsApiKey)
            .build()
        if (!quotas.reserveProviderAttempt(userId)) {
            return AdapterResult(AdapterOutcome.THROTTLED, AdapterClassification.RATE_LIMIT)
        }
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 429 -> AdapterResult(AdapterOutcome.THROTTLED, AdapterClassification.RATE_LIMIT)
                    response.code in 500..599 -> TRANSIENT_FAILURE
                    !response.isSuccessful -> AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
                    objectMapper.readTree(body).path("status").asInt() != SUCCESS_STATUS -> AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
                    else -> AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS, digest(body))
                }
            }
        } catch (_: IOException) {
            TRANSIENT_FAILURE
        } catch (_: Exception) {
            AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private data class SmsIrBulkRequest(
        val lineNumber: String,
        val messageText: String,
        val mobiles: List<String>,
        val sendDateTime: Long? = null,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val TRANSIENT_FAILURE = AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT)
        val UNKNOWN_AFTER_SEND = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)
        const val SUCCESS_STATUS = 1
    }
}
