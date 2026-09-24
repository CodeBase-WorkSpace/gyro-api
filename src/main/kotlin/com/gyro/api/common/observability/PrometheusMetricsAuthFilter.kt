package com.gyro.api.common.observability

import com.gyro.api.common.error.ApiErrorCode
import com.gyro.api.common.error.ApiErrorResponseWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
class PrometheusMetricsAuthFilter(
    private val properties: ObservabilityProperties,
    private val apiErrorResponseWriter: ApiErrorResponseWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI != PROMETHEUS_ENDPOINT

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedToken = request.getHeader(TOKEN_HEADER)
        if (!suppliedToken.matches(properties.metricsToken)) {
            apiErrorResponseWriter.write(
                response = response,
                status = HttpServletResponse.SC_UNAUTHORIZED,
                code = ApiErrorCode.INVALID_CREDENTIALS,
                message = "Operations authentication is required.",
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun String?.matches(expectedToken: String): Boolean {
        if (this == null) return false

        return MessageDigest.isEqual(
            toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        private const val PROMETHEUS_ENDPOINT = "/actuator/prometheus"
        const val TOKEN_HEADER = "X-Observability-Token"
    }
}
