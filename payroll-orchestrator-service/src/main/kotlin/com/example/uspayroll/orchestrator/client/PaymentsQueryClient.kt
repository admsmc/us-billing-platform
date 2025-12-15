package com.example.uspayroll.orchestrator.client

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
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

@ConfigurationProperties(prefix = "payments.http")
data class PaymentsQueryClientProperties(
    /** Base URL for payments-service. */
    var baseUrl: String = "http://localhost:8086",
)

@Configuration
@EnableConfigurationProperties(PaymentsQueryClientProperties::class)
class PaymentsQueryClientConfig {
    @Bean
    fun paymentsQueryClient(props: PaymentsQueryClientProperties, hrRestTemplate: RestTemplate): PaymentsQueryClient {
        return HttpPaymentsQueryClient(props, hrRestTemplate)
    }
}

class HttpPaymentsQueryClient(
    private val props: PaymentsQueryClientProperties,
    private val restTemplate: RestTemplate,
) : PaymentsQueryClient {

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
        val rows = restTemplate.getForObject<Array<PaymentViewDto>>(url)
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
