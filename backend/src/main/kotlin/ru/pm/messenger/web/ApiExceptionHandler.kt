package ru.pm.messenger.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import ru.pm.messenger.config.CorrelationIdFilter
import ru.pm.messenger.identity.ApiException

data class ErrorBody(
    val code: String,
    val message: String,
    val correlationId: String?,
)

data class ApiError(
    val error: ErrorBody,
)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(exception: ApiException): ResponseEntity<ApiError> {
        val body = ApiError(
            ErrorBody(
                code = exception.code,
                message = exception.message ?: "Request failed.",
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
            ),
        )
        return ResponseEntity.status(exception.status).body(body)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(): ResponseEntity<ApiError> {
        val body = ApiError(
            ErrorBody(
                code = "payload_too_large",
                message = "File is too large.",
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
            ),
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun unhandled(exception: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        val correlationId = MDC.get(CorrelationIdFilter.MDC_KEY)
        log.error(
            "Unhandled error class={} path={} correlationId={}",
            exception.javaClass.simpleName,
            request.requestURI,
            correlationId,
        )
        val body = ApiError(
            ErrorBody(
                code = "internal_error",
                message = "The request could not be completed.",
                correlationId = correlationId,
            ),
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
