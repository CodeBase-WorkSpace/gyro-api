package com.gyro.api.notification.infrastructure.delivery

import com.gyro.api.notification.application.TelegramAccountLinkingService
import com.gyro.api.notification.application.delivery.NotificationChannelAdapter
import com.gyro.api.notification.config.NotificationProperties
import com.gyro.api.notification.domain.AdapterClassification
import com.gyro.api.notification.domain.AdapterOutcome
import com.gyro.api.notification.domain.AdapterResult
import com.gyro.api.notification.domain.RenderedNotification
import com.gyro.api.notification.infrastructure.persistence.NotificationIntentRepository
import jakarta.annotation.PreDestroy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration

enum class TelegramSendFailure { BLOCKED, INVALID_CHAT, OTHER }

data class TelegramBotSendResult(
    val result: AdapterResult,
    val failure: TelegramSendFailure? = null,
)

interface TelegramDirectMessageSender {
    fun send(chatId: String, text: String): TelegramBotSendResult
}

data class TelegramWebhookInfo(
    val url: String,
    val pendingUpdateCount: Int,
    val lastErrorMessage: String?,
)

sealed interface TelegramWebhookResult {
    data class Success(val info: TelegramWebhookInfo) : TelegramWebhookResult
    data object Unavailable : TelegramWebhookResult
}

/** Shared, token-safe Bot API transport for operator and personal Telegram adapters. */
@Component
@ConditionalOnProperty(prefix = "app.notification", name = ["telegram-enabled"], havingValue = "true")
class TelegramBotClient(
    private val properties: NotificationProperties,
    private val objectMapper: ObjectMapper,
    private val client: OkHttpClient = buildClient(properties),
) : TelegramDirectMessageSender {
    fun ensureWebhook(url: String, secret: String): TelegramWebhookResult {
        val body = objectMapper.writeValueAsString(
            mapOf("url" to url, "secret_token" to secret, "allowed_updates" to listOf("message")),
        )
        val request = botRequest("setWebhook").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        val configured = executeBotApi(request)?.path("ok")?.asBoolean(false) == true
        return if (configured) webhookInfo() else TelegramWebhookResult.Unavailable
    }

    fun webhookInfo(): TelegramWebhookResult {
        val request = botRequest("getWebhookInfo").get().build()
        val result = executeBotApi(request)?.takeIf { it.path("ok").asBoolean(false) }?.path("result")
            ?: return TelegramWebhookResult.Unavailable
        return TelegramWebhookResult.Success(
            TelegramWebhookInfo(
                url = result.path("url").asString(""),
                pendingUpdateCount = result.path("pending_update_count").asInt(0),
                lastErrorMessage = result.path("last_error_message").asString().takeIf(String::isNotBlank),
            ),
        )
    }

    override fun send(chatId: String, text: String): TelegramBotSendResult {
        val requestBody = objectMapper.writeValueAsString(TelegramSendMessageRequest(chatId, text))
        val transmission = NotificationRequestTransmission()
        val request = Request.Builder()
            .url("${properties.telegramApiBaseUrl.trimEnd('/')}/bot${properties.telegramBotToken}/sendMessage")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Accept", "application/json")
            .tag(NotificationRequestTransmission::class.java, transmission)
            .build()
        // The bot token is embedded in the request URL. Never add logging interceptors or expose
        // request/response objects to application logs or exception messages.
        return try {
            client.newCall(request).execute().use { response ->
                val body = readBoundedBody(response)
                when {
                    response.code == 403 -> TelegramBotSendResult(PERMANENT_FAILURE, TelegramSendFailure.BLOCKED)
                    response.code == 400 && body.content?.contains("chat not found", ignoreCase = true) == true ->
                        TelegramBotSendResult(INVALID_ENDPOINT, TelegramSendFailure.INVALID_CHAT)
                    response.code == 429 -> TelegramBotSendResult(
                        AdapterResult(
                            AdapterOutcome.THROTTLED,
                            AdapterClassification.RATE_LIMIT,
                            retryAfter = body.content?.let(::retryAfter),
                        ),
                    )
                    response.code in 500..599 -> TelegramBotSendResult(TRANSIENT_FAILURE)
                    !response.isSuccessful -> TelegramBotSendResult(PERMANENT_FAILURE, TelegramSendFailure.OTHER)
                    // A 2xx response may already represent a published message. If the provider
                    // exceeds our defensive response limit, never parse the incomplete document
                    // and never classify the accepted send as a retryable or permanent failure.
                    body.overflow -> TelegramBotSendResult(UNKNOWN_AFTER_SEND)
                    else -> successfulResult(requireNotNull(body.content))
                }
            }
        } catch (_: IOException) {
            TelegramBotSendResult(if (transmission.mayHaveReachedProvider) UNKNOWN_AFTER_SEND else TRANSIENT_FAILURE)
        } catch (_: Exception) {
            TelegramBotSendResult(
                if (transmission.mayHaveReachedProvider) UNKNOWN_AFTER_SEND else PERMANENT_FAILURE,
                TelegramSendFailure.OTHER.takeUnless { transmission.mayHaveReachedProvider },
            )
        }
    }

    private fun botRequest(method: String): Request.Builder = Request.Builder()
        .url("${properties.telegramApiBaseUrl.trimEnd('/')}/bot${properties.telegramBotToken}/$method")
        .addHeader("Accept", "application/json")

    private fun executeBotApi(request: Request): tools.jackson.databind.JsonNode? = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = readBoundedBody(response)
            if (body.overflow) null else body.content?.let(objectMapper::readTree)
        }
    } catch (_: Exception) {
        null
    }

    @PreDestroy
    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun successfulResult(body: String): TelegramBotSendResult {
        val parsed = objectMapper.readTree(body)
        return if (parsed.path("ok").asBoolean(false)) {
            TelegramBotSendResult(
                AdapterResult(
                    AdapterOutcome.SUCCESS,
                    AdapterClassification.LOG_ONLY_SUCCESS,
                    digest(parsed.path("result").path("message_id").asString("")),
                ),
            )
        } else {
            TelegramBotSendResult(PERMANENT_FAILURE, TelegramSendFailure.OTHER)
        }
    }

    private fun readBoundedBody(response: okhttp3.Response): ProviderBody {
        val bytes = response.body?.byteStream()?.use { it.readNBytes(MAX_RESPONSE_BODY_BYTES + 1) }
            ?: return ProviderBody(content = "")
        if (bytes.size > MAX_RESPONSE_BODY_BYTES) return ProviderBody(overflow = true)
        return ProviderBody(content = String(bytes, Charsets.UTF_8))
    }

    private fun retryAfter(body: String): Duration? = runCatching {
        objectMapper.readTree(body).path("parameters").path("retry_after").asLong(0)
            .takeIf { it > 0 }
            ?.let(Duration::ofSeconds)
    }.getOrNull()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private data class TelegramSendMessageRequest(
        val chat_id: String,
        val text: String,
    )

    private data class ProviderBody(
        val content: String? = null,
        val overflow: Boolean = false,
    )

    companion object {
        // Telegram echoes the sent Message object, including up to 4,096 Unicode text
        // characters and entity metadata. Keep a generous byte limit, detect overflow,
        // and parse only complete JSON documents.
        private const val MAX_RESPONSE_BODY_BYTES = 256 * 1_024
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val TRANSIENT_FAILURE = AdapterResult(AdapterOutcome.TRANSIENT_FAILURE, AdapterClassification.PROVIDER_TRANSIENT)
        private val PERMANENT_FAILURE = AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        private val INVALID_ENDPOINT = AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        private val UNKNOWN_AFTER_SEND = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)

        fun buildClient(properties: NotificationProperties): OkHttpClient =
            NotificationHttpClientFactory.build(properties, properties.telegramTimeout)
    }
}

/** Existing operator-channel adapter. Its key and configured destination remain unchanged. */
@Component
@ConditionalOnProperty(prefix = "app.notification", name = ["telegram-enabled"], havingValue = "true")
class TelegramNotificationAdapter(
    private val properties: NotificationProperties,
    private val bot: TelegramBotClient,
) : NotificationChannelAdapter {
    override val adapterKey = "telegram-bot"

    override fun reconcile(notification: RenderedNotification): AdapterResult = UNKNOWN_AFTER_SEND

    override fun deliver(notification: RenderedNotification): AdapterResult {
        val chatId = properties.telegramChatId.takeIf(String::isNotBlank)
            ?: return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        val result = bot.send(chatId, notification.telegramText())
        // Preserve the operator adapter's established 400 classification. Endpoint invalidation
        // is meaningful only for a persisted personal endpoint owned by a Gyro user.
        return if (result.failure == TelegramSendFailure.INVALID_CHAT) {
            AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        } else {
            result.result
        }
    }
}

/** Personal Telegram delivery resolves only a verified endpoint owned by the intent recipient. */
@Component
@ConditionalOnProperty(
    prefix = "app.notification",
    name = ["telegram-enabled", "telegram-linking-enabled"],
    havingValue = "true",
)
class TelegramUserNotificationAdapter(
    private val linking: TelegramAccountLinkingService,
    private val intents: NotificationIntentRepository,
    private val bot: TelegramBotClient,
) : NotificationChannelAdapter {
    override val adapterKey = "telegram-user"

    override fun reconcile(notification: RenderedNotification): AdapterResult = UNKNOWN_AFTER_SEND

    override fun deliver(notification: RenderedNotification): AdapterResult {
        val userId = intents.findById(notification.intentId).orElse(null)?.userId
            ?: return AdapterResult(AdapterOutcome.PERMANENT_FAILURE, AdapterClassification.PROVIDER_PERMANENT)
        val endpoint = linking.active(userId)
            ?: return AdapterResult(AdapterOutcome.INVALID_ENDPOINT, AdapterClassification.ENDPOINT_INVALID)
        val result = bot.send(endpoint.chatId, notification.telegramText())
        when (result.failure) {
            TelegramSendFailure.BLOCKED -> linking.markBlocked(userId)
            TelegramSendFailure.INVALID_CHAT -> linking.markRevoked(userId)
            else -> Unit
        }
        return result.result
    }
}

private fun RenderedNotification.telegramText(): String = buildString {
    subject?.takeIf(String::isNotBlank)?.let { append(it).append("\n\n") }
    append(plainBody)
}

private val UNKNOWN_AFTER_SEND = AdapterResult(AdapterOutcome.UNKNOWN_AFTER_SEND, AdapterClassification.UNKNOWN_AFTER_SEND)
