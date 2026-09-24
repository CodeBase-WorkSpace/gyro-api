package com.gyro.api.common.request

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import java.util.UUID

object RequestIds {
    const val HEADER_NAME = "X-Request-Id"
    const val ATTRIBUTE_NAME = "com.gyro.api.requestId"
    private const val MDC_KEY = "requestId"
    private val allowedRequestId = Regex("^[A-Za-z0-9._:-]{8,128}$")

    fun resolve(request: HttpServletRequest): String {
        val provided = request.getHeader(HEADER_NAME)?.trim()
        return if (provided != null && allowedRequestId.matches(provided)) {
            provided
        } else {
            UUID.randomUUID().toString()
        }
    }

    fun put(requestId: String) {
        MDC.put(MDC_KEY, requestId)
    }

    fun current(): String? {
        return MDC.get(MDC_KEY)
    }

    fun clear() {
        MDC.remove(MDC_KEY)
    }
}
