package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.web.client.DownstreamHttpClientProperties
import com.example.uspayroll.web.client.HttpClientGuardrails
import com.example.uspayroll.web.client.RestTemplateRetryClassifier
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject

interface PaymentsQueryClient {
    data class PaycheckPaymentView(
        val employerId: String,
        val paymentId: String,
        val paycheckId: String,
        val payRunId: String,
        val employeeId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
        val status: PaycheckPaymentLifecycleStatus,
        val attempts: Int,
    )

    fun listPaymentsForPayRun(employerId: String, payRunId: String): List<PaycheckPaymentView>
}

@ConfigurationProperties(prefix = "downstreams.payments")
class PaymentsQueryClientProperties : DownstreamHttpClientProperties() {
    init {
        baseUrl = "http://localhost:8086"
    }
}

@Configuration
@EnableConfigurationProperties(PaymentsQueryClientProperties::class)
class PaymentsQueryClientConfig {
    @Bean
    fun paymentsQueryClient(props: PaymentsQueryClientProperties, @Qualifier("paymentsRestTemplate") paymentsRestTemplate: RestTemplate, meterRegistry: MeterRegistry): PaymentsQueryClient = HttpPaymentsQueryClient(props, paymentsRestTemplate, meterRegistry)
}

class HttpPaymentsQueryClient(
    private val props: PaymentsQueryClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
) : PaymentsQueryClient {

    private val logger = LoggerFactory.getLogger(HttpPaymentsQueryClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    data class PaymentViewDto(
        val employerId: String,
        val paymentId: String,
        val paycheckId: String,
        val payRunId: String,
        val employeeId: String,
        val payPeriodId: String,
        val currency: String,
        val netCents: Long,
        val status: String,
        val attempts: Int,
    )

    override fun listPaymentsForPayRun(employerId: String, payRunId: String): List<PaymentsQueryClient.PaycheckPaymentView> {
        val url = "${props.baseUrl}/employers/$employerId/payruns/$payRunId/payments"

        val rows = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "payments",
                        "operation",
                        "listPaymentsForPayRun",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=payments op=listPaymentsForPayRun attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.getForObject<Array<PaymentViewDto>>(url)
        }
            ?: return emptyList()

        return rows.map {
            PaymentsQueryClient.PaycheckPaymentView(
                employerId = it.employerId,
                paymentId = it.paymentId,
                paycheckId = it.paycheckId,
                payRunId = it.payRunId,
                employeeId = it.employeeId,
                payPeriodId = it.payPeriodId,
                currency = it.currency,
                netCents = it.netCents,
                status = PaycheckPaymentLifecycleStatus.valueOf(it.status),
                attempts = it.attempts,
            )
        }
    }
}
