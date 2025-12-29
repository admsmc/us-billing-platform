package com.example.usbilling.portal.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that extracts and validates JWT tokens from the Authorization header.
 *
 * Expects: Authorization: Bearer <token>
 */
@Component
class CustomerAuthenticationFilter(
    private val jwtUtil: JwtUtil,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)

            // Validate and extract claims
            val claims = jwtUtil.validateAndExtractClaims(token)
            if (claims != null) {
                val principal = jwtUtil.extractPrincipal(claims)

                if (principal != null && SecurityContextHolder.getContext().authentication == null) {
                    // Create authentication token
                    val authentication = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        emptyList(), // No roles/authorities for now
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)

                    // Set authentication in security context
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }
        }

        filterChain.doFilter(request, response)
    }
}
