package com.example.uspayroll.orchestrator.web

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestControllerAdvice

import com.example.uspayroll.web.ProblemDetails
import com.example.uspayroll.web.WebErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler

@RestControllerAdvice
@Component("orchestratorProblemDetailsAdvice")
class ProblemDetailsAdvice : com.example.uspayroll.web.ProblemDetailsExceptionHandler() {

    private val logger = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        // Keep client response clean and stable; log details server-side.
        logger.warn(
            "Data integrity violation for {} {}: {}",
            request.method,
            request.requestURI,
            ex.mostSpecificCause?.message ?: ex.message,
        )

        val problem = ProblemDetails.build(
            status = HttpStatus.CONFLICT,
            title = "Conflict",
            detail = "Conflict",
            errorCode = WebErrorCode.CONFLICT,
            instance = request.instanceUri(),
        )

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_PROBLEM_JSON }
        val correlationId = problem.properties?.get("correlationId") as? String
        if (!correlationId.isNullOrBlank()) {
            headers.add(com.example.uspayroll.web.WebHeaders.CORRELATION_ID, correlationId)
        }

        return ResponseEntity(problem, headers, HttpStatus.CONFLICT)
    }

    private fun HttpServletRequest.instanceUri(): java.net.URI? {
        val raw = requestURI ?: return null
        return try {
            java.net.URI.create(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
