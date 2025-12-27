package com.example.usbilling.web

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IdempotencyTest {

    @Test
    fun `normalize trims and treats blank as null`() {
        assertNull(Idempotency.normalize(null))
        assertNull(Idempotency.normalize(""))
        assertNull(Idempotency.normalize("   "))
        assertEquals("abc", Idempotency.normalize("abc"))
        assertEquals("abc", Idempotency.normalize("  abc  "))
    }

    @Test
    fun `resolveIdempotencyKey returns null when both sources are null or blank`() {
        assertNull(Idempotency.resolveIdempotencyKey(null, null))
        assertNull(Idempotency.resolveIdempotencyKey("  ", null))
        assertNull(Idempotency.resolveIdempotencyKey(null, "  "))
    }

    @Test
    fun `resolveIdempotencyKey prefers non-blank header or body when only one is present`() {
        assertEquals("h", Idempotency.resolveIdempotencyKey("h", null))
        assertEquals("h", Idempotency.resolveIdempotencyKey("  h  ", null))
        assertEquals("b", Idempotency.resolveIdempotencyKey(null, "b"))
        assertEquals("b", Idempotency.resolveIdempotencyKey(null, "  b  "))
    }

    @Test
    fun `resolveIdempotencyKey accepts equal non-blank header and body`() {
        assertEquals("k", Idempotency.resolveIdempotencyKey("k", "k"))
        assertEquals("k", Idempotency.resolveIdempotencyKey("  k  ", "k"))
    }

    @Test
    fun `resolveIdempotencyKey rejects mismatched non-blank header and body`() {
        val ex = assertFailsWith<ResponseStatusException> {
            Idempotency.resolveIdempotencyKey("header-key", "body-key")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals(
            "Idempotency-Key header does not match request body idempotencyKey",
            ex.reason,
        )
    }
}
