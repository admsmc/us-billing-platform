package com.example.uspayroll.worker.client

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import java.time.Duration
import java.time.LocalDate

@ConfigurationProperties(prefix = "hr")
data class HrClientProperties(
    var baseUrl: String = "http://localhost:8081",
    /** Connection + read timeout for HR HTTP calls. */
    var connectTimeout: Duration = Duration.ofSeconds(2),
    var readTimeout: Duration = Duration.ofSeconds(5),
    /** Number of retry attempts for transient failures on non-critical endpoints. */
    var maxRetries: Int = 2,
)

@Configuration
@EnableConfigurationProperties(HrClientProperties::class)
class HrClientConfig {

    /**
     * Shared RestTemplate for all HTTP-based clients in worker-service.
     *
     * For now we use a SimpleClientHttpRequestFactory with configurable
     * connect/read timeouts, which are injected via [HrClientProperties].
     */
    @Bean
    fun restTemplate(messageConverter: MappingJackson2HttpMessageConverter, props: HrClientProperties): RestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.connectTimeout)
            setReadTimeout(props.readTimeout)
        }
        return RestTemplate(listOf(messageConverter)).apply {
            setRequestFactory(requestFactory)
        }
    }

    @Bean
    fun httpHrClient(props: HrClientProperties, restTemplate: RestTemplate, meterRegistry: io.micrometer.core.instrument.MeterRegistry): HrClient =
        HttpHrClient(props, restTemplate, meterRegistry)
}

class HttpHrClient(
    private val props: HrClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry? = null,
) : HrClient {

    private val logger = LoggerFactory.getLogger(HttpHrClient::class.java)

    override fun getEmployeeSnapshot(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): EmployeeSnapshot? {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/snapshot?asOf=$asOfDate"
        return executeWithRetry("GET employee snapshot", url) {
            restTemplate.getForObject<EmployeeSnapshot>(url)
        }
    }

    override fun getPayPeriod(
        employerId: EmployerId,
        payPeriodId: String,
    ): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/$payPeriodId"
        return executeWithRetry("GET pay period", url) {
            restTemplate.getForObject<PayPeriod>(url)
        }
    }

    override fun findPayPeriodByCheckDate(
        employerId: EmployerId,
        checkDate: LocalDate,
    ): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/by-check-date?checkDate=$checkDate"
        return executeWithRetry("GET pay period by check date", url) {
            restTemplate.getForObject<PayPeriod>(url)
        }
    }

    override fun getGarnishmentOrders(
        employerId: EmployerId,
        employeeId: EmployeeId,
        asOfDate: LocalDate,
    ): List<GarnishmentOrder> {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments?asOf=$asOfDate"

        // Garnishments are important but not critical enough to fail the
        // entire payroll run if HR is briefly unavailable. In case of
        // repeated failures we log a warning and return an empty list.
        val dtoArray: Array<GarnishmentOrderDto>? =
            executeWithRetry("GET garnishments", url, failOnExhaustion = false) {
                restTemplate.getForObject<Array<GarnishmentOrderDto>>(url)
            }

        return dtoArray?.toList()?.map { it.toDomain() } ?: emptyList()
    }

    override fun recordGarnishmentWithholding(
        employerId: EmployerId,
        employeeId: EmployeeId,
        request: GarnishmentWithholdingRequest,
    ) {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings"

        // Withholding callbacks must be fire-and-forget from the payroll
        // worker perspective. If HR is unavailable after a few retries,
        // we log an error and continue without failing the paycheck.
        executeWithRetry("POST garnishment withholdings", url, failOnExhaustion = false) {
            restTemplate.postForLocation(url, request)
        }
    }

    private fun <T> executeWithRetry(
        operation: String,
        url: String,
        failOnExhaustion: Boolean = true,
        block: () -> T?,
    ): T? {
        var attempt = 0
        val maxAttempts = (props.maxRetries + 1).coerceAtLeast(1)
        var lastError: Exception? = null

        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (ex: RestClientException) {
                lastError = ex
                attempt += 1
                if (attempt >= maxAttempts) {
                    val message = "$operation failed after $attempt attempt(s) to $url: ${ex.message}"
                    // Final failure: record an error metric and either throw
                    // or degrade gracefully based on failOnExhaustion.
                    meterRegistry
                        ?.counter(
                            "payroll.garnishments.hr_errors",
                            "endpoint", operation,
                        )
                        ?.increment()

                    if (failOnExhaustion) {
                        logger.error(message, ex)
                        throw ex
                    } else {
                        logger.warn(message, ex)
                        return null
                    }
                } else {
                    logger.warn(
                        "$operation attempt $attempt/$maxAttempts failed for $url: ${ex.message}; will retry",
                        ex,
                    )
                }
            }
        }

        if (failOnExhaustion && lastError != null) {
            throw lastError
        }
        return null
    }
}
