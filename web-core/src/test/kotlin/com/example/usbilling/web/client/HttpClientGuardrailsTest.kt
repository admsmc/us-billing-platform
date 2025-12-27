package com.example.usbilling.web.client

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpClientGuardrailsTest {

    @Test
    fun `backoff caps at max delay`() {
        val backoff = BackoffPolicy(
            initialDelay = Duration.ofMillis(100),
            maxDelay = Duration.ofMillis(250),
            multiplier = 2.0,
        )

        assertEquals(Duration.ofMillis(100), backoff.delayForAttempt(1))
        assertEquals(Duration.ofMillis(200), backoff.delayForAttempt(2))
        assertEquals(Duration.ofMillis(250), backoff.delayForAttempt(3))
        assertEquals(Duration.ofMillis(250), backoff.delayForAttempt(4))
    }

    @Test
    fun `retries up to max attempts and then rethrows`() {
        var attempts = 0
        val guardrails = HttpClientGuardrails(
            retryPolicy = RetryPolicy(
                maxAttempts = 3,
                backoff = BackoffPolicy.none(),
            ),
            circuitBreakerPolicy = null,
            sleepMillis = {},
        )

        assertFailsWith<IllegalStateException> {
            guardrails.execute(isRetryable = { true }) {
                attempts += 1
                error("boom")
            }
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `circuit opens after threshold failures and fails fast until open duration elapses`() {
        var now = 0L
        val policy = CircuitBreakerPolicy(
            failureThreshold = 2,
            openDuration = Duration.ofMillis(100),
            halfOpenMaxCalls = 1,
        )
        val breaker = CircuitBreaker(policy) { now }

        assertFailsWith<IllegalStateException> {
            breaker.call({ true }) { error("fail-1") }
        }
        assertFailsWith<IllegalStateException> {
            breaker.call({ true }) { error("fail-2") }
        }

        // Now open.
        assertFailsWith<CircuitBreakerOpenException> {
            breaker.call({ true }) { "should not run" }
        }

        // Time passes -> half-open probe allowed.
        now = 150L
        assertEquals("ok", breaker.call({ true }) { "ok" })

        // Success closes.
        assertEquals("ok2", breaker.call({ true }) { "ok2" })
    }
}
