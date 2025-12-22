package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.web.client.CircuitBreakerOpenException
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
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.DayOfWeek
import java.time.LocalDate

interface TimeClient {
    fun getTimeSummary(employerId: EmployerId, employeeId: EmployeeId, start: LocalDate, end: LocalDate, workState: String? = null, weekStartsOn: DayOfWeek = DayOfWeek.MONDAY): TimeSummary

    data class TimeSummary(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
        val cashTipsCents: Long,
        val chargedTipsCents: Long,
        val allocatedTipsCents: Long,
        val commissionCents: Long,
        val bonusCents: Long,
        val reimbursementNonTaxableCents: Long,
    ) {
        val totalTipsCents: Long get() = cashTipsCents + chargedTipsCents + allocatedTipsCents
        val totalOtherEarningsCents: Long get() = commissionCents + bonusCents + reimbursementNonTaxableCents
    }
}

@ConfigurationProperties(prefix = "downstreams.time")
class TimeClientProperties : DownstreamHttpClientProperties() {
    var enabled: Boolean = false

    init {
        baseUrl = "http://localhost:8084"
    }
}

@Configuration
@EnableConfigurationProperties(TimeClientProperties::class)
class TimeClientConfig {

    @Bean
    fun httpTimeClient(props: TimeClientProperties, @Qualifier("timeRestTemplate") timeRestTemplate: RestTemplate, meterRegistry: MeterRegistry): TimeClient = HttpTimeClient(props, timeRestTemplate, meterRegistry)
}

private data class TimeSummaryResponse(
    val totals: Totals,
) {
    data class Totals(
        val regularHours: Double,
        val overtimeHours: Double,
        val doubleTimeHours: Double,
        val cashTipsCents: Long = 0L,
        val chargedTipsCents: Long = 0L,
        val allocatedTipsCents: Long = 0L,
        val commissionCents: Long = 0L,
        val bonusCents: Long = 0L,
        val reimbursementNonTaxableCents: Long = 0L,
    )
}

class HttpTimeClient(
    private val props: TimeClientProperties,
    private val restTemplate: RestTemplate,
    private val meterRegistry: MeterRegistry? = null,
) : TimeClient {

    private val logger = LoggerFactory.getLogger(HttpTimeClient::class.java)

    private val guardrails = HttpClientGuardrails.with(
        maxRetries = props.maxRetries,
        initialBackoff = props.retryInitialBackoff,
        maxBackoff = props.retryMaxBackoff,
        backoffMultiplier = props.retryBackoffMultiplier,
        circuitBreakerPolicy = if (props.circuitBreakerEnabled) props.circuitBreaker else null,
    )

    override fun getTimeSummary(employerId: EmployerId, employeeId: EmployeeId, start: LocalDate, end: LocalDate, workState: String?, weekStartsOn: DayOfWeek): TimeClient.TimeSummary {
        if (!props.enabled) {
            return emptySummary()
        }

        val params = ArrayList<String>(4)
        params.add("start=$start")
        params.add("end=$end")
        if (!workState.isNullOrBlank()) params.add("workState=$workState")
        params.add("weekStartsOn=${weekStartsOn.name}")

        val url = "${props.baseUrl}/employers/${employerId.value}/employees/${employeeId.value}/time-summary?" + params.joinToString("&")

        val dto = try {
            guardrails.execute(
                isRetryable = RestTemplateRetryClassifier::isRetryable,
                onRetry = { attempt ->
                    meterRegistry
                        ?.counter(
                            "uspayroll.http.client.retries",
                            "client",
                            "time",
                            "operation",
                            "getTimeSummary",
                        )
                        ?.increment()

                    logger.warn(
                        "http.client.retry client=time op=getTimeSummary attempt={}/{} delayMs={} url={} error={}",
                        attempt.attempt,
                        attempt.maxAttempts,
                        attempt.nextDelay.toMillis(),
                        url,
                        attempt.throwable.message,
                    )
                },
            ) {
                restTemplate.getForObject(url, TimeSummaryResponse::class.java)
            }
        } catch (ex: RestClientException) {
            meterRegistry
                ?.counter(
                    "uspayroll.http.client.degraded",
                    "client",
                    "time",
                    "operation",
                    "getTimeSummary",
                )
                ?.increment()

            logger.warn("http.client.degraded client=time op=getTimeSummary url={} error={}", url, ex.message, ex)
            return emptySummary()
        } catch (ex: CircuitBreakerOpenException) {
            meterRegistry
                ?.counter(
                    "uspayroll.http.client.degraded",
                    "client",
                    "time",
                    "operation",
                    "getTimeSummary",
                )
                ?.increment()

            logger.warn("http.client.degraded client=time op=getTimeSummary url={} error={}", url, ex.message, ex)
            return emptySummary()
        }

        if (dto == null) return emptySummary()

        return TimeClient.TimeSummary(
            regularHours = dto.totals.regularHours,
            overtimeHours = dto.totals.overtimeHours,
            doubleTimeHours = dto.totals.doubleTimeHours,
            cashTipsCents = dto.totals.cashTipsCents,
            chargedTipsCents = dto.totals.chargedTipsCents,
            allocatedTipsCents = dto.totals.allocatedTipsCents,
            commissionCents = dto.totals.commissionCents,
            bonusCents = dto.totals.bonusCents,
            reimbursementNonTaxableCents = dto.totals.reimbursementNonTaxableCents,
        )
    }

    private fun emptySummary(): TimeClient.TimeSummary = TimeClient.TimeSummary(
        regularHours = 0.0,
        overtimeHours = 0.0,
        doubleTimeHours = 0.0,
        cashTipsCents = 0L,
        chargedTipsCents = 0L,
        allocatedTipsCents = 0L,
        commissionCents = 0L,
        bonusCents = 0L,
        reimbursementNonTaxableCents = 0L,
    )
}
