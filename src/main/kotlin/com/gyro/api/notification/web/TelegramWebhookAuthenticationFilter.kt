package com.gyro.api.notification.web

import com.gyro.api.common.error.ApiErrorCode
import com.gyro.api.common.error.ApiErrorResponseWriter
import com.gyro.api.notification.config.NotificationProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Authenticates and bounds the public Telegram webhook before JSON deserialization. */
@Component
class TelegramWebhookAuthenticationFilter(
    @Value("\${app.api.base-path:/api/v1}") apiBasePath: String,
    private val properties: NotificationProperties,
    private val apiErrorResponseWriter: ApiErrorResponseWriter,
) : OncePerRequestFilter() {
    private val webhookPath = apiPath(apiBasePath, "/integrations/telegram/webhook")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.requestURI != webhookPath

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!properties.telegramEnabled || !properties.telegramLinkingEnabled || properties.telegramWebhookSecret.isBlank()) {
            apiErrorResponseWriter.write(
                response,
                HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                ApiErrorCode.EXTERNAL_SERVICE_UNAVAILABLE,
                "Telegram linking is unavailable.",
            )
            return
        }
        if (!secretMatches(request.getHeader(SECRET_HEADER), properties.telegramWebhookSecret)) {
            apiErrorResponseWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                ApiErrorCode.INVALID_CREDENTIALS,
                "Invalid Telegram webhook secret.",
            )
            return
        }
        if (request.contentLengthLong > MAX_PAYLOAD_BYTES) {
            rejectOversized(response)
            return
        }
        val body = request.inputStream.use { it.readNBytes(MAX_PAYLOAD_BYTES + 1) }
        if (body.size > MAX_PAYLOAD_BYTES) {
            rejectOversized(response)
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun rejectOversized(response: HttpServletResponse) {
        apiErrorResponseWriter.write(
            response,
            HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
            ApiErrorCode.VALIDATION_ERROR,
            "Telegram webhook payload is too large.",
        )
    }

    private fun secretMatches(provided: String?, expected: String): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
        const val MAX_PAYLOAD_BYTES = 64 * 1_024

        private fun apiPath(basePath: String, path: String): String {
            val base = basePath.trim().trimEnd('/')
            val suffix = if (path.startsWith('/')) path else "/$path"
            return if (base.isBlank() || base == "/") suffix else "$base$suffix"
        }
    }
}

private class CachedBodyRequest(
    request: HttpServletRequest,
    private val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getContentLength(): Int = body.size
    override fun getContentLengthLong(): Long = body.size.toLong()
    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)
    override fun getReader(): BufferedReader {
        val charset = characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8
        return BufferedReader(InputStreamReader(inputStream, charset))
    }
}

private class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
    private val input = ByteArrayInputStream(body)

    override fun read(): Int = input.read()
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
    override fun isFinished(): Boolean = input.available() == 0
    override fun isReady(): Boolean = true
    override fun setReadListener(listener: ReadListener?) {
        if (listener == null) return
        if (isFinished) listener.onAllDataRead() else listener.onDataAvailable()
    }
}
