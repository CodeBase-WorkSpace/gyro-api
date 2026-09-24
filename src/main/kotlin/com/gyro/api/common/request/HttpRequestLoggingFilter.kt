package com.gyro.api.common.request

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.concurrent.TimeUnit

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class HttpRequestLoggingFilter(
    private val meterRegistry: MeterRegistry,
    private val clientIps: TrustedClientIpResolver,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
            path == "/actuator/prometheus" ||
            path.startsWith("/api-docs/") ||
            path.startsWith("/swagger-ui/") ||
            path == "/swagger-ui.html" ||
            path.startsWith("/v3/api-docs/") ||
            path == "/favicon.ico" ||
            path.startsWith("/assets/") ||
            path.startsWith("/static/") ||
            path.startsWith("/webjars/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        val requestIdAlreadyPresent = RequestIds.current() != null

        if (!requestIdAlreadyPresent) {
            val requestId = request.getAttribute(RequestIds.ATTRIBUTE_NAME) as? String ?: RequestIds.resolve(request)
            request.setAttribute(RequestIds.ATTRIBUTE_NAME, requestId)
            response.setHeader(RequestIds.HEADER_NAME, requestId)
            RequestIds.put(requestId)
        }

        try {
            filterChain.doFilter(request, response)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            logCompletedRequest(request, response, startedAt, failure)
            if (!requestIdAlreadyPresent) {
                RequestIds.clear()
            }
        }
    }

    private fun logCompletedRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        startedAt: Long,
        failure: Throwable?,
    ) {
        val durationNanos = System.nanoTime() - startedAt
        val durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos)
        val status = if (failure != null && response.status < 400) {
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        } else {
            response.status
        }
        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "unmatched"

        Timer.builder("gyro.api.requests")
            .description("Completed API requests measured by normalized Spring MVC route.")
            .publishPercentileHistogram()
            .tags(
                "method", request.method,
                "route", route,
                "status", status.toString(),
                "outcome", status.outcome(),
            ).register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)

        requestLogger.atInfo()
            .addKeyValue("event", "http_request")
            .addKeyValue("method", request.method)
            .addKeyValue("path", request.requestURI)
            .addKeyValue("route", route)
            .addKeyValue("status", status)
            .addKeyValue("durationMs", durationMs)
            .addKeyValue("userId", request.getAttribute(USER_ID_ATTRIBUTE) ?: "anonymous")
            .addKeyValue("role", request.getAttribute(USER_ROLE_ATTRIBUTE) ?: "anonymous")
            .addKeyValue("clientIp", clientIps.resolve(request).safeMetadataValue())
            .addKeyValue("userAgent", request.getHeader("User-Agent")?.safeMetadataValue())
            .addKeyValue("contentLength", request.contentLengthValue())
            .addKeyValue("queryPresent", !request.queryString.isNullOrBlank())
            .addKeyValue("errorCode", request.getAttribute(ERROR_CODE_ATTRIBUTE))
            .log("HTTP request completed")
    }

    private fun HttpServletRequest.contentLengthValue(): Long? {
        return contentLengthLong.takeIf { it >= 0 }
    }

    private fun String.safeMetadataValue(): String {
        return take(MAX_METADATA_LENGTH)
    }

    private fun Int.outcome(): String = when {
        this >= 500 -> "SERVER_ERROR"
        this >= 400 -> "CLIENT_ERROR"
        this >= 300 -> "REDIRECTION"
        else -> "SUCCESS"
    }

    private companion object {
        private const val MAX_METADATA_LENGTH = 256
        private val requestLogger = LoggerFactory.getLogger(HttpRequestLoggingFilter::class.java)
    }
}
