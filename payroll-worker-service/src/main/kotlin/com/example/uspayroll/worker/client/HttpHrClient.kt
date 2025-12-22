package com.example.uspayroll.worker.client

import com.example.uspayroll.hr.client.HrClient
import com.example.uspayroll.hr.client.HrClientProperties
import com.example.uspayroll.hr.http.GarnishmentOrderDto
import com.example.uspayroll.hr.http.GarnishmentWithholdingRequest
import com.example.uspayroll.hr.http.toDomain
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.client.CircuitBreakerOpenException
import com.example.uspayroll.web.client.HttpClientGuardrails
import com.example.uspayroll.web.client.RestTemplateRetryClassifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

@Configuration
@EnableConfigurationProperties(HrClientProperties::class)
class HrClientConfig {

    @Bean
    fun httpHrClient(props: HrClientProperties, @Qualifier("hrRestTemplate") hrRestTemplate: RestTemplate, meterRegistry: io.micrometer.core.instrument.MeterRegistry): HrClient = HttpHrClient(props, hrRestTemplate, meterRegistry)
}

class HttpHrClient(
    private val props: HrClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: io.micrometer.core.instrument.MeterRegistry? = null,
) : HrClient {

    private val logger = LoggerFactory.getLogger(HttpHrClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    override fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot? {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/snapshot?asOf=$asOfDate"
        return try {
            executeWithGuardrails("GET employee snapshot", url) {
                // Avoid Kotlin RestTemplate.getForObject<T>() which throws when the body is empty (null).
                restTemplate.getForObject(url, EmployeeSnapshot::class.java)
            }
        } catch (ex: HttpClientErrorException.NotFound) {
            logger.info(
                "hr-client.snapshot.not_found employer={} employee={} asOf={} url={}",
                employerId.value,
                employeeId.value,
                asOfDate,
                url,
            )
            null
        }
    }

    override fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/$payPeriodId"
        return try {
            executeWithGuardrails("GET pay period", url) {
                // hr-service returns `null` when not found (200 + empty body). Use the Java overload to allow null.
                restTemplate.getForObject(url, PayPeriod::class.java)
            }
        } catch (ex: HttpClientErrorException.NotFound) {
            logger.info(
                "hr-client.pay_period.not_found employer={} payPeriodId={} url={}",
                employerId.value,
                payPeriodId,
                url,
            )
            null
        }
    }

    override fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod? {
        val url = "${props.baseUrl}/employers/${employerId.value}/pay-periods/by-check-date?checkDate=$checkDate"
        return try {
            executeWithGuardrails("GET pay period by check date", url) {
                restTemplate.getForObject(url, PayPeriod::class.java)
            }
        } catch (ex: HttpClientErrorException.NotFound) {
            logger.info(
                "hr-client.pay_period_by_check_date.not_found employer={} checkDate={} url={}",
                employerId.value,
                checkDate,
                url,
            )
            null
        }
    }

    override fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<GarnishmentOrder> {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments?asOf=$asOfDate"

        // Garnishments are important but not critical enough to fail the
        // entire payroll run if HR is briefly unavailable. In case of
        // repeated failures we log a warning and return an empty list.
        val dtoArray: Array<GarnishmentOrderDto>? =
            executeWithGuardrails("GET garnishments", url, failOnExhaustion = false) {
                restTemplate.getForObject(url, Array<GarnishmentOrderDto>::class.java)
            }

        return dtoArray?.toList()?.map { it.toDomain() } ?: emptyList()
    }

    override fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest) {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings"

        // Withholding callbacks must be fire-and-forget from the payroll
        // worker perspective. If HR is unavailable after a few retries,
        // we log an error and continue without failing the paycheck.
        executeWithGuardrails("POST garnishment withholdings", url, failOnExhaustion = false) {
            restTemplate.postForLocation(url, request)
        }
    }

    private fun <T> executeWithGuardrails(operation: String, url: String, failOnExhaustion: Boolean = true, block: () -> T?): T? {
        return try {
            guardrails.execute(
                isRetryable = RestTemplateRetryClassifier::isRetryable,
                onRetry = { attempt ->
                    meterRegistry
                        ?.counter(
                            "uspayroll.http.client.retries",
                            "client",
                            "hr",
                            "operation",
                            operation,
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=hr op={} attempt={}/{} delayMs={} url={} error={}",
                        operation,
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
                block = block,
            )
        } catch (ex: RestClientException) {
            val message = "$operation failed to $url: ${ex.message}"

            // Keep existing garnishments failure metric shape for dashboards.
            meterRegistry
                ?.counter(
                    "payroll.garnishments.hr_errors",
                    "endpoint",
                    operation,
                )
                ?.increment()

            if (failOnExhaustion) {
                logger.error(message, ex)
                throw ex
            } else {
                logger.warn(message, ex)
                return null
            }
        } catch (ex: CircuitBreakerOpenException) {
            val message = "$operation failed fast (circuit open) to $url: ${ex.message}"

            meterRegistry
                ?.counter(
                    "payroll.garnishments.hr_errors",
                    "endpoint",
                    operation,
                )
                ?.increment()

            if (failOnExhaustion) {
                logger.error(message, ex)
                throw ex
            } else {
                logger.warn(message, ex)
                return null
            }
        }
    }
}
