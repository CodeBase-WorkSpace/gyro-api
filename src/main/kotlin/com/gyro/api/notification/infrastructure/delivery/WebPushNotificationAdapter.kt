package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.PushSubscriptionService
import com.gyro.api.notification.application.ActivePushSubscription
import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import com.gyro.api.notification.infrastructure.observability.NotificationMetrics
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.Encoding
import nl.martijndwars.webpush.PushService
import jakarta.annotation.PreDestroy
import org.apache.http.client.methods.HttpPost
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.Security
import java.util.concurrent.ExecutionException
import tools.jackson.databind.ObjectMapper

interface WebPushClient {
    fun send(subscription: com.gyro.api.notification.application.ActivePushSubscription, payload: String): WebPushProviderResponse
}

enum class WebPushProviderClassification {
    BAD_JWT,
    EXPIRED_SUBSCRIPTION,
    INVALID_AUDIENCE,
    RATE_LIMITED,
    PROVIDER_ERROR,
}

data class WebPushProviderResponse(
    val statusCode: Int,
    val classification: WebPushProviderClassification? = null,
)

internal class WebPushTransportException(
    val mayHaveReachedProvider: Boolean,
    cause: IOException,
) : IOException(cause)

@Component
class VapidWebPushClient(private val properties: NotificationProperties) : WebPushClient {
    private val httpClient = NotificationHttpClientFactory.build(properties, properties.webPushTimeout)

    private val service by lazy {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        PushService()
            .setSubject(properties.webPushSubject)
            .setPublicKey(requireNotNull(properties.webPushPublicKey?.takeIf(String::isNotBlank)))
            .setPrivateKey(requireNotNull(properties.webPushPrivateKey?.takeIf(String::isNotBlank)))
    }

    override fun send(subscription: com.gyro.api.notification.application.ActivePushSubscription, payload: String): WebPushProviderResponse {
        val prepared = prepareWebPush(service, Notification(subscription.endpoint, subscription.p256dh, subscription.auth, payload))
        val body = (prepared.entity?.content?.use { it.readBytes() } ?: ByteArray(0)).toRequestBody()
        val transmission = NotificationRequestTransmission()
        val request = Request.Builder()
            .url(prepared.uri.toString())
            .post(body)
            .also { builder -> prepared.allHeaders.forEach { builder.addHeader(it.name, it.value) } }
            .tag(NotificationRequestTransmission::class.java, transmission)
            .build()
        try {
            return httpClient.newCall(request).execute().use { response ->
                val providerBody = readBoundedProviderBody(response.body)
                WebPushProviderResponse(response.code, classifyProviderResponse(response.code, providerBody))
            }
        } catch (exception: IOException) {
            throw WebPushTransportException(transmission.mayHaveReachedProvider, exception)
        }
    }

    @PreDestroy
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private companion object {
        const val MAX_PROVIDER_BODY_CLASSIFICATION_LENGTH = 1_024
    }

    private fun readBoundedProviderBody(body: ResponseBody?): String? = body?.charStream()?.use { reader ->
        val buffer = CharArray(MAX_PROVIDER_BODY_CLASSIFICATION_LENGTH)
        val length = reader.read(buffer)
        if (length <= 0) null else String(buffer, 0, length)
    }
}

internal fun classifyProviderResponse(statusCode: Int, body: String?): WebPushProviderClassification? {
    if (statusCode in 200..299) return null
    if (statusCode in setOf(404, 410)) return WebPushProviderClassification.EXPIRED_SUBSCRIPTION
    if (statusCode == 429) return WebPushProviderClassification.RATE_LIMITED
    val normalized = body.orEmpty().lowercase()
    return when {
        "badjwttoken" in normalized || "bad jwt" in normalized -> WebPushProviderClassification.BAD_JWT
        "audience" in normalized || "badaud" in normalized -> WebPushProviderClassification.INVALID_AUDIENCE
        else -> WebPushProviderClassification.PROVIDER_ERROR
    }
}

internal fun prepareWebPush(service: PushService, notification: Notification): HttpPost =
    service.preparePost(notification, Encoding.AES128GCM)

@Component
class WebPushNotificationAdapter(
    private val subscriptions: PushSubscriptionService,
    private val properties: NotificationProperties,
    private val intents: NotificationIntentRepository,
    private val client: WebPushClient,
    private val objectMapper: ObjectMapper,
    private val metrics: NotificationMetrics,
) : NotificationChannelAdapter {
    override val adapterKey = "web-push"

    /**
     * Native Web Push has no provider-side idempotency lookup keyed by `providerRequestId`.
     * After an incomplete attempt we cannot prove whether provider accepted send, so never
     * submit same Push again. Core records bounded unknown outcome instead of duplicate alert.
     */
    override fun reconcile(notification: RenderedNotification): AdapterResult =
        AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)

    override fun deliver(notification: RenderedNotification): AdapterResult {
        val userId = intents.findById(notification.intentId).orElse(null)?.userId
            ?: return AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        val activeSubscriptions = subscriptions.activeAll(userId)
        if (activeSubscriptions.isEmpty()) return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        if (!properties.webPushEnabled) return AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        if (properties.webPushPublicKey.isNullOrBlank() || properties.webPushPrivateKey.isNullOrBlank()) return AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "title" to (notification.subject?.takeIf(String::isNotBlank) ?: "جیرو"),
                "body" to notification.plainBody,
                "url" to "/dashboard",
                "tag" to notification.providerRequestId,
            ),
        )
        val results = mutableListOf<AdapterResult>()
        metrics.pushDevicesTargeted(activeSubscriptions.size)
        for (subscription in activeSubscriptions) {
            val result = deliverToSubscription(notification, userId, subscription, payload)
            results += result
            metrics.pushDeviceOutcome(result.outcome)
            if (Thread.currentThread().isInterrupted) break
        }
        logDeliverySummary(notification, activeSubscriptions.size, results)
        return aggregate(results)
    }

    private fun deliverToSubscription(
        notification: RenderedNotification,
        userId: java.util.UUID,
        subscription: ActivePushSubscription,
        payload: String,
    ): AdapterResult {
        return try {
            val provider = client.send(subscription, payload)
            val result = resultForStatus(provider.statusCode, userId, subscription.fingerprint)
            log.info(
                "event=notification_delivery adapter={} outcome={} providerStatus={} providerClassification={} intentId={} userId={} subscription={}",
                adapterKey,
                result.outcome,
                provider.statusCode,
                provider.classification,
                notification.intentId,
                userId,
                subscription.fingerprint,
            )
            result
        } catch (exception: WebPushTransportException) {
            val result = if (exception.mayHaveReachedProvider) uncertainSend() else transientFailure()
            log.warn(
                "event=notification_delivery adapter={} outcome={} intentId={} subscription={} exception={}",
                adapterKey,
                result.outcome,
                notification.intentId,
                subscription.fingerprint,
                exception.cause?.let { it::class.simpleName } ?: exception::class.simpleName,
            )
            result
        } catch (exception: IOException) {
            log.warn(
                "event=notification_delivery adapter={} outcome=unknown_after_send intentId={} subscription={} exception={}",
                adapterKey,
                notification.intentId,
                subscription.fingerprint,
                exception::class.simpleName,
            )
            uncertainSend()
        } catch (exception: ExecutionException) {
            log.warn("event=notification_delivery adapter={} outcome=unknown_after_send intentId={} subscription={} exception={}", adapterKey, notification.intentId, subscription.fingerprint, exception::class.simpleName)
            uncertainSend()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("event=notification_delivery adapter={} outcome=unknown_after_send intentId={} subscription={} exception={}", adapterKey, notification.intentId, subscription.fingerprint, exception::class.simpleName)
            uncertainSend()
        } catch (exception: Exception) {
            log.warn(
                "event=notification_delivery adapter={} outcome=permanent_failure intentId={} userId={} subscription={} exception={}",
                adapterKey,
                notification.intentId,
                userId,
                subscription.fingerprint,
                exception::class.simpleName,
            )
            AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        }
    }

    internal fun aggregate(results: List<AdapterResult>): AdapterResult {
        // A Push intent succeeds when at least one active device accepted it. Per-device
        // counters and the summary log retain partial/unknown outcomes for operations.
        results.firstOrNull { it.outcome == AdapterOutcome.SUCCESS }?.let { return it }
        results.firstOrNull { it.outcome == AdapterOutcome.UNKNOWN_AFTER_SEND }?.let { return it }
        results.firstOrNull { it.outcome == AdapterOutcome.THROTTLED }?.let { return it }
        results.firstOrNull { it.outcome == AdapterOutcome.TRANSIENT_FAILURE }?.let { return it }
        if (results.isNotEmpty() && results.all { it.outcome == AdapterOutcome.INVALID_ENDPOINT }) return results.first()
        return AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
    }

    internal fun resultForStatus(status: Int, userId: java.util.UUID, fingerprint: String): AdapterResult =
        when (status) {
                in 200..299 -> AdapterResult(AdapterOutcome.SUCCESS, AdapterClassification.LOG_ONLY_SUCCESS)
                404, 410 -> { subscriptions.revoke(userId, fingerprint); AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID) }
                429 -> AdapterResult(AdapterOutcome.THROTTLED, AdapterClassification.RATE_LIMIT)
                in 500..599 -> AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT)
                else -> AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
            }

    private fun uncertainSend() = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)

    private fun transientFailure() = AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT)

    private fun logDeliverySummary(
        notification: RenderedNotification,
        targeted: Int,
        results: List<AdapterResult>,
    ) {
        fun count(outcome: AdapterOutcome) = results.count { it.outcome == outcome }
        val failed = results.size - count(AdapterOutcome.SUCCESS) - count(AdapterOutcome.INVALID_ENDPOINT) - count(AdapterOutcome.UNKNOWN_AFTER_SEND)
        log.info(
            "event=web_push_delivery_summary contract=AT_LEAST_ONE_DEVICE_ACCEPTED intentId={} targeted={} succeeded={} invalid={} unknown={} failed={}",
            notification.intentId,
            targeted,
            count(AdapterOutcome.SUCCESS),
            count(AdapterOutcome.INVALID_ENDPOINT),
            count(AdapterOutcome.UNKNOWN_AFTER_SEND),
            failed,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebPushNotificationAdapter::class.java)
    }
}
