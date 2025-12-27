package com.example.usbilling.orchestrator.client

import com.example.usbilling.labor.http.LaborStandardsContextDto
import com.example.usbilling.labor.http.toDomain
import com.example.usbilling.payroll.model.LaborStandardsContext
import com.example.usbilling.shared.EmployerId
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
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

interface LaborStandardsClient {
    fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String> = emptyList()): LaborStandardsContext?
}

@ConfigurationProperties(prefix = "downstreams.labor")
class LaborClientProperties : DownstreamHttpClientProperties() {
    init {
        baseUrl = "http://localhost:8083"
    }
}

@Configuration
@EnableConfigurationProperties(LaborClientProperties::class)
class LaborClientConfig {

    @Bean
    fun httpLaborStandardsClient(props: LaborClientProperties, @Qualifier("laborRestTemplate") laborRestTemplate: RestTemplate, meterRegistry: MeterRegistry): LaborStandardsClient = HttpLaborStandardsClient(props, laborRestTemplate, meterRegistry)
}

class HttpLaborStandardsClient(
    private val props: LaborClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
) : LaborStandardsClient {

    private val logger = LoggerFactory.getLogger(HttpLaborStandardsClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>): LaborStandardsContext? {
        if (workState == null) return null

        val base = "${props.baseUrl}/employers/${employerId.value}/labor-standards"
        val baseQuery = if (homeState != null) {
            "$base?asOf=$asOfDate&state=$workState&homeState=$homeState"
        } else {
            "$base?asOf=$asOfDate&state=$workState"
        }

        val localityQuery = if (localityCodes.isNotEmpty()) {
            localityCodes.joinToString(separator = "") { code -> "&locality=$code" }
        } else {
            ""
        }

        val url = baseQuery + localityQuery

        @Suppress("SwallowedException")
        return try {
            val dto = guardrails.execute(
                isRetryable = RestTemplateRetryClassifier::isRetryable,
                onRetry = { attempt ->
                    meterRegistry
                        ?.counter(
                            "uspayroll.http.client.retries",
                            "client",
                            "labor",
                            "operation",
                            "getLaborStandards",
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=labor op=getLaborStandards attempt={}/{} delayMs={} url={} error={}",
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
            ) {
                val response: ResponseEntity<LaborStandardsContextDto> = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    LaborStandardsContextDto::class.java,
                )
                response.body
            }

            dto?.toDomain()
        } catch (_: HttpClientErrorException.NotFound) {
            // REST-y contract: 404 means "no applicable labor standards".
            null
        }
    }
}
