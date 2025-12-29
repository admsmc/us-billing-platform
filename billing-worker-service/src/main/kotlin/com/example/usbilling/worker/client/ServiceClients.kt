package com.example.usbilling.worker.client

import com.example.usbilling.billing.model.BillingPeriod
import com.example.usbilling.billing.model.CustomerSnapshot
import com.example.usbilling.billing.model.RateContext
import com.example.usbilling.billing.model.RegulatoryContext
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

/**
 * HTTP client for customer-service.
 * Fetches customer snapshots and billing periods.
 */
@Component
class CustomerServiceClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${services.customer.url:http://localhost:8081}") private val customerServiceUrl: String,
) {
    private val webClient: WebClient by lazy {
        webClientBuilder.baseUrl(customerServiceUrl).build()
    }

    fun getCustomerSnapshot(
        utilityId: UtilityId,
        customerId: CustomerId,
        asOfDate: LocalDate,
    ): CustomerSnapshot? = try {
        webClient.get()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/snapshot?asOfDate={date}",
                utilityId.value,
                customerId.value,
                asOfDate.toString(),
            )
            .retrieve()
            .bodyToMono<CustomerSnapshot>()
            .block()
    } catch (e: Exception) {
        // Log error and return null
        null
    }

    fun getBillingPeriod(
        utilityId: UtilityId,
        customerId: CustomerId,
        billingPeriodId: String,
    ): BillingPeriodWithReads? = try {
        webClient.get()
            .uri(
                "/utilities/{utilityId}/customers/{customerId}/billing-periods/{periodId}",
                utilityId.value,
                customerId.value,
                billingPeriodId,
            )
            .retrieve()
            .bodyToMono<BillingPeriodWithReads>()
            .block()
    } catch (e: Exception) {
        null
    }
}

/**
 * Response wrapper from customer-service billing period endpoint.
 */
data class BillingPeriodWithReads(
    val period: BillingPeriod,
    val meterReads: List<com.example.usbilling.billing.model.MeterRead>,
)

/**
 * HTTP client for rate-service.
 * Fetches rate contexts with tariff information.
 */
@Component
class RateServiceClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${services.rate.url:http://localhost:8082}") private val rateServiceUrl: String,
) {
    private val webClient: WebClient by lazy {
        webClientBuilder.baseUrl(rateServiceUrl).build()
    }

    fun getRateContext(
        utilityId: UtilityId,
        serviceState: String,
        asOfDate: LocalDate,
    ): RateContext? = try {
        webClient.get()
            .uri(
                "/utilities/{utilityId}/rates/context?serviceState={state}&asOfDate={date}",
                utilityId.value,
                serviceState,
                asOfDate.toString(),
            )
            .retrieve()
            .bodyToMono<RateContext>()
            .block()
    } catch (e: Exception) {
        null
    }
}

/**
 * HTTP client for regulatory-service.
 * Fetches regulatory contexts with PUC charges.
 */
@Component
class RegulatoryServiceClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${services.regulatory.url:http://localhost:8083}") private val regulatoryServiceUrl: String,
) {
    private val webClient: WebClient by lazy {
        webClientBuilder.baseUrl(regulatoryServiceUrl).build()
    }

    fun getRegulatoryContext(
        utilityId: UtilityId,
        jurisdiction: String,
        asOfDate: LocalDate,
    ): RegulatoryContext? = try {
        webClient.get()
            .uri(
                "/utilities/{utilityId}/regulatory/context?jurisdiction={jurisdiction}&asOfDate={date}",
                utilityId.value,
                jurisdiction,
                asOfDate.toString(),
            )
            .retrieve()
            .bodyToMono<RegulatoryContext>()
            .block()
    } catch (e: Exception) {
        null
    }
}
