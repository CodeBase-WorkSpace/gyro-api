package com.gyro.api.common.request

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = RequestIds.resolve(request)
        request.setAttribute(RequestIds.ATTRIBUTE_NAME, requestId)
        response.setHeader(RequestIds.HEADER_NAME, requestId)
        RequestIds.put(requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            RequestIds.clear()
        }
    }
}
