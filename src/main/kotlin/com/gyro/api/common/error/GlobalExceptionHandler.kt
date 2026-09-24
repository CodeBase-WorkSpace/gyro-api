package com.gyro.api.common.error

import com.gyro.api.common.request.RequestIds
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.sql.SQLException
import java.time.LocalDate

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val response = ApiErrorResponse(
            status = status.value(),
            code = ApiErrorCode.VALIDATION_ERROR,
            message = "Request validation failed.",
            requestId = RequestIds.current(),
            fieldErrors = ex.bindingResult.fieldErrors.map { it.toApiFieldError() },
        )
        exceptionLogger.warn(
            "Handled method argument validation error. requestId=${response.requestId} " +
                "status=${response.status} code=${response.code} fields=${response.fieldErrorNames()} " +
                "fieldErrorCount=${response.fieldErrors.size} path=${request.requestPath()}",
        )

        return ResponseEntity.status(status).body(response)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val response = ApiErrorResponse(
            status = status.value(),
            code = ApiErrorCode.VALIDATION_ERROR,
            message = "Request validation failed.",
            requestId = RequestIds.current(),
            fieldErrors = ex.readableFieldPath()?.let { field ->
                listOf(
                    ApiErrorResponse.FieldError(
                        field = field,
                        errorMessage = "Invalid value.",
                        code = "INVALID",
                    )
                )
            }.orEmpty(),
        )
        exceptionLogger.warn(
            "Handled unreadable HTTP message. requestId=${response.requestId} " +
                "status=${response.status} code=${response.code} fields=${response.fieldErrorNames()} " +
                "path=${request.requestPath()} cause=${ex.mostSpecificCause::class.simpleName}",
            ex,
        )

        return ResponseEntity.status(status).body(response)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
    ): ResponseEntity<ApiErrorResponse> {
        val response = ApiErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            code = ApiErrorCode.VALIDATION_ERROR,
            message = "Request validation failed.",
            requestId = RequestIds.current(),
            fieldErrors = ex.constraintViolations.map { violation ->
                ApiErrorResponse.FieldError(
                    field = violation.propertyPath.toString(),
                    errorMessage = violation.message,
                    code = violation.constraintDescriptor.annotation.annotationClass.simpleName
                        ?.toStableErrorCode()
                        ?: "INVALID",
                )
            },
        )
        exceptionLogger.warn(
            "Handled constraint violation. requestId=${response.requestId} status=${response.status} " +
                "code=${response.code} fields=${response.fieldErrorNames()} " +
                "fieldErrorCount=${response.fieldErrors.size}",
        )

        return ResponseEntity.badRequest().body(response)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
    ): ResponseEntity<ApiErrorResponse> {
        if (ex.requiredType == LocalDate::class.java) {
            return handleDomainException(InvalidDiaryDateException())
        }

        val response = ApiErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            code = ApiErrorCode.VALIDATION_ERROR,
            message = "Request validation failed.",
            requestId = RequestIds.current(),
        )
        exceptionLogger.warn(
            "Handled method argument type mismatch. requestId=${response.requestId} " +
                "status=${response.status} code=${response.code} parameter=${ex.name} " +
                "requiredType=${ex.requiredType?.simpleName}",
            ex,
        )

        return ResponseEntity.badRequest().body(response)
    }

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(
        ex: DomainException,
    ): ResponseEntity<ApiErrorResponse> {
        val response = ApiErrorResponse(
            status = ex.status.value(),
            code = ex.code,
            reasonCode = if (ex is PromotionException) ex.reasonCode else null,
            message = ex.message,
            requestId = RequestIds.current(),
            metadata = ex.metadata,
            fieldErrors = if (ex is FieldValidationException) ex.fieldErrors else emptyList(),
        )
        if (ex.status.is5xxServerError) {
            exceptionLogger.error(
                "Handled domain exception. requestId=${response.requestId} status=${response.status} code=${response.code}",
                ex,
            )
        } else {
            exceptionLogger.warn(
                "Handled domain exception. requestId=${response.requestId} status=${response.status} " +
                    "code=${response.code} exception=${ex::class.simpleName}",
            )
        }

        return ResponseEntity.status(ex.status).body(response)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
    ): ResponseEntity<ApiErrorResponse> {
        val status = ex.statusCode
        val response = ApiErrorResponse(
            status = status.value(),
            code = ApiErrorCode.INTERNAL_ERROR,
            message = ex.reason ?: status.defaultMessage(),
            requestId = RequestIds.current(),
        )
        exceptionLogger.warn(
            "Handled response status exception. requestId=${response.requestId} status=${response.status} " +
                "reason=${ex.reason}",
            ex,
        )

        return ResponseEntity.status(status).body(response)
    }

    @ExceptionHandler(AuthorizationDeniedException::class)
    fun handleAuthorizationDeniedException(
        ex: AuthorizationDeniedException,
    ): ResponseEntity<ApiErrorResponse> {
        val response = ApiErrorResponse(
            status = HttpStatus.FORBIDDEN.value(),
            code = ApiErrorCode.FORBIDDEN_RESOURCE,
            message = "You do not have access to this resource.",
            requestId = RequestIds.current(),
        )
        exceptionLogger.warn(
            "Handled authorization denied exception. requestId=${response.requestId} status=${response.status} code=${response.code}",
            ex,
        )

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        ex: Exception,
    ): ResponseEntity<ApiErrorResponse> {
        // The V39 deletion write barrier raises SQLSTATE 23U01; the wrapping exception type varies
        // by write path (JdbcTemplate translation, JPA flush, transaction commit), so the marker is
        // detected on the cause chain here rather than per wrapper type.
        if (ex.hasSqlState(DELETION_BARRIER_SQL_STATE)) {
            val barrierResponse = ApiErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = ApiErrorCode.ACCOUNT_DELETION_IN_PROGRESS,
                message = "This account is being deleted and can no longer accept changes.",
                requestId = RequestIds.current(),
            )
            exceptionLogger.warn(
                "Rejected write against account under deletion. requestId=${barrierResponse.requestId} " +
                    "status=${barrierResponse.status} code=${barrierResponse.code}",
            )
            return ResponseEntity.status(HttpStatus.CONFLICT).body(barrierResponse)
        }

        val response = ApiErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            code = ApiErrorCode.INTERNAL_ERROR,
            message = "An unexpected error occurred.",
            requestId = RequestIds.current(),
        )
        exceptionLogger.error(
            "Handled unexpected exception. requestId=${response.requestId} status=${response.status} code=${response.code}",
            ex,
        )

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }

    private fun Throwable.hasSqlState(sqlState: String): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLException && cause.sqlState == sqlState) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun FieldError.toApiFieldError(): ApiErrorResponse.FieldError {
        return ApiErrorResponse.FieldError(
            field = field,
            errorMessage = defaultMessage ?: "Invalid value.",
            code = code?.toStableErrorCode() ?: "INVALID",
        )
    }

    private fun HttpStatusCode.defaultMessage(): String {
        return if (this is HttpStatus) {
            reasonPhrase
        } else {
            "Request failed."
        }
    }

    private fun String.toStableErrorCode(): String {
        return replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .uppercase()
    }

    private fun HttpMessageNotReadableException.readableFieldPath(): String? {
        val message = mostSpecificCause.message ?: return null
        val fields = Regex("""\["([^"]+)"]""")
            .findAll(message)
            .map { it.groupValues[1] }
            .toList()

        return fields.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun ApiErrorResponse.fieldErrorNames(): String {
        return fieldErrors.joinToString(prefix = "[", postfix = "]") { it.field }
    }

    private fun WebRequest.requestPath(): String {
        return getDescription(false).removePrefix("uri=")
    }

    private companion object {
        private val exceptionLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        /** Raised by gyro_reject_write_during_account_deletion (V39) for writes against deleted accounts. */
        private const val DELETION_BARRIER_SQL_STATE = "23U01"
    }
}
