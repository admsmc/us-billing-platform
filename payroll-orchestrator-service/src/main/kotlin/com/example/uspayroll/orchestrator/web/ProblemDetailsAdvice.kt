package com.example.uspayroll.orchestrator.web

import com.example.uspayroll.web.ProblemDetails
import com.example.uspayroll.web.WebErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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

        return respond(HttpStatus.CONFLICT, problem)
    }
}
