package com.example.usbilling.orchestrator.client

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.util.concurrent.TimeUnit

class RestTemplateMetricsInterceptor(
    private val meterRegistry: MeterRegistry,
    private val client: String,
) : ClientHttpRequestInterceptor {

    @Suppress("TooGenericExceptionCaught")
    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        val method = request.method?.toString() ?: "UNKNOWN"
        val start = System.nanoTime()

        try {
            val response = execution.execute(request, body)
            val status = response.statusCode.value()
            val outcome = httpOutcome(status)

            recordTimer(method, outcome, status.toString(), System.nanoTime() - start)
            return response
        } catch (ex: Exception) {
            val durationNanos = System.nanoTime() - start
            recordTimer(method, outcome = "EXCEPTION", status = "EXCEPTION", durationNanos = durationNanos)
            meterRegistry.counter(
                "uspayroll.http.client.errors",
                "client",
                client,
                "method",
                method,
                "exception",
                ex.javaClass.simpleName,
            ).increment()
            throw ex
        }
    }

    private fun recordTimer(method: String, outcome: String, status: String, durationNanos: Long) {
        Timer.builder("uspayroll.http.client.requests")
            .tag("client", client)
            .tag("method", method)
            .tag("outcome", outcome)
            .tag("status", status)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    private fun httpOutcome(status: Int): String = when (status / 100) {
        1 -> "INFORMATIONAL"
        2 -> "SUCCESS"
        3 -> "REDIRECTION"
        4 -> "CLIENT_ERROR"
        5 -> "SERVER_ERROR"
        else -> "UNKNOWN"
    }
}
