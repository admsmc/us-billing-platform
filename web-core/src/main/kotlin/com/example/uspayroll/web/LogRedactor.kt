package com.example.uspayroll.web

/**
 * Utility for redacting obvious secret/token material from strings before logging.
 *
 * This is a guardrail for future debug/error logging that might accidentally include headers
 * or bearer tokens.
 */
object LogRedactor {

    private val authorizationBearerRegex = Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([^\\s]+)")
    private val bearerTokenRegex = Regex("(?i)(bearer\\s+)([^\\s]+)")
    private val internalTokenHeaderRegex = Regex("(?i)(x-internal-token\\s*[:=]\\s*)([^\\s]+)")

    // Very common JWT shape: three base64url-ish segments separated by dots.
    private val jwtLikeRegex = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    fun redact(input: String): String {
        var out = input

        out = out.replace(authorizationBearerRegex) { m -> m.groupValues[1] + "<redacted>" }
        out = out.replace(internalTokenHeaderRegex) { m -> m.groupValues[1] + "<redacted>" }
        out = out.replace(bearerTokenRegex) { m -> m.groupValues[1] + "<redacted>" }
        out = out.replace(jwtLikeRegex, "<redacted-jwt>")

        return out
    }
}
