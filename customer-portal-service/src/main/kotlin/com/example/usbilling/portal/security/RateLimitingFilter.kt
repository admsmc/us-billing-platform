package com.example.usbilling.portal.security

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiting filter using Bucket4j token bucket algorithm.
 *
 * Rate limits are applied per remote IP address. In production, consider:
 * - Using a distributed cache (Redis) for multi-instance deployments
 * - Applying limits per authenticated customer ID instead of IP
 * - Configuring X-Forwarded-For header handling for proxy/load balancer deployments
 */
@Component
class RateLimitingFilter(
    @Value("\${customer-portal.rate-limit.enabled:true}")
    private val rateLimitEnabled: Boolean,

    @Value("\${customer-portal.rate-limit.requests-per-minute:100}")
    private val requestsPerMinute: Long,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response)
            return
        }

        val clientKey = extractClientKey(request)
        val bucket = buckets.computeIfAbsent(clientKey) { createBucket() }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response)
        } else {
            log.warn("Rate limit exceeded for client: {}", clientKey)
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Rate limit exceeded. Please try again later."}""")
        }
    }

    private fun extractClientKey(request: HttpServletRequest): String {
        // In production, check X-Forwarded-For header if behind a proxy
        return request.remoteAddr
    }

    private fun createBucket(): Bucket {
        val refill = Refill.intervally(requestsPerMinute, Duration.ofMinutes(1))
        val bandwidth = Bandwidth.classic(requestsPerMinute, refill)
        return Bucket.builder()
            .addLimit(bandwidth)
            .build()
    }
}
