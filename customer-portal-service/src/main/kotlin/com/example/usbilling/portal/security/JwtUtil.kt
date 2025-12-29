package com.example.usbilling.portal.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT token validation and extraction utility.
 */
@Component
class JwtUtil(
    @Value("\${customer-portal.jwt.secret}")
    private val secret: String,

    @Value("\${customer-portal.jwt.expiration-hours:24}")
    private val expirationHours: Long,
) {

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Validate a JWT token and extract claims.
     *
     * @return Claims if valid, null if invalid or expired
     */
    fun validateAndExtractClaims(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    } catch (e: Exception) {
        // Token invalid, expired, or malformed
        null
    }

    /**
     * Extract CustomerPrincipal from validated JWT claims.
     */
    fun extractPrincipal(claims: Claims): CustomerPrincipal? {
        val customerId = claims.subject ?: return null
        val utilityId = claims["utilityId"] as? String ?: return null
        val accountIds = (claims["accountIds"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val email = claims["email"] as? String
        val fullName = claims["fullName"] as? String

        return CustomerPrincipal(
            customerId = customerId,
            utilityId = utilityId,
            accountIds = accountIds,
            email = email,
            fullName = fullName,
        )
    }

    /**
     * Generate a JWT token for a customer (for testing/dev purposes).
     */
    fun generateToken(principal: CustomerPrincipal): String {
        val now = Date()
        val expiration = Date(now.time + (expirationHours * 60 * 60 * 1000))

        return Jwts.builder()
            .subject(principal.customerId)
            .claim("utilityId", principal.utilityId)
            .claim("accountIds", principal.accountIds)
            .claim("email", principal.email)
            .claim("fullName", principal.fullName)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }
}
