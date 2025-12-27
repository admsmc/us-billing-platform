package com.example.usbilling.web

import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemDetailsExceptionHandlerTest {

    private class ExposedHandler : ProblemDetailsExceptionHandler() {
        fun map(status: HttpStatus): WebErrorCode = status.toWebErrorCode()
    }

    private val handler = ExposedHandler()

    @Test
    fun `toWebErrorCode maps common statuses to stable codes`() {
        assertEquals(WebErrorCode.BAD_REQUEST, handler.map(HttpStatus.BAD_REQUEST))
        assertEquals(WebErrorCode.UNAUTHORIZED, handler.map(HttpStatus.UNAUTHORIZED))
        assertEquals(WebErrorCode.FORBIDDEN, handler.map(HttpStatus.FORBIDDEN))
        assertEquals(WebErrorCode.NOT_FOUND, handler.map(HttpStatus.NOT_FOUND))
        assertEquals(WebErrorCode.CONFLICT, handler.map(HttpStatus.CONFLICT))
        assertEquals(WebErrorCode.RATE_LIMITED, handler.map(HttpStatus.TOO_MANY_REQUESTS))
        assertEquals(WebErrorCode.SERVICE_UNAVAILABLE, handler.map(HttpStatus.SERVICE_UNAVAILABLE))
        assertEquals(WebErrorCode.INTERNAL_ERROR, handler.map(HttpStatus.INTERNAL_SERVER_ERROR))
    }

    @Test
    fun `toWebErrorCode maps other statuses to generic HTTP_ERROR`() {
        assertEquals(WebErrorCode.HTTP_ERROR, handler.map(HttpStatus.CREATED))
        assertEquals(WebErrorCode.HTTP_ERROR, handler.map(HttpStatus.ACCEPTED))
    }
}
