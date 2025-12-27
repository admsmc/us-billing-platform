package com.example.usbilling.web

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Shared helpers for HTTP idempotency handling.
 *
 * The canonical idempotency key is carried in the [WebHeaders.IDEMPOTENCY_KEY]
 * header. Some endpoints also accept a body-level `idempotencyKey` field.
 *
 * These helpers encapsulate the normalization and reconciliation rules so that
 * all services treat header vs body keys consistently and surface the same
 * 400 error on mismatches.
 */
object Idempotency {

    /**
     * Normalize a potentially blank idempotency key by trimming and treating
     * empty values as null.
     */
    fun normalize(raw: String?): String? = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Resolve an idempotency key from an optional header and body field.
     *
     * Rules:
     * - If both are null/blank -> returns null (no idempotency).
     * - If only one is non-blank -> returns that value.
     * - If both are non-blank and equal -> returns the shared value.
     * - If both are non-blank and differ -> throws ResponseStatusException(400).
     */
    fun resolveIdempotencyKey(headerKey: String?, bodyKey: String?): String? {
        val h = normalize(headerKey)
        val b = normalize(bodyKey)

        return when {
            h == null && b == null -> null
            h == null -> b
            b == null -> h
            h == b -> h
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key header does not match request body idempotencyKey",
            )
        }
    }
}
