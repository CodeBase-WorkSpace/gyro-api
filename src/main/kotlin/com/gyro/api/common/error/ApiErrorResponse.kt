package com.gyro.api.common.error

data class ApiErrorResponse(
    val status: Int,
    val code: ApiErrorCode,
    val reasonCode: String? = null,
    val message: String,
    val requestId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val fieldErrors: List<FieldError> = emptyList(),
) {
    data class FieldError(
        val field: String,
        val errorMessage: String,
        val code: String = "INVALID",
    )
}
