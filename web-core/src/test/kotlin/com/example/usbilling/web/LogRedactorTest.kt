package com.example.usbilling.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogRedactorTest {

    @Test
    fun `redacts Authorization Bearer token`() {
        val input = "Authorization: Bearer abc.def.ghi"
        val out = LogRedactor.redact(input)
        assertTrue(out.contains("Authorization: Bearer <redacted>"))
        assertFalse(out.contains("abc.def.ghi"))
    }

    @Test
    fun `redacts bare bearer token occurrences`() {
        val input = "sending bearer abc123"
        val out = LogRedactor.redact(input)
        assertTrue(out.contains("bearer <redacted>"))
        assertFalse(out.contains("abc123"))
    }

    @Test
    fun `redacts jwt-looking strings even without Authorization keyword`() {
        val input = "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzdWIifQ.sgn"
        val out = LogRedactor.redact(input)
        assertTrue(out.contains("<redacted-jwt>"))
    }
}
