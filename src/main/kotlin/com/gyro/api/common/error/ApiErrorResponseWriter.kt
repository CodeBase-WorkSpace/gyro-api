package com.gyro.api.common.error

import tools.jackson.databind.ObjectMapper
import com.gyro.api.common.request.RequestIds
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

@Component
class ApiErrorResponseWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        response: HttpServletResponse,
        status: Int,
        code: ApiErrorCode,
        message: String,
    ) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()

        objectMapper.writeValue(
            response.writer,
            ApiErrorResponse(
                status = status,
                code = code,
                message = message,
                requestId = RequestIds.current(),
            ),
        )
    }
}
