package com.example.uspayroll.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import java.net.URI

/**
 * Base class for consistent ProblemDetail-based error handling.
 *
 * Usage:
 * - In each service module, create a small wrapper annotated with @RestControllerAdvice
 *   that extends this class.
 */
open class ProblemDetailsExceptionHandler {

    private val logger = LoggerFactory.getLogger(ProblemDetailsExceptionHandler::class.java)

    @ExceptionHandler(DomainProblemException::class)
    fun handleDomainProblem(ex: DomainProblemException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetails.build(
            status = ex.status,
            title = ex.title ?: ex.status.reasonPhrase,
            detail = ex.message,
            errorCode = ex.transportCode,
            instance = request.instanceUri(),
            domainErrorCode = ex.domainErrorCode,
        )
        return respond(ex.status, problem)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        status = HttpStatus.BAD_REQUEST,
        problem = ProblemDetails.badRequest(ex.message, request.instanceUri()),
    )

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNoSuchElement(ex: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        status = HttpStatus.NOT_FOUND,
        problem = ProblemDetails.notFound(ex.message, request.instanceUri()),
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetails.build(
            status = HttpStatus.BAD_REQUEST,
            title = "Bad Request",
            detail = "Validation failed",
            errorCode = WebErrorCode.VALIDATION_FAILED,
            instance = request.instanceUri(),
        )
        val violations = ex.bindingResult.fieldErrors.map { fe ->
            mapOf(
                "field" to fe.field,
                "message" to (fe.defaultMessage ?: "invalid"),
            )
        }
        problem.setProperty("violations", violations)
        return respond(HttpStatus.BAD_REQUEST, problem)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = ex.statusCode as? HttpStatus ?: HttpStatus.INTERNAL_SERVER_ERROR
        val title = ex.reason ?: status.reasonPhrase
        val code = status.toWebErrorCode()
        val problem = ProblemDetails.build(
            status = status,
            title = title,
            detail = ex.message,
            errorCode = code,
            instance = request.instanceUri(),
        )
        return respond(status, problem)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnhandled(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        // Log server-side for debugging. ProblemDetail remains minimal.
        logger.error("Unhandled exception for {} {}", request.method, request.requestURI, ex)

        val problem = ProblemDetails.build(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            title = "Internal Server Error",
            detail = "Unexpected error",
            errorCode = WebErrorCode.INTERNAL_ERROR,
            instance = request.instanceUri(),
        )
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, problem)
    }

    private fun respond(status: HttpStatus, problem: ProblemDetail): ResponseEntity<ProblemDetail> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON

        // Ensure correlation header is present for clients.
        val correlationId = problem.properties?.get("correlationId") as? String
        if (!correlationId.isNullOrBlank()) {
            headers.add(WebHeaders.CORRELATION_ID, correlationId)
        }

        return ResponseEntity(problem, headers, status)
    }

    private fun HttpServletRequest.instanceUri(): URI? {
        val raw = requestURI ?: return null
        return try {
            URI.create(raw)
        } catch (ex: IllegalArgumentException) {
            // Defensive: avoid breaking error handling if requestURI isn't a valid URI.
            logger.debug("Invalid requestURI for ProblemDetail.instance: {}", raw, ex)
            null
        }
    }

    private fun HttpStatus.toWebErrorCode(): WebErrorCode = when (this) {
        HttpStatus.BAD_REQUEST -> WebErrorCode.BAD_REQUEST
        HttpStatus.UNAUTHORIZED -> WebErrorCode.UNAUTHORIZED
        HttpStatus.FORBIDDEN -> WebErrorCode.FORBIDDEN
        HttpStatus.NOT_FOUND -> WebErrorCode.NOT_FOUND
        HttpStatus.CONFLICT -> WebErrorCode.CONFLICT
        HttpStatus.TOO_MANY_REQUESTS -> WebErrorCode.RATE_LIMITED
        HttpStatus.SERVICE_UNAVAILABLE -> WebErrorCode.SERVICE_UNAVAILABLE
        HttpStatus.INTERNAL_SERVER_ERROR -> WebErrorCode.INTERNAL_ERROR
        else -> WebErrorCode.HTTP_ERROR
    }
}
