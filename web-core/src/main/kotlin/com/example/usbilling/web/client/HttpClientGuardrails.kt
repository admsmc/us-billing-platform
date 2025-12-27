package com.example.usbilling.web.client

import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Shared guardrails for HTTP clients (blocking) that call other internal services.
 *
 * Goals:
 * - bounded retries with backoff
 * - simple circuit breaker to fail fast during outages
 *
 * This is intentionally framework-agnostic (no Spring Boot), testable, and safe to use from
 * any module that already depends on Spring Web.
 */
class HttpClientGuardrails(
    private val retryPolicy: RetryPolicy = RetryPolicy.none(),
    circuitBreakerPolicy: CircuitBreakerPolicy? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {

    private val circuitBreaker: CircuitBreaker? = circuitBreakerPolicy?.let { CircuitBreaker(it, nowMillis) }

    data class AttemptFailure(
        val attempt: Int,
        val maxAttempts: Int,
        val nextDelay: Duration,
        val throwable: Throwable,
    )

    @Suppress("TooGenericExceptionCaught")
    fun <T> execute(isRetryable: (Throwable) -> Boolean, shouldRecordFailureInCircuitBreaker: (Throwable) -> Boolean = isRetryable, onRetry: (AttemptFailure) -> Unit = {}, block: () -> T): T {
        val maxAttempts = retryPolicy.maxAttempts
        var attempt = 1

        while (true) {
            try {
                val cb = circuitBreaker
                return if (cb == null) {
                    block()
                } else {
                    cb.call(shouldRecordFailureInCircuitBreaker, block)
                }
            } catch (open: CircuitBreakerOpenException) {
                // Circuit is open: fail fast (no retry).
                throw open
            } catch (ex: Exception) {
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw ex
                }

                val nextDelay = retryPolicy.backoff.delayForAttempt(attempt)
                onRetry(
                    AttemptFailure(
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        nextDelay = nextDelay,
                        throwable = ex,
                    ),
                )

                val ms = nextDelay.toMillis()
                if (ms > 0) {
                    sleepMillis(ms)
                }

                attempt += 1
            }
        }
    }

    companion object {
        fun with(
            maxRetries: Int,
            initialBackoff: Duration = Duration.ofMillis(100),
            maxBackoff: Duration = Duration.ofSeconds(1),
            backoffMultiplier: Double = 2.0,
            circuitBreakerPolicy: CircuitBreakerPolicy? = null,
        ): HttpClientGuardrails {
            circuitBreakerPolicy?.validate()

            val retryPolicy = RetryPolicy(
                maxAttempts = (maxRetries + 1).coerceAtLeast(1),
                backoff = BackoffPolicy(
                    initialDelay = initialBackoff,
                    maxDelay = maxBackoff,
                    multiplier = backoffMultiplier,
                ),
            )
            return HttpClientGuardrails(retryPolicy = retryPolicy, circuitBreakerPolicy = circuitBreakerPolicy)
        }
    }
}

data class RetryPolicy(
    val maxAttempts: Int,
    val backoff: BackoffPolicy,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    companion object {
        fun none(): RetryPolicy = RetryPolicy(maxAttempts = 1, backoff = BackoffPolicy.none())
    }
}

data class BackoffPolicy(
    val initialDelay: Duration,
    val maxDelay: Duration,
    val multiplier: Double = 2.0,
) {
    init {
        require(!initialDelay.isNegative) { "initialDelay must be >= 0" }
        require(!maxDelay.isNegative) { "maxDelay must be >= 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    fun delayForAttempt(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be >= 1" }
        if (initialDelay.isZero || maxDelay.isZero) return Duration.ZERO

        val initialMs = initialDelay.toMillis().coerceAtLeast(0L)
        val maxMs = maxDelay.toMillis().coerceAtLeast(0L)
        val scaled = (initialMs.toDouble() * multiplier.pow((attempt - 1).toDouble())).toLong()
        val capped = minOf(scaled, maxMs)
        return Duration.ofMillis(capped)
    }

    companion object {
        fun none(): BackoffPolicy = BackoffPolicy(Duration.ZERO, Duration.ZERO, 1.0)
    }
}

data class CircuitBreakerPolicy(
    var failureThreshold: Int = 5,
    var openDuration: Duration = Duration.ofSeconds(30),
    var halfOpenMaxCalls: Int = 1,
) {
    fun validate() {
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
        require(!openDuration.isNegative && !openDuration.isZero) { "openDuration must be > 0" }
        require(halfOpenMaxCalls >= 1) { "halfOpenMaxCalls must be >= 1" }
    }
}

class CircuitBreakerOpenException(message: String) : RuntimeException(message)

/**
 * Minimal, dependency-free circuit breaker intended for blocking internal HTTP clients.
 *
 * State model (simplified):
 * - Closed: counts consecutive failures
 * - Open: fail fast until openUntilMillis
 * - Half-open: allow a limited number of probe calls
 */
class CircuitBreaker(
    private val policy: CircuitBreakerPolicy,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val consecutiveFailures = AtomicInteger(0)

    /**
     * Sentinel state encoded in a single atomic:
     * - 0  : closed
     * - -1 : half-open
     * - >0 : open until epochMillis
     */
    private val state = AtomicLong(0)

    private val halfOpenRemainingCalls = AtomicInteger(0)

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun <T> call(shouldRecordFailure: (Throwable) -> Boolean, block: () -> T): T {
        val now = nowMillis()
        val current = state.get()

        when {
            current > 0 && current > now -> {
                throw CircuitBreakerOpenException("circuit_open until=$current")
            }

            current > 0 && current <= now -> {
                // Transition open -> half-open.
                if (state.compareAndSet(current, -1L)) {
                    halfOpenRemainingCalls.set(policy.halfOpenMaxCalls)
                }
            }

            current == -1L -> {
                val remaining = halfOpenRemainingCalls.getAndDecrement()
                if (remaining <= 0) {
                    // No probe calls left; fail fast until a success closes.
                    halfOpenRemainingCalls.incrementAndGet()
                    throw CircuitBreakerOpenException("circuit_half_open_exhausted")
                }
            }
        }

        try {
            val result = block()
            // Any success closes the circuit.
            consecutiveFailures.set(0)
            state.set(0)
            halfOpenRemainingCalls.set(0)
            return result
        } catch (t: Throwable) {
            if (shouldRecordFailure(t)) {
                if (state.get() == -1L) {
                    // Probe failed -> immediately open.
                    val until = nowMillis() + policy.openDuration.toMillis()
                    state.set(until)
                    halfOpenRemainingCalls.set(0)
                } else {
                    val failures = consecutiveFailures.incrementAndGet()
                    if (failures >= policy.failureThreshold) {
                        val until = nowMillis() + policy.openDuration.toMillis()
                        state.set(until)
                        halfOpenRemainingCalls.set(0)
                    }
                }
            }
            throw t
        }
    }
}

/**
 * Conservative retry classifier for Spring's blocking RestTemplate.
 *
 * - retry: connect/read timeouts, 5xx, and 429
 * - no retry: other 4xx by default
 */
object RestTemplateRetryClassifier {
    fun isRetryable(t: Throwable): Boolean = when (t) {
        is ResourceAccessException -> true
        is HttpServerErrorException -> true
        is HttpClientErrorException.TooManyRequests -> true
        else -> false
    }
}
