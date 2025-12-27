package com.example.usbilling.worker.client

import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.tax.http.TaxContextDto
import com.example.usbilling.tax.http.toDomain
import com.example.usbilling.web.client.DownstreamHttpClientProperties
import com.example.usbilling.web.client.HttpClientGuardrails
import com.example.usbilling.web.client.RestTemplateRetryClassifier
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.LocalDate

/**
 * Client-side abstraction for talking to the Tax service.
 */
interface TaxClient {
    fun getTaxContext(employerId: UtilityId, asOfDate: LocalDate, residentState: String? = null, workState: String? = null, localityCodes: List<String> = emptyList()): TaxContext
}

@ConfigurationProperties(prefix = "downstreams.tax")
class TaxClientProperties : DownstreamHttpClientProperties() {
    init {
        baseUrl = "http://localhost:8082"
    }
}

@Configuration
@EnableConfigurationProperties(TaxClientProperties::class)
class TaxClientConfig {

    @Bean
    fun httpTaxClient(props: TaxClientProperties, @Qualifier("taxRestTemplate") taxRestTemplate: RestTemplate, meterRegistry: MeterRegistry): TaxClient = HttpTaxClient(props, taxRestTemplate, meterRegistry)
}

class HttpTaxClient(
    private val props: TaxClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
) : TaxClient {

    private val logger = LoggerFactory.getLogger(HttpTaxClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    override fun getTaxContext(employerId: UtilityId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext {
        val params = ArrayList<String>(3 + localityCodes.size)
        params.add("asOf=$asOfDate")
        if (!residentState.isNullOrBlank()) params.add("residentState=$residentState")
        if (!workState.isNullOrBlank()) params.add("workState=$workState")
        localityCodes.forEach { code -> params.add("locality=$code") }

        val url = "${props.baseUrl}/employers/${employerId.value}/tax-context?" + params.joinToString("&")

        val dto = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "tax",
                        "operation",
                        "getTaxContext",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=tax op=getTaxContext attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.getForObject<TaxContextDto>(url)
        }
            ?: error("Tax service returned null TaxContext for employer=${employerId.value} asOf=$asOfDate")

        return dto.toDomain()
    }
}
