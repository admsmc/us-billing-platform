package com.example.usbilling.orchestrator.client

import com.example.usbilling.hr.client.HrClient
import com.example.usbilling.hr.client.HrClientProperties
import com.example.usbilling.hr.http.GarnishmentOrderDto
import com.example.usbilling.hr.http.GarnishmentWithholdingRequest
import com.example.usbilling.hr.http.toDomain
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.web.client.HttpClientGuardrails
import com.example.usbilling.web.client.RestTemplateRetryClassifier
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

@Configuration
@EnableConfigurationProperties(HrClientProperties::class)
class HrClientConfig {

    @Bean
    fun httpHrClient(props: HrClientProperties, @Qualifier("hrRestTemplate") hrRestTemplate: RestTemplate, meterRegistry: MeterRegistry): HrClient = HttpHrClient(props, hrRestTemplate, meterRegistry)
}

class HttpHrClient(
    private val props: HrClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
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
        // Avoid Kotlin RestTemplate.getForObject<T>() which throws when the body is empty (null).
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
                            "getEmployeeSnapshot",
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=hr op=getEmployeeSnapshot attempt={}/{} delayMs={} url={} error={}",
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
            ) {
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
        // hr-service returns `null` when not found (200 + empty body). Use the Java overload to allow null.
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
                            "getPayPeriod",
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=hr op=getPayPeriod attempt={}/{} delayMs={} url={} error={}",
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
            ) {
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
            guardrails.execute(
                isRetryable = RestTemplateRetryClassifier::isRetryable,
                onRetry = { attempt ->
                    meterRegistry
                        ?.counter(
                            "uspayroll.http.client.retries",
                            "client",
                            "hr",
                            "operation",
                            "findPayPeriodByCheckDate",
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=hr op=findPayPeriodByCheckDate attempt={}/{} delayMs={} url={} error={}",
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
            ) {
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

    override fun getGarnishmentOrders(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): List<com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder> {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments?asOf=$asOfDate"
        val dtoArray = guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "hr",
                        "operation",
                        "getGarnishmentOrders",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=hr op=getGarnishmentOrders attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.getForObject(url, Array<GarnishmentOrderDto>::class.java)
        }
            ?: return emptyList()
        return dtoArray.toList().map { it.toDomain() }
    }

    override fun recordGarnishmentWithholding(employerId: EmployerId, employeeId: EmployeeId, request: GarnishmentWithholdingRequest) {
        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/garnishments/withholdings"
        guardrails.execute(
            isRetryable = RestTemplateRetryClassifier::isRetryable,
            onRetry = { attempt ->
                meterRegistry
                    ?.counter(
                        "uspayroll.http.client.retries",
                        "client",
                        "hr",
                        "operation",
                        "recordGarnishmentWithholding",
                    )
                    ?.increment()

                logger.warn(
                    "http.client.retry client=hr op=recordGarnishmentWithholding attempt={}/{} delayMs={} url={} error={}",
                    attempt.attempt,
                    attempt.maxAttempts,
                    attempt.nextDelay.toMillis(),
                    url,
                    attempt.throwable.message,
                )
            },
        ) {
            restTemplate.postForLocation(url, request)
        }
    }
}
