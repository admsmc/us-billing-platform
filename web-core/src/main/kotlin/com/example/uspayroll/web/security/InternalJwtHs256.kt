package com.example.uspayroll.web.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HS256 JWT implementation intended for *internal service-to-service* authentication.
 *
 * Design constraints:
 * - No additional dependencies (uses JDK crypto only).
 * - Short-lived tokens (caller chooses TTL).
 * - Verifies signature and expiry; optionally verifies issuer/audience.
 *
 * This is not intended as a general-purpose JWT library.
 */
object InternalJwtHs256 {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class VerifiedClaims(
        val issuer: String,
        val subject: String,
        val audience: String,
        val issuedAtEpochSeconds: Long,
        val expiresAtEpochSeconds: Long,
        val jwtId: String,
    )

    fun issue(secret: String, issuer: String, subject: String, audience: String, ttlSeconds: Long = 60, now: Instant = Instant.now()): String {
        require(secret.isNotBlank()) { "secret must be non-blank" }
        require(issuer.isNotBlank()) { "issuer must be non-blank" }
        require(subject.isNotBlank()) { "subject must be non-blank" }
        require(audience.isNotBlank()) { "audience must be non-blank" }
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }

        val iat = now.epochSecond
        val exp = now.plusSeconds(ttlSeconds).epochSecond
        val jti = UUID.randomUUID().toString()

        val headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
        val payloadJson = buildString {
            append('{')
            append("\"iss\":\"").append(jsonEscape(issuer)).append("\",")
            append("\"sub\":\"").append(jsonEscape(subject)).append("\",")
            append("\"aud\":\"").append(jsonEscape(audience)).append("\",")
            append("\"iat\":").append(iat).append(',')
            append("\"exp\":").append(exp).append(',')
            append("\"jti\":\"").append(jsonEscape(jti)).append('\"')
            append('}')
        }

        val header64 = base64Url(headerJson.toByteArray(StandardCharsets.UTF_8))
        val payload64 = base64Url(payloadJson.toByteArray(StandardCharsets.UTF_8))

        val signingInput = "$header64.$payload64"
        val signature = hmacSha256(secret, signingInput)
        val signature64 = base64Url(signature)

        return "$signingInput.$signature64"
    }

    fun verify(token: String, secret: String, expectedIssuer: String? = null, expectedAudience: String? = null, now: Instant = Instant.now(), leewaySeconds: Long = 5): VerifiedClaims {
        require(token.isNotBlank()) { "token must be non-blank" }
        require(secret.isNotBlank()) { "secret must be non-blank" }
        require(leewaySeconds >= 0) { "leewaySeconds must be >= 0" }

        val parts = token.split('.')
        require(parts.size == 3) { "invalid JWT format" }

        val headerJson = decodeBase64Url(parts[0])
        val headerAlg = extractStringClaim(headerJson, "alg")
        require(headerAlg == "HS256") { "unexpected alg: $headerAlg" }

        val signingInput = parts[0] + "." + parts[1]
        val expectedSig = hmacSha256(secret, signingInput)
        val actualSig = decodeBase64UrlBytes(parts[2])

        require(MessageDigest.isEqual(expectedSig, actualSig)) { "invalid JWT signature" }

        val payloadJson = decodeBase64Url(parts[1])

        val iss = requireNotNull(extractStringClaim(payloadJson, "iss")) { "missing iss" }
        val sub = requireNotNull(extractStringClaim(payloadJson, "sub")) { "missing sub" }
        val aud = requireNotNull(extractStringClaim(payloadJson, "aud")) { "missing aud" }
        val iat = requireNotNull(extractLongClaim(payloadJson, "iat")) { "missing iat" }
        val exp = requireNotNull(extractLongClaim(payloadJson, "exp")) { "missing exp" }
        val jti = requireNotNull(extractStringClaim(payloadJson, "jti")) { "missing jti" }

        val nowEpoch = now.epochSecond
        require(nowEpoch <= exp + leewaySeconds) { "token expired" }

        if (!expectedIssuer.isNullOrBlank()) {
            require(iss == expectedIssuer) { "unexpected iss" }
        }

        if (!expectedAudience.isNullOrBlank()) {
            require(aud == expectedAudience) { "unexpected aud" }
        }

        return VerifiedClaims(
            issuer = iss,
            subject = sub,
            audience = aud,
            issuedAtEpochSeconds = iat,
            expiresAtEpochSeconds = exp,
            jwtId = jti,
        )
    }

    private fun hmacSha256(secret: String, data: String): ByteArray {
        val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun base64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)

    private fun decodeBase64Url(value: String): String = String(decodeBase64UrlBytes(value), StandardCharsets.UTF_8)

    private fun decodeBase64UrlBytes(value: String): ByteArray = decoder.decode(value)

    private fun jsonEscape(value: String): String {
        // Minimal JSON string escape for our controlled internal claims.
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun jsonUnescape(value: String): String {
        // Minimal unescape for values we previously escaped.
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractStringClaim(json: String, name: String): String? {
        // Matches: "name" : "value"
        val regex = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return jsonUnescape(raw)
    }

    private fun extractLongClaim(json: String, name: String): Long? {
        val regex = Regex("\"$name\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLong()
    }
}
